"""Deterministic pre-check, run before the LLM ever sees the answer -
docs/interrogation-engine.md §4: "the single highest-leverage rule in the
whole system." Any unquantified magnitude ("scalable", "fast", "a lot", ...)
in the most recent answer forces the next question toward concretization
instead of letting the LLM accept it at face value. This is exactly the list
from interrogation-engine.md §4 - extending it is a deliberate content
decision, not a code change to make lightly, since it directly shapes
interview behavior.
"""

from __future__ import annotations

import re

VAGUE_TERMS = [
    "scalable", "scale well", "fast", "secure", "a lot", "eventually",
    "real-time", "real time", "high-traffic", "high traffic",
    "enterprise-grade", "enterprise grade", "robust", "flexible",
    "user-friendly", "user friendly", "efficient", "reliable",
]

_PATTERN = re.compile(
    r"\b(" + "|".join(re.escape(term) for term in VAGUE_TERMS) + r")\b",
    re.IGNORECASE,
)


def detect_vagueness(answer_text: str | None) -> list[str]:
    """Returns the vague terms found in the answer, empty if none. Matching on
    the raw answer only - never strip/normalize first, since the exact phrase
    is what a concretization follow-up should quote back."""
    if not answer_text:
        return []
    return sorted(set(match.group(0).lower() for match in _PATTERN.finditer(answer_text)))
