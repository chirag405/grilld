import importlib

import grilld_ai_service.model_tiers as model_tiers


def _reload():
    return importlib.reload(model_tiers)


def test_flat_override_wins_for_every_agent(monkeypatch):
    monkeypatch.setenv("GRILLD_AI_MODEL", "anthropic:claude-haiku-4-5-20251001")
    monkeypatch.delenv("GRILLD_MODEL_OPUS", raising=False)
    mt = _reload()
    assert mt.model_for("tech_architect") == "anthropic:claude-haiku-4-5-20251001"
    assert mt.model_for("diagram_agent") == "anthropic:claude-haiku-4-5-20251001"


def test_tiered_resolution_when_flat_override_unset(monkeypatch):
    monkeypatch.delenv("GRILLD_AI_MODEL", raising=False)
    monkeypatch.delenv("GRILLD_MODEL_OPUS", raising=False)
    monkeypatch.delenv("GRILLD_MODEL_SONNET", raising=False)
    mt = _reload()
    assert mt.model_for("tech_architect") == "anthropic:claude-opus-5"  # opus tier
    assert mt.model_for("diagram_agent") == "anthropic:claude-sonnet-5"  # sonnet tier


def test_per_tier_env_override(monkeypatch):
    monkeypatch.delenv("GRILLD_AI_MODEL", raising=False)
    monkeypatch.setenv("GRILLD_MODEL_OPUS", "anthropic:custom-opus-model")
    mt = _reload()
    assert mt.model_for("consistency_auditor") == "anthropic:custom-opus-model"


def test_unknown_agent_defaults_to_sonnet(monkeypatch):
    monkeypatch.delenv("GRILLD_AI_MODEL", raising=False)
    monkeypatch.delenv("GRILLD_MODEL_SONNET", raising=False)
    mt = _reload()
    assert mt.model_for("some_future_agent") == "anthropic:claude-sonnet-5"


def test_every_specialist_and_graph_from_the_roster_has_an_assigned_tier():
    expected = {
        "orchestrator", "interrogator", "rubric", "scale_calibrator",
        "market_analyst", "competition_analyst", "strategy_agent",
        "tech_architect", "infra_agent", "diagram_agent", "roadmap_agent",
        "skills_curator", "agent_file_writer", "consistency_auditor",
    }
    assert expected <= model_tiers.AGENT_TIER.keys()
