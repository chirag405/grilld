"""Per-agent model selection (docs/product-and-architecture.md §3.2's roster
table already assigns a tier - Opus/Sonnet - to every agent; this had been
left unwired through Phase 5, with every agent flatly inheriting one
GRILLD_AI_MODEL instead).

Deliberately NOT a dynamic classifier (RouteLLM, vLLM Semantic Router, etc.)
- those solve a different problem: routing a large volume of unpredictable
incoming queries to the right model at runtime. Grilld's roster is a small,
fixed, known set of agent roles, each with an already-decided task profile -
a static table, decided once at design time, is the right-sized solution.
Researched before writing this (2026-08-16): confirmed this is genuinely a
different problem shape, not a shortcut around the "real" solution.

GRILLD_AI_MODEL, if set, is a blanket override for every tier - the existing
cheap/flat testing mode from Phases 3-5 keeps working unchanged. Unset it to
get the real tiered behavior below.
"""

from __future__ import annotations

import os

from typing import Literal

Tier = Literal["opus", "sonnet", "haiku"]

_TIER_DEFAULTS: dict[Tier, str] = {
    "opus": "anthropic:claude-opus-5",
    "sonnet": "anthropic:claude-sonnet-5",
    "haiku": "anthropic:claude-haiku-4-5-20251001",
}

# docs/product-and-architecture.md §3.2 - the roster table's "Model" column,
# copied exactly. Graph names match langgraph.json's registered graph ids;
# specialist names match each SubAgent dict's "name" field.
AGENT_TIER: dict[str, Tier] = {
    "orchestrator": "opus",
    "interrogator": "opus",
    "rubric": "sonnet",
    "scale_calibrator": "sonnet",
    "market_analyst": "sonnet",
    "competition_analyst": "sonnet",
    "strategy_agent": "sonnet",
    "tech_architect": "opus",
    "infra_agent": "opus",
    "diagram_agent": "sonnet",
    "roadmap_agent": "opus",
    "skills_curator": "sonnet",
    "agent_file_writer": "opus",
    "consistency_auditor": "opus",
}


def model_for(agent_name: str) -> str:
    """Resolves the model string for a given agent/graph name.

    GRILLD_AI_MODEL, if set, wins for every agent (flat/cheap testing mode -
    what every graph used through Phase 5). Otherwise resolves per-tier via
    GRILLD_MODEL_OPUS/GRILLD_MODEL_SONNET/GRILLD_MODEL_HAIKU (each with a
    sensible default), based on AGENT_TIER's assignment for this agent.
    """
    flat_override = os.getenv("GRILLD_AI_MODEL")
    if flat_override:
        return flat_override

    tier = AGENT_TIER.get(agent_name, "sonnet")
    env_var = f"GRILLD_MODEL_{tier.upper()}"
    return os.getenv(env_var, _TIER_DEFAULTS[tier])
