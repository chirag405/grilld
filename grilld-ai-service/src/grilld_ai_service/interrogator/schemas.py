"""Structured-output contract for the Interrogator's per-turn response.

Mirrors grilld-backend's InterrogatorTurnResult.java field-for-field (snake_case
here, camelCase there - HttpAiServiceClient reads these keys directly from the
raw JSON, no automatic name-mapping). Changing a field here means changing it
there too - see docs/interrogation-engine.md §3 for the contract this
implements.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Technique = Literal[
    "FREE_ELICITATION", "LADDERING", "CONCRETIZATION", "CONTRAST_TRIADIC",
    "ASSUMPTION_SURFACING", "CONTRADICTION_RESOLUTION", "SCENARIO_PROJECTION",
    "EXPERTISE_PROBE",
]

InputMode = Literal["voice_primary", "chips", "number", "text"]

SlotOrigin = Literal["SEED", "DERIVED", "PROBE"]


class ExtractedFact(BaseModel):
    slot_key: str
    value: str
    confidence: float = Field(ge=0, le=1)


class NewSlot(BaseModel):
    key: str
    description: str
    origin: SlotOrigin
    importance: int = Field(ge=1, le=5)
    parent_slot_key: str | None = None


class WaivedSlot(BaseModel):
    key: str
    reason: str


class NextQuestion(BaseModel):
    text: str
    targets_slots: list[str]
    technique: Technique
    input_mode: InputMode
    why_asking: str


class InterrogatorTurnResult(BaseModel):
    """What generate_turn must produce. ready_to_conclude=True means
    next_question is not applicable - matches interrogation-engine.md §3's
    "or: {ready_to_conclude: true}" branch."""

    extracted_facts: list[ExtractedFact] = Field(default_factory=list)
    new_slots: list[NewSlot] = Field(default_factory=list)
    waived_slots: list[WaivedSlot] = Field(default_factory=list)
    next_question: NextQuestion | None = None
    ready_to_conclude: bool = False
