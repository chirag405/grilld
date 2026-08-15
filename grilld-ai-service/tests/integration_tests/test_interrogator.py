"""Real Claude calls against the Interrogator graph - proves the structured
output contract actually comes back correctly shaped, not just "some text was
returned." Needs ANTHROPIC_API_KEY.
"""

import os

import pytest

from grilld_ai_service.interrogator.graph import graph

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )


async def test_opening_turn_restates_the_idea():
    result = await graph.ainvoke({
        "session_id": "test-session-1",
        "raw_idea": "a tool for freelancers to track which invoices are unpaid",
        "compacted_brief_summary": None,
        "recent_turns": [],
        "open_slots_ranked": [
            {"slot_key": "problem_statement", "description": "Who this is for and what's broken", "importance": 5, "priority": 5.0},
            {"slot_key": "target_user", "description": "The specific person who has this problem", "importance": 5, "priority": 5.0},
        ],
        "answered_topics": [],
    })

    turn_result = result["turn_result"]
    assert turn_result["extracted_facts"] == [], "opening turn has nothing to extract yet"
    assert turn_result["ready_to_conclude"] is False
    question = turn_result["next_question"]
    assert question is not None
    assert question["technique"] == "ASSUMPTION_SURFACING"
    # Should reference the actual idea, not a generic question
    assert "invoice" in question["text"].lower() or "freelancer" in question["text"].lower()


async def test_vague_answer_forces_concretization():
    result = await graph.ainvoke({
        "session_id": "test-session-2",
        "raw_idea": "a tool for freelancers to track unpaid invoices",
        "compacted_brief_summary": "Building a tool for freelancers to track unpaid invoices.",
        "recent_turns": [
            {"turn_number": 1, "question_text": "How many users on day one?", "answer_text": "It needs to scale well and be fast"},
        ],
        "open_slots_ranked": [
            {"slot_key": "scale_expectation", "description": "Concrete user numbers", "importance": 5, "priority": 5.0},
        ],
        "answered_topics": ["problem_statement"],
    })

    turn_result = result["turn_result"]
    question = turn_result["next_question"]
    assert question is not None
    assert question["technique"] == "CONCRETIZATION", (
        "a vague answer ('scale well', 'fast') must force a concretization follow-up, "
        f"got technique={question['technique']!r}"
    )
