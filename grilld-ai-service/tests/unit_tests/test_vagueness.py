from grilld_ai_service.interrogator.vagueness import detect_vagueness


def test_no_vague_terms_in_concrete_answer():
    assert detect_vagueness("about 200 people, mostly on weekends") == []


def test_detects_a_single_vague_term():
    assert detect_vagueness("it needs to scale well eventually") == ["eventually", "scale well"]


def test_detects_multiple_vague_terms():
    found = detect_vagueness("it should be fast and secure and handle a lot of traffic")
    assert "fast" in found
    assert "secure" in found
    assert "a lot" in found


def test_none_or_empty_answer_returns_empty():
    assert detect_vagueness(None) == []
    assert detect_vagueness("") == []


def test_case_insensitive_and_deduplicated():
    found = detect_vagueness("Fast, FAST, and fast again")
    assert found == ["fast"]


def test_does_not_match_substrings():
    # "fast" inside "fasten" should not trigger - word-boundary matching
    assert detect_vagueness("we need to fasten the bolts") == []
