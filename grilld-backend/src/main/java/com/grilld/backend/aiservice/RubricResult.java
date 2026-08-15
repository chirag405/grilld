package com.grilld.backend.aiservice;

import java.util.List;

/**
 * Mirrors the Rubric Agent's structured-output contract
 * (docs/product-and-architecture.md §7, docs/decisions-and-technical-
 * architecture.md §11.4). {@code verdict}/{@code openGaps} are computed
 * deterministically on the Python side from the per-dimension scores, not
 * decided by the judge LLM itself - see grilld_ai_service/rubric/graph.py's
 * _compute_verdict for why that split matters.
 */
public record RubricResult(
        List<DimensionResult> dimensions,
        String verdict, // "accept" | "probe_further"
        List<String> openGaps
) {
    public record DimensionResult(String dimension, String score, String reasoning) {
    }

    public boolean accepted() {
        return "accept".equals(verdict);
    }
}
