"""The Rubric Agent - the quality gate from docs/product-and-architecture.md
§7. A single-node stateless graph, called by Spring only when the Interrogator
itself signals ready_to_conclude (see grilld-backend's SessionService) - this
is the antagonistic check on that signal, not a per-turn cost.

Built on openevals' create_async_llm_as_judge per
docs/decisions-and-technical-architecture.md §11.4 rather than a hand-rolled
prompt/parse loop - it's exactly the "custom rubric in, structured
{score, reasoning} out" shape that factory exists for.
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph
from openevals.llm import create_async_llm_as_judge
from pydantic import BaseModel
from typing_extensions import TypedDict

from grilld_ai_service.model_tiers import model_for
from grilld_ai_service.rubric.prompt import RUBRIC_PROMPT
from grilld_ai_service.rubric.schemas import DimensionResult


class SlotSummary(TypedDict):
    slot_key: str
    status: str
    value: str | None
    importance: int


class RubricState(TypedDict, total=False):
    # Input
    session_id: str
    brief_json: str
    slots: list[SlotSummary]

    # Output - the JSON Spring's HttpAiServiceClient reads directly.
    rubric_result: dict


class JudgeOutput(BaseModel):
    """What the LLM judge actually decides - scores only. verdict/open_gaps
    are derived deterministically in _compute_verdict below, not asked of the
    model: per docs/decisions-and-technical-architecture.md §11.4, openevals
    changes the scoring primitive (categorical vs 1-5), not who decides
    accept/reject - that's fixed control flow, same as before the openevals
    switch. Letting the model freely decide the verdict too made it an extra,
    unreliable holistic judgment on top of its own per-dimension scores -
    observed in testing to disagree with its own scores (e.g. call a brief
    "probe_further" with zero FAILs)."""

    dimensions: list[DimensionResult]


def _format_slots(slots: list[SlotSummary]) -> str:
    if not slots:
        return "(no slots recorded yet)"
    lines = []
    for slot in slots:
        value = slot.get("value") or "(unfilled)"
        lines.append(f"- {slot['slot_key']} [{slot['status']}, importance={slot['importance']}]: {value}")
    return "\n".join(lines)


def _compute_verdict(dimensions: list[dict]) -> tuple[str, list[str]]:
    """accept requires every dimension to PASS - a BORDERLINE is still not
    confident enough to build from, per product-and-architecture.md §7's
    "adversary in the loop" framing."""
    open_gaps = [
        f"{d['dimension']}: {d['reasoning']}"
        for d in dimensions
        if d["score"] in ("FAIL", "BORDERLINE")
    ]
    verdict = "accept" if not open_gaps else "probe_further"
    return verdict, open_gaps


async def evaluate(state: RubricState) -> dict:
    judge = create_async_llm_as_judge(
        prompt=RUBRIC_PROMPT,
        model=model_for("rubric"),
        output_schema=JudgeOutput,
    )
    result = await judge(
        brief_summary=state.get("brief_json") or "{}",
        slot_state=_format_slots(state.get("slots", [])),
    )
    # openevals returns either a JudgeOutput instance or an already-plain
    # dict depending on judge/model wiring - normalize to a plain dict so
    # Spring always reads the same JSON shape either way.
    if hasattr(result, "model_dump"):
        result = result.model_dump()
    dimensions = result["dimensions"]
    verdict, open_gaps = _compute_verdict(dimensions)
    return {"rubric_result": {"dimensions": dimensions, "verdict": verdict, "open_gaps": open_gaps}}


def build_graph():
    graph = StateGraph(RubricState)
    graph.add_node("evaluate", evaluate)
    graph.add_edge(START, "evaluate")
    graph.add_edge("evaluate", END)
    return graph.compile()


# Stateless, like the Interrogator - one call, no thread, no checkpointer.
graph = build_graph()
