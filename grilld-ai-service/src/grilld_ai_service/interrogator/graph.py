"""The Interrogator - a LangGraph StateGraph, not a Deep Agent, per
docs/decisions-and-technical-architecture.md §11.1: its control flow
(vagueness-gated prompting) needs precise custom edges that middleware doesn't
give you. Exposed as its own top-level graph (langgraph.json's "interrogator"
entry) rather than nested under the Orchestrator - interrogation and
generation are different lifecycle phases called by Spring differently (one
call per interview turn vs. one call per whole generation run), not a single
workflow.

Two nodes: check_vagueness (deterministic, cheap) -> generate_turn (the actual
LLM call, structured output). No contradiction-detection node here - that
needs the full stored brief, which only Java has; see
SessionService.applyExtraction() in grilld-backend.
"""

from __future__ import annotations

from langchain.chat_models import init_chat_model
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

from grilld_ai_service.interrogator.schemas import InterrogatorTurnResult
from grilld_ai_service.interrogator.vagueness import detect_vagueness
from grilld_ai_service.model_tiers import model_for


class InterrogatorState(TypedDict, total=False):
    # Input - mirrors grilld-backend's WorkingContext record exactly.
    session_id: str
    raw_idea: str
    compacted_brief_summary: str | None
    recent_turns: list[dict]
    open_slots_ranked: list[dict]
    answered_topics: list[str]
    open_gaps: list[str]  # set only when the Rubric Agent just rejected a conclude attempt

    # Intermediate (check_vagueness's output, generate_turn's input)
    vague_terms_found: list[str]

    # Output - the JSON Spring's HttpAiServiceClient reads directly.
    turn_result: dict


def check_vagueness(state: InterrogatorState) -> dict:
    last_answer = state["recent_turns"][0]["answer_text"] if state.get("recent_turns") else None
    return {"vague_terms_found": detect_vagueness(last_answer)}


def _build_prompt(state: InterrogatorState) -> str:
    is_opening = not state.get("recent_turns")
    slots_text = "\n".join(
        f"- {s['slot_key']} (importance {s['importance']}): {s['description']}"
        for s in state.get("open_slots_ranked", [])
    ) or "(none)"
    turns_text = "\n".join(
        f"Q: {t['question_text']}\nA: {t['answer_text']}"
        for t in reversed(state.get("recent_turns", []))
    ) or "(none - this is the opening turn)"
    answered_text = ", ".join(state.get("answered_topics", [])) or "(none)"
    vague_terms = state.get("vague_terms_found", [])

    opening_instruction = ""
    if is_opening:
        opening_instruction = f"""
THIS IS THE OPENING TURN. The user's raw idea is: "{state['raw_idea']}"
Per the restate-first design: restate their idea back, slightly sharper than
they put it, with exactly 2-3 non-obvious inferences flagged as inferences.
End with "What did I get wrong?" Use technique=ASSUMPTION_SURFACING,
input_mode=text. extracted_facts and new_slots should be empty - there's
nothing to extract yet, you're generating the first question, not processing
an answer.
"""

    # detect_vagueness is a cheap, deterministic, guaranteed-catch pre-filter
    # for a fixed list of common vague terms - not the ceiling on what counts
    # as vague. It will never cover every vague phrasing ("seamless",
    # "intuitive", "as needed", "some users", ...), so the model always gets
    # a standing instruction to apply its own judgment too, on top of
    # whatever the deterministic check already flagged.
    if vague_terms:
        vagueness_instruction = f"""
VAGUENESS DETECTED in the last answer (matched a known vague-term list): {", ".join(vague_terms)}
Per interrogation-engine.md §4, do not accept this at face value. Your
next_question MUST use technique=CONCRETIZATION and ask for a specific number
or concrete example in place of the vague term.
"""
    else:
        vagueness_instruction = """
No vague terms matched the fixed known-term list, but use your own judgment too: if the last
answer still leans on unmeasurable claims, marketing language, or hand-waving you weren't
explicitly given a number or concrete example for ("seamless", "intuitive", "as needed", "some
users", "cutting-edge", etc. - or anything else in that spirit), treat it the same as a detected
vague term - use technique=CONCRETIZATION and ask for the specific number or example instead.
"""

    open_gaps = state.get("open_gaps") or []
    rubric_rejection_instruction = ""
    if open_gaps:
        gaps_text = "\n".join(f"- {gap}" for gap in open_gaps)
        rubric_rejection_instruction = f"""
THE RUBRIC AGENT JUST REJECTED YOUR PROPOSAL TO CONCLUDE. You are NOT done -
do not set ready_to_conclude=true again. It found these specific gaps still
unresolved:
{gaps_text}
Your next_question MUST target one of these gaps directly (put its slot in
targets_slots). Pick whichever gap is most urgent to close.
"""

    return f"""You are conducting a discovery interview with someone who wants to build a project.

You have NO script. Generate every question from what this specific person has said.

WHAT YOU KNOW SO FAR:
{state.get('compacted_brief_summary') or '(nothing yet)'}

WHAT YOU STILL NEED (ranked, most important first):
{slots_text}

RECENT EXCHANGE (most recent first):
{turns_text}

ALREADY COVERED — never revisit:
{answered_text}
{opening_instruction}{vagueness_instruction}{rubric_rejection_instruction}
YOUR TURN:
1. Extract every fact from their last answer (skip this on the opening turn - see above). Map to existing slots by key.
2. Waive slots their answer made irrelevant. Be aggressive - dead questions kill trust.
3. Choose ONE next question. Pick the technique that fits this moment.
4. Match their vocabulary level exactly.

RULES:
- One question. Never stack.
- Reference what they actually said - this must feel like listening, not processing.
- Max 3 levels of "why" on any laddering thread; stop at a terminal value.
- No question without a target slot in targets_slots.
- If the interview has covered enough ground (most high-importance slots filled, no more open high-priority slots), set ready_to_conclude=true instead of asking another question.
"""


async def generate_turn(state: InterrogatorState) -> dict:
    model = init_chat_model(model_for("interrogator"))
    structured_model = model.with_structured_output(InterrogatorTurnResult)
    prompt = _build_prompt(state)
    result: InterrogatorTurnResult = await structured_model.ainvoke(prompt)
    return {"turn_result": result.model_dump()}


def build_graph():
    graph = StateGraph(InterrogatorState)
    graph.add_node("check_vagueness", check_vagueness)
    graph.add_node("generate_turn", generate_turn)
    graph.add_edge(START, "check_vagueness")
    graph.add_edge("check_vagueness", "generate_turn")
    graph.add_edge("generate_turn", END)
    return graph.compile()


# No checkpointer needed - each turn is a complete, independent invocation
# (Spring assembles fresh context and calls once per turn; nothing here
# persists or resumes across calls). See interrogation-engine.md §3. No live
# resource to manage (unlike app.py's Postgres connection), so a plain
# module-level compiled graph is enough - no async factory needed here.
graph = build_graph()
