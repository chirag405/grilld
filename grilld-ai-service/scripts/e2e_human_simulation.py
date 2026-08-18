"""Run Grilld's complete AI pipeline with simulated human conversations.

This is an intentionally expensive live-system verification: it invokes the
configured LLMs, web-search agents, rubric, scale calibrator, and all ten
specialists. It does not mock agent output. Run from ``grilld-ai-service``:

    uv run python scripts/e2e_human_simulation.py
    uv run python scripts/e2e_human_simulation.py --scenario comprehensive

Generated artifacts and a machine-readable report are written under
``e2e-output/`` (gitignored). Use ``--interview-only`` for the cheaper semantic
intent suite without specialist document generation.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

load_dotenv()

# This harness is deliberately cost-bounded. Set the blanket override before
# importing any graph module because specialist model instances are resolved
# while those modules are imported. Production configuration is untouched.
E2E_MODEL = "anthropic:claude-haiku-4-5-20251001"
os.environ["GRILLD_AI_MODEL"] = E2E_MODEL

from grilld_ai_service.graph import build_orchestrator  # noqa: E402
from grilld_ai_service.interrogator.graph import graph as interrogator  # noqa: E402
from grilld_ai_service.rubric.graph import graph as rubric  # noqa: E402
from grilld_ai_service.scale_calibrator.graph import graph as scale_calibrator  # noqa: E402


SEED_SLOTS = {
    "problem_statement": ("Who this is for and what's broken today", 5),
    "target_user": ("The specific person who has this problem", 5),
    "scale_expectation": ("Concrete user and traffic expectations", 5),
    "timeline": ("When this needs to ship", 4),
    "team_shape": ("Who is building it", 5),
    "builder_skillset": ("What the builder already knows", 4),
    "success_definition": ("What working looks like", 4),
    "hard_constraints": ("Budget, compliance, and integration constraints", 4),
}

REQUIRED_DOCUMENTS = {
    "/docs/PROJECT_BRIEF.md",
    "/docs/MARKET_ANALYSIS.md",
    "/docs/COMPETITION.md",
    "/docs/STRATEGY.md",
    "/docs/TECH_STACK.md",
    "/docs/ARCHITECTURE.md",
    "/docs/INFRA.md",
    "/docs/ROADMAP.md",
    "/docs/SKILLS_NEEDED.md",
    "/docs/CONSISTENCY_REPORT.md",
    "/diagrams/architecture.mmd",
    "/diagrams/data-flow.mmd",
    "/diagrams/deployment.mmd",
    "/agent-kit/AGENTS.md",
    "/agent-kit/CLAUDE.md",
}


@dataclass(frozen=True)
class HumanTurn:
    message: str
    expected_intent: str


@dataclass(frozen=True)
class Scenario:
    name: str
    idea: str
    turns: tuple[HumanTurn, ...]


SCENARIOS = (
    Scenario(
        name="comprehensive",
        idea="A tool for freelancers to track unpaid invoices and follow up without awkward manual chasing.",
        turns=(
            HumanTurn("Before I answer, what exactly do you mean by an MVP?", "QUESTION"),
            HumanTurn("The first users are solo freelance designers and developers.", "ANSWER"),
            HumanTurn("Actually, correction: small two-to-five-person agencies are the primary user; solo freelancers are secondary.", "CORRECTION"),
            HumanTurn("I don't know the exact traffic. Skip that and choose a responsible starting assumption.", "SKIP"),
            HumanTurn("Sure, the server should melt on day one — I am joking. Realistically maybe 100 early users.", "ANSWER"),
            HumanTurn("Unrelated question: can you tell me tomorrow's weather in Tokyo?", "UNRELATED"),
            HumanTurn("We use Next.js and Spring, have two technical builders, and want a public beta in eight weeks.", "ANSWER"),
            HumanTurn("You have enough context. Decide the remaining low-risk details and finish the brief.", "FINISH"),
        ),
    ),
    Scenario(
        name="terse_and_uncertain",
        idea="An app that helps local repair shops schedule jobs.",
        turns=(
            HumanTurn("Owners and front-desk staff.", "ANSWER"),
            HumanTurn("Not sure. Recommend what makes sense for version one.", "SKIP"),
            HumanTurn("No fixed budget, one developer, web only.", "ANSWER"),
            HumanTurn("Finish with sensible assumptions.", "FINISH"),
        ),
    ),
    Scenario(
        name="midstream_questions",
        idea="A private knowledge base for a small legal team.",
        turns=(
            HumanTurn("Why are you asking about compliance this early?", "QUESTION"),
            HumanTurn("Five lawyers, confidential client documents, and EU clients.", "ANSWER"),
            HumanTurn("Correction: documents must stay in our own cloud account.", "CORRECTION"),
            HumanTurn("Please finish now and clearly list anything you assumed.", "FINISH"),
        ),
    ),
)


def _open_slots(values: dict[str, str], waived: set[str], derived: dict[str, tuple[str, int]]) -> list[dict[str, Any]]:
    all_slots = {**SEED_SLOTS, **derived}
    return [
        {"slot_key": key, "description": description, "importance": importance, "priority": float(importance)}
        for key, (description, importance) in all_slots.items()
        if key not in values and key not in waived
    ]


async def run_interview(scenario: Scenario) -> dict[str, Any]:
    values: dict[str, str] = {}
    waived: set[str] = set()
    derived: dict[str, tuple[str, int]] = {}
    exchanges: list[dict[str, Any]] = []
    traces: list[dict[str, Any]] = []

    async def invoke(recent_turns: list[dict[str, Any]]) -> dict[str, Any]:
        state = await interrogator.ainvoke({
            "session_id": f"e2e-{scenario.name}",
            "raw_idea": scenario.idea,
            "compacted_brief_summary": json.dumps(values) if values else None,
            # WorkingContextAssembler sends newest-first; matching that order
            # is essential because the Interrogator treats index 0 as the
            # message whose semantic intent it must classify.
            "recent_turns": list(reversed(recent_turns))[:8],
            "open_slots_ranked": _open_slots(values, waived, derived),
            "answered_topics": list(values),
            "open_gaps": [],
        })
        result = state["turn_result"]
        trace = {
            "summary": result.get("reasoning_summary"),
            "decisions": result.get("reasoning_decisions", []),
            "assumptions": result.get("reasoning_assumptions", []),
        }
        if not trace["summary"]:
            raise AssertionError("Interrogator returned no user-safe reasoning summary")
        traces.append(trace)
        for fact in result.get("extracted_facts", []):
            values[fact["slot_key"]] = fact["value"]
        for slot in result.get("new_slots", []):
            derived[slot["key"]] = (slot["description"], slot["importance"])
        waived.update(slot["key"] for slot in result.get("waived_slots", []))
        return result

    opening = await invoke([])
    question = opening.get("next_question")
    if not question:
        raise AssertionError(f"Opening turn did not produce a question: {opening}")

    observed_intents: list[str] = []
    concluded = False
    for number, human_turn in enumerate(scenario.turns, start=1):
        exchange = {
            "turn_number": number,
            "question_text": question["text"] if question else "Continue",
            "answer_text": human_turn.message,
        }
        exchanges.append(exchange)
        result = await invoke(exchanges)
        intent = result.get("intent")
        observed_intents.append(intent)
        if intent != human_turn.expected_intent:
            raise AssertionError(
                f"{scenario.name} turn {number}: expected semantic intent "
                f"{human_turn.expected_intent}, got {intent}: {human_turn.message!r}"
            )
        if intent in {"QUESTION", "UNRELATED"} and not result.get("assistant_message"):
            raise AssertionError(f"{intent} turn did not include a direct assistant response")
        concluded = bool(result.get("ready_to_conclude"))
        question = result.get("next_question")
        if concluded:
            break
        if not question:
            raise AssertionError(
                f"Turn {number} neither concluded nor supplied a next question: {result}"
            )

    if not concluded:
        raise AssertionError(f"Scenario {scenario.name} exhausted its human turns without concluding")

    slots = []
    for key, (description, importance) in {**SEED_SLOTS, **derived}.items():
        status = "FILLED" if key in values else "WAIVED" if key in waived else "OPEN"
        slots.append({"slot_key": key, "status": status, "value": values.get(key), "importance": importance})
    return {
        "brief": values,
        "slots": slots,
        "traces": traces,
        "observed_intents": observed_intents,
        "unresolved": [slot["slot_key"] for slot in slots if slot["status"] == "OPEN"],
    }


def _file_text(file_data: Any) -> str:
    if isinstance(file_data, str):
        return file_data
    if isinstance(file_data, dict):
        content = file_data.get("content", "")
        return "\n".join(content) if isinstance(content, list) else str(content)
    return str(file_data)


def validate_and_write_files(files: dict[str, Any], output_dir: Path) -> list[str]:
    paths = set(files)
    missing = REQUIRED_DOCUMENTS - paths
    if missing:
        raise AssertionError(f"Missing required generated files: {sorted(missing)}")
    if not any(path.startswith("/agent-kit/agents/") and path.endswith(".md") for path in paths):
        raise AssertionError("No scoped agent role Markdown files were generated")
    if not any(path.startswith("/agent-kit/skills/") and path.endswith("SKILL.md") for path in paths):
        raise AssertionError("No phase skill Markdown files were generated")

    for path, data in files.items():
        text = _file_text(data).strip()
        if path.endswith((".md", ".mmd")) and len(text) < 50:
            raise AssertionError(f"Generated artifact is empty or stub-like: {path}")
        destination = output_dir / path.lstrip("/")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text + "\n", encoding="utf-8")
    return sorted(paths)


async def run_scenario(scenario: Scenario, output_root: Path, interview_only: bool) -> dict[str, Any]:
    interview = await run_interview(scenario)
    brief_json = json.dumps(interview["brief"], indent=2)
    rubric_state = await rubric.ainvoke({
        "session_id": f"e2e-{scenario.name}", "brief_json": brief_json, "slots": interview["slots"]
    })
    scale_state = await scale_calibrator.ainvoke({"brief_json": brief_json})
    report: dict[str, Any] = {
        "scenario": scenario.name,
        "model": E2E_MODEL,
        "interview": interview,
        "rubric": rubric_state["rubric_result"],
        "scale": scale_state["calibration_result"],
        "generated_files": [],
    }
    if interview_only:
        return report

    unresolved = "\n".join(f"- {key}" for key in interview["unresolved"]) or "- None"
    orchestrator = build_orchestrator(checkpointer=None)
    generated = await orchestrator.ainvoke(
        {"messages": [{"role": "user", "content": (
            f"Here is the project brief (JSON):\n{brief_json}\n\n"
            f"The assigned scale tier is {scale_state['calibration_result']['tier']}.\n\n"
            f"Unresolved items that must be documented as assumptions:\n{unresolved}\n\nBegin."
        )}]},
        config={"recursion_limit": 180},
    )
    report["generated_files"] = validate_and_write_files(generated.get("files", {}), output_root / scenario.name)
    return report


async def async_main(args: argparse.Namespace) -> int:
    selected = [scenario for scenario in SCENARIOS if args.scenario in ("all", scenario.name)]
    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
    output_root = Path(args.output_dir) / stamp
    output_root.mkdir(parents=True, exist_ok=True)
    reports = []
    for scenario in selected:
        print(f"[e2e] running {scenario.name}", flush=True)
        reports.append(await run_scenario(scenario, output_root, args.interview_only))
        print(f"[e2e] passed {scenario.name}", flush=True)
    report_path = output_root / "report.json"
    report_path.write_text(json.dumps(reports, indent=2), encoding="utf-8")
    print(f"[e2e] all scenarios passed; report: {report_path}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", default="all", choices=["all", *(s.name for s in SCENARIOS)])
    parser.add_argument("--interview-only", action="store_true", help="Skip the expensive specialist roster")
    parser.add_argument("--output-dir", default="e2e-output")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(async_main(parse_args())))
    except Exception as exc:
        print(f"[e2e] FAILED: {exc}", file=sys.stderr)
        raise
