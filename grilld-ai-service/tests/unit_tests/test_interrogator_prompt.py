from grilld_ai_service.interrogator.graph import _build_prompt


def _base_state(**overrides):
    state = {
        "raw_idea": "a scheduling tool",
        "compacted_brief_summary": "some summary",
        "recent_turns": [{"turn_number": 1, "question_text": "How many users?", "answer_text": "it just needs to be fast"}],
        "open_slots_ranked": [],
        "answered_topics": [],
    }
    state.update(overrides)
    return state


def test_prompt_uses_semantic_intent_not_keyword_matching():
    prompt = _build_prompt(_base_state())
    assert "semantic intent" in prompt
    assert "Never classify by keyword matching" in prompt


def test_prompt_honors_delegation_and_finish_requests():
    prompt = _build_prompt(_base_state())
    assert "decide for them" in prompt
    assert "stop asking" in prompt
    assert "set ready_to_conclude=true" in prompt
    assert "Aim for 3-6 useful questions" in prompt
    assert "Never expose hidden chain-of-thought" in prompt
