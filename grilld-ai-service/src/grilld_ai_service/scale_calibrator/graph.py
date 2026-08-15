"""The Scale Calibrator - a single stateless LLM call (like the Rubric
Agent), not a Deep Agents subagent. Unlike the rest of the specialist roster
(Phase 5, docs/product-and-architecture.md §3.2), its output is structured
data Spring stores and shows the user for override *before* the full
generation run starts (§4: "the tier is user-visible and overridable") - so
it needs its own quick, directly-callable graph, the same shape as the
Rubric Agent, not a subagent buried inside one big Orchestrator run.
"""

from __future__ import annotations

from langchain.chat_models import init_chat_model
from langgraph.graph import END, START, StateGraph
from typing_extensions import TypedDict

from grilld_ai_service.model_tiers import model_for
from grilld_ai_service.scale_calibrator.prompt import SCALE_CALIBRATION_PROMPT
from grilld_ai_service.scale_calibrator.schemas import ScaleCalibrationResult


class ScaleCalibratorState(TypedDict, total=False):
    # Input
    session_id: str
    brief_json: str

    # Output - the JSON Spring's HttpAiServiceClient reads directly.
    calibration_result: dict


async def calibrate(state: ScaleCalibratorState) -> dict:
    model = init_chat_model(model_for("scale_calibrator"))
    structured_model = model.with_structured_output(ScaleCalibrationResult)
    prompt = SCALE_CALIBRATION_PROMPT.format(brief_json=state.get("brief_json") or "{}")
    result: ScaleCalibrationResult = await structured_model.ainvoke(prompt)
    return {"calibration_result": result.model_dump()}


def build_graph():
    graph = StateGraph(ScaleCalibratorState)
    graph.add_node("calibrate", calibrate)
    graph.add_edge(START, "calibrate")
    graph.add_edge("calibrate", END)
    return graph.compile()


# Stateless, like the Interrogator/Rubric graphs - one call, no thread needed.
graph = build_graph()
