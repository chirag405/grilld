"""Structured output for the Rubric Agent - the quality gate from
docs/product-and-architecture.md §7, scored per
docs/decisions-and-technical-architecture.md §11.4: categorical FAIL/
BORDERLINE/PASS per dimension, not a noisy 1-5 scale.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

DimensionScore = Literal["FAIL", "BORDERLINE", "PASS"]

DIMENSIONS = (
    "problem_clarity",
    "scope_boundedness",
    "scale_concreteness",
    "technical_grounding",
    "success_definition",
    "risk_awareness",
)


class DimensionResult(BaseModel):
    dimension: str
    score: DimensionScore
    reasoning: str


class RubricResult(BaseModel):
    dimensions: list[DimensionResult]
    verdict: Literal["accept", "probe_further"]
    open_gaps: list[str] = Field(default_factory=list)
