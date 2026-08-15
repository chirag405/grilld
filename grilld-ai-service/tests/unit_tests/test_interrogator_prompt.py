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


def test_detected_vague_term_names_it_explicitly():
    prompt = _build_prompt(_base_state(vague_terms_found=["fast"]))
    assert "VAGUENESS DETECTED" in prompt
    assert "fast" in prompt
    assert "technique=CONCRETIZATION" in prompt


def test_no_detected_term_still_instructs_model_to_use_own_judgment():
    # This is the actual fix: even when the deterministic regex list finds
    # nothing, the model must still be told to apply its own judgment for
    # vague language the fixed list doesn't cover - not silently pass it through.
    prompt = _build_prompt(_base_state(vague_terms_found=[]))
    assert "VAGUENESS DETECTED" not in prompt
    assert "use your own judgment" in prompt
    assert "technique=CONCRETIZATION" in prompt
