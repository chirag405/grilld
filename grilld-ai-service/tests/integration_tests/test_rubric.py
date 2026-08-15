"""Real Claude calls against the Rubric Agent - proves the categorical
FAIL/BORDERLINE/PASS judge actually discriminates between a thin brief and a
well-covered one, not just "some verdict came back". Needs ANTHROPIC_API_KEY.
"""

import os

import pytest

from grilld_ai_service.rubric.graph import graph

pytestmark = pytest.mark.anyio

if not os.getenv("ANTHROPIC_API_KEY"):
    pytest.skip(
        "Set ANTHROPIC_API_KEY to run integration tests.", allow_module_level=True
    )


async def test_thin_brief_is_rejected():
    result = await graph.ainvoke({
        "session_id": "test-rubric-thin",
        "brief_json": '{"problem_statement": "freelancers track unpaid invoices"}',
        "slots": [
            {"slot_key": "problem_statement", "status": "FILLED", "value": "freelancers track unpaid invoices", "importance": 5},
            {"slot_key": "target_user", "status": "OPEN", "value": None, "importance": 5},
            {"slot_key": "scale_expectation", "status": "OPEN", "value": None, "importance": 4},
            {"slot_key": "builder_skillset", "status": "OPEN", "value": None, "importance": 4},
            {"slot_key": "success_definition", "status": "OPEN", "value": None, "importance": 4},
        ],
    })

    rubric_result = result["rubric_result"]
    assert rubric_result["verdict"] == "probe_further", (
        "a brief with almost everything still OPEN must not be accepted, "
        f"got: {rubric_result}"
    )
    assert len(rubric_result["open_gaps"]) > 0
    scores = {d["dimension"]: d["score"] for d in rubric_result["dimensions"]}
    assert len(scores) == 6, f"expected all six dimensions scored, got {scores.keys()}"


async def test_well_covered_brief_is_accepted():
    result = await graph.ainvoke({
        "session_id": "test-rubric-solid",
        "brief_json": (
            '{"problem_statement": "Freelancers lose track of which invoices are unpaid and '
            'end up chasing the same client twice, or forgetting to follow up entirely.", '
            '"target_user": "Solo freelancers with 5-20 active clients, mostly designers and '
            'consultants who invoice manually today.", '
            '"scale_expectation": "About 200 freelancers in the first 3 months, growing to 2000 '
            'by end of year one.", '
            '"builder_skillset": "Solo developer, comfortable with React and Node, no prior '
            'fintech experience.", '
            '"hard_constraints": "Must integrate with Stripe for payment status; no budget for a '
            'dedicated backend team.", '
            '"success_definition": "A freelancer opens the app and immediately sees which '
            'invoices are overdue, with zero manual reconciliation.", '
            '"risk_1": "Stripe API rate limits could delay status sync for high-volume users.", '
            '"risk_2": "Freelancers may not trust a new tool with their financial data early on.", '
            '"backend_plan": "Managed Postgres (Supabase) with Stripe webhooks for status sync; no '
            'custom backend infra since this is a solo build. Checked Stripe docs: webhook '
            'delivery and API rate limits comfortably support the 2000-user target without a '
            'queueing layer.", '
            '"scope_exclusions": "Explicitly NOT building in v1: sending/generating invoices '
            '(read-only status tracking only), multi-currency support, team/multi-user accounts, '
            'and automated payment reminders - all deferred post-launch."}'
        ),
        "slots": [
            {"slot_key": "problem_statement", "status": "FILLED", "value": "see brief", "importance": 5},
            {"slot_key": "target_user", "status": "FILLED", "value": "see brief", "importance": 5},
            {"slot_key": "scale_expectation", "status": "FILLED", "value": "200 -> 2000", "importance": 4},
            {"slot_key": "builder_skillset", "status": "FILLED", "value": "see brief", "importance": 4},
            {"slot_key": "hard_constraints", "status": "FILLED", "value": "see brief", "importance": 4},
            {"slot_key": "backend_plan", "status": "FILLED", "value": "see brief", "importance": 3},
            {"slot_key": "success_definition", "status": "FILLED", "value": "see brief", "importance": 4},
            {"slot_key": "risk_1", "status": "FILLED", "value": "see brief", "importance": 3},
            {"slot_key": "risk_2", "status": "FILLED", "value": "see brief", "importance": 3},
            {"slot_key": "invoice_sending", "status": "WAIVED", "value": "explicitly out of scope for v1", "importance": 2},
            {"slot_key": "multi_currency", "status": "WAIVED", "value": "explicitly out of scope for v1", "importance": 2},
            {"slot_key": "team_accounts", "status": "WAIVED", "value": "explicitly out of scope for v1", "importance": 2},
        ],
    })

    rubric_result = result["rubric_result"]
    assert rubric_result["verdict"] == "accept", (
        f"a well-covered brief with all six dimensions supported should be accepted, "
        f"got: {rubric_result}"
    )
