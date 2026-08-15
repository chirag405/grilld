"""Structured output for the Scale Calibrator
(docs/product-and-architecture.md §4). The tier is a hard complexity ceiling
injected into every downstream specialist's prompt, and it's user-visible/
overridable in Spring - so it needs to be reliably parseable, not free text.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Tier = Literal["T0", "T1", "T2", "T3"]


class ScaleCalibrationResult(BaseModel):
    tier: Tier
    reasoning: str = Field(description="1-2 sentences, shown to the user alongside the tier")
    signals: list[str] = Field(
        default_factory=list,
        description="The specific brief facts that drove this tier (e.g. 'solo builder', 'pre-revenue')",
    )
