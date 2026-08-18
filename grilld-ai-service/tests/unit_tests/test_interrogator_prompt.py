from grilld_ai_service.interrogator.graph import _build_prompt


def _base_state(**overrides):
    state = {
        "raw_idea": "a scheduling tool",
        "compacted_brief_summary": "some summary",
        "recent_turns": [{"turn_number": 1, "question_text": "How many users?", "answer_text": "it just needs to be fast"}],
        "open_slots_ranked": [],
        "answered_topics": [],
        "vague_terms_found": ["fast"],
    }
    state.update(overrides)
    return state


def test_detected_vague_term_does_not_force_concretization():
    prompt = _build_prompt(_base_state(vague_terms_found=["fast"]))
    assert "VAGUENESS DETECTED" in prompt
    assert "fast" in prompt
    assert "materially change the blueprint" in prompt


def test_no_detected_term_does_not_manufacture_more_questions():
    # This is the actual fix: even when the deterministic regex list finds
    # nothing, the model must still be told to apply its own judgment for
    # vague language the fixed list doesn't cover - not silently pass it through.
    prompt = _build_prompt(_base_state(vague_terms_found=[]))
    assert "VAGUENESS DETECTED" not in prompt
    assert "Do not manufacture a need" in prompt


def test_prompt_honors_delegation_and_finish_requests():
    prompt = _build_prompt(_base_state())
    assert "decide for them" in prompt
    assert "stop asking" in prompt
    assert "set ready_to_conclude=true" in prompt
    assert "Aim for 3-6 useful questions" in prompt
