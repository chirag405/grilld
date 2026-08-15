"""Real Claude calls against the Scale Calibrator - proves it actually
discriminates tiers from brief signals, not just returns a fixed answer.
Needs ANTHROPIC_API_KEY.
"""

import os

import pytest

from grilld_ai_service.scale_calibrator.graph import graph

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )


async def test_weekend_project_gets_t0():
    result = await graph.ainvoke({
        "session_id": "test-calibrator-t0",
        "brief_json": (
            '{"problem_statement": "a CLI tool to track my own reading list", '
            '"team_shape": "just me, a weekend project", '
            '"timeline": "want it done this weekend, no real deadline", '
            '"monetization_intent": "none, purely for my own learning and use", '
            '"scale_expectation": "just me using it"}'
        ),
    })
    calibration = result["calibration_result"]
    assert calibration["tier"] == "T0", f"expected T0 for a solo weekend learning project, got {calibration}"
    assert len(calibration["signals"]) > 0


async def test_funded_team_project_gets_t2_or_t3():
    result = await graph.ainvoke({
        "session_id": "test-calibrator-t2",
        "brief_json": (
            '{"problem_statement": "a B2B SaaS for restaurant inventory management", '
            '"team_shape": "a funded team of 4 engineers plus a designer", '
            '"timeline": "6 month runway to first paying customers, already have seed funding", '
            '"monetization_intent": "subscription revenue from day one, already have 3 pilot customers", '
            '"scale_expectation": "targeting 10,000 restaurants within the first year"}'
        ),
    })
    calibration = result["calibration_result"]
    assert calibration["tier"] in ("T2", "T3"), (
        f"expected T2 or T3 for a funded 4-person team with revenue intent, got {calibration}"
    )
