from grilld_ai_service.rubric.graph import _compute_verdict


def test_all_pass_accepts_with_no_gaps():
    dimensions = [
        {"dimension": "problem_clarity", "score": "PASS", "reasoning": "clear"},
        {"dimension": "scope_boundedness", "score": "PASS", "reasoning": "clear"},
    ]
    verdict, gaps = _compute_verdict(dimensions)
    assert verdict == "accept"
    assert gaps == []


def test_single_borderline_blocks_accept():
    dimensions = [
        {"dimension": "problem_clarity", "score": "PASS", "reasoning": "clear"},
        {"dimension": "technical_grounding", "score": "BORDERLINE", "reasoning": "no backend plan"},
    ]
    verdict, gaps = _compute_verdict(dimensions)
    assert verdict == "probe_further"
    assert gaps == ["technical_grounding: no backend plan"]


def test_any_fail_blocks_accept():
    dimensions = [
        {"dimension": "problem_clarity", "score": "FAIL", "reasoning": "no problem stated"},
    ]
    verdict, gaps = _compute_verdict(dimensions)
    assert verdict == "probe_further"
    assert gaps == ["problem_clarity: no problem stated"]
