"""The Interrogator - a LangGraph StateGraph, not a Deep Agent, per
docs/decisions-and-technical-architecture.md §11.1: its control flow
(vagueness-gated prompting) needs precise custom edges that middleware doesn't
give you. Exposed as its own top-level graph (langgraph.json's "interrogator"
entry) rather than nested under the Orchestrator - interrogation and
generation are different lifecycle phases called by Spring differently (one
call per interview turn vs. one call per whole generation run), not a single
workflow.

One semantic generate_turn node performs the actual structured LLM call. No
contradiction-detection node here - that needs the full stored brief, which only Java has; see
SessionService.applyExtraction() in grilld-backend.
"""

from __future__ import annotations

from langchain.chat_models import init_chat_model
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

from grilld_ai_service.interrogator.schemas import InterrogatorTurnResult
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

    # Output - the JSON Spring's HttpAiServiceClient reads directly.
    turn_result: dict


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

    opening_instruction = ""
    if is_opening:
        opening_instruction = f"""
THIS IS THE OPENING TURN. The user's raw idea is: "{state['raw_idea']}"
Per the restate-first design: restate their idea back, slightly sharper than
they put it, with exactly 2-3 non-obvious inferences flagged as inferences.
End with "What did I get wrong?" Use technique=ASSUMPTION_SURFACING,
input_mode=text. extracted_facts and new_slots should be empty - there's
nothing to extract yet, you're generating the first question, not processing
an answer. You MUST set ready_to_conclude=false and provide next_question.
For this system-generated opening only, set intent=ANSWER and use
assistant_message for the brief restatement that introduces next_question.
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

    return f"""You are a product copilot helping someone turn a rough idea into an actionable blueprint.

You have NO script. Generate every question from what this specific person has said.

WHAT YOU KNOW SO FAR:
{state.get('compacted_brief_summary') or '(nothing yet)'}

WHAT YOU STILL NEED (ranked, most important first):
{slots_text}

RECENT EXCHANGE (most recent first):
{turns_text}

ALREADY COVERED — never revisit:
{answered_text}
{opening_instruction}{rubric_rejection_instruction}
YOUR TURN:
1. Understand the semantic intent of the user's whole message in context and set intent to exactly one of:
   ANSWER, QUESTION, CORRECTION, SKIP, FINISH, or UNRELATED. Never classify by keyword matching.
2. Extract every fact from their last answer (skip this on the opening turn). Map to existing slots by key.
3. Waive slots their answer made irrelevant. Be aggressive - dead questions kill trust.
4. Choose ONE next question only when its answer would materially change the product, architecture,
   or delivery plan. Otherwise fill low-risk gaps with explicit ASSUMED facts and conclude.
5. Return reasoning_summary, reasoning_decisions, and reasoning_assumptions as a concise, user-safe
   audit trail. Never expose hidden chain-of-thought, token-by-token deliberation, or private scratch work.

RULES:
- One question. Never stack.
- Help more than you interrogate. Offer a concrete recommendation when the user is unsure, then
  either ask for a lightweight correction or proceed with it as an assumption.
- Never ask the user to define analytics, failure criteria, exact scale, or workflow details that
  the blueprint can responsibly propose for them unless the choice has major consequences.
- Never revisit a topic already present in ALREADY COVERED or the brief, even to seek more precision.
- Treat sarcasm, frustration, terse answers, and spelling mistakes charitably; do not literalize jokes.
- If the user says to decide for them, proceed, finish, stop asking, or otherwise delegates decisions,
  infer sensible defaults, set ready_to_conclude=true, and do not ask another question.
- For QUESTION, answer it directly in assistant_message. Unless the answer makes the pending discovery
  question irrelevant, you MUST copy that pending discovery question into next_question so the UI can
  resume it; do not hide the resumed question inside assistant_message.
- For CORRECTION, acknowledge and apply the correction before choosing what comes next.
- For FINISH, set ready_to_conclude=true. For SKIP, waive the targeted slot without pressure.
- For UNRELATED, answer briefly when safe and simple; otherwise explain the product-discovery boundary,
  then gently resume. Never pretend to have tools, live data, or expertise you do not have.
- assistant_message is always required. It MUST contain the direct answer for QUESTION and UNRELATED,
  acknowledge the change for CORRECTION, and briefly acknowledge ANSWER, SKIP, or FINISH.
- Aim for 3-6 useful questions total. More than 8 is a failure unless resolving a direct contradiction.
- Reference what they actually said - this must feel like listening, not processing.
- Max 3 levels of "why" on any laddering thread; stop at a terminal value.
- No question without a target slot in targets_slots.
- Unless ready_to_conclude=true, next_question is REQUIRED. Never return both
  ready_to_conclude=false and next_question=null.
- If the interview has covered enough ground (most high-importance slots filled, no more open high-priority slots), set ready_to_conclude=true instead of asking another question.
- If their last answer is a plain skip/decline ("skip", "I don't know", "not sure", "I'd rather not say", "N/A", or similar - use your judgment, not a fixed word list), do NOT push back or re-ask. Waive the targeted slot(s), then conclude if the remaining gaps can safely become assumptions.
- If you set input_mode=chips, chip_options must be 2-6 short, concrete, mutually exclusive answers to YOUR question specifically - grounded in what they've already told you, never generic filler like "Option A". If you can't write real options for this exact question, use input_mode=text instead - an empty/fake chip list is worse than no chips.

WRITING STYLE - why_asking:
This is shown directly to the person you're interviewing, as a one-line answer
to "why are you asking me this?" - not your internal reasoning about
technique or strategy. Write it the way you'd actually say it out loud to
them: plain, warm, one sentence, no jargon like "surfaces assumptions" or
"technique" or "targets_slots". Say what it gets them, not how it works.
Bad: "Opening turn: restate the raw idea sharper and surface the assumptions
hiding inside 'tool for nerds' so the builder corrects the framing before we
spend turns on scale, constraints, or timeline."
Good: "So I don't waste your time guessing wrong about what you actually need."

WRITING STYLE - the question itself:
Plain language over jargon, but don't dumb it down - this person can handle
technical precision, they just don't want to wade through unnecessary words
to get it. Say the smart thing in the fewest, clearest words.
"""


async def generate_turn(state: InterrogatorState) -> dict:
    model = init_chat_model(model_for("interrogator"))
    structured_model = model.with_structured_output(InterrogatorTurnResult)
    prompt = _build_prompt(state)
    result: InterrogatorTurnResult = await structured_model.ainvoke(prompt)
    return {"turn_result": result.model_dump()}


def build_graph():
    graph = StateGraph(InterrogatorState)
    graph.add_node("generate_turn", generate_turn)
    graph.add_edge(START, "generate_turn")
    graph.add_edge("generate_turn", END)
    return graph.compile()


# No checkpointer needed - each turn is a complete, independent invocation
# (Spring assembles fresh context and calls once per turn; nothing here
# persists or resumes across calls). See interrogation-engine.md §3. No live
# resource to manage (unlike app.py's Postgres connection), so a plain
# module-level compiled graph is enough - no async factory needed here.
graph = build_graph()
