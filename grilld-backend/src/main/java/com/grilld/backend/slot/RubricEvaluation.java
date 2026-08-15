package com.grilld.backend.slot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of `rubric_evaluations` - one Rubric Agent pass over the brief.
 * `scores` holds the per-dimension FAIL/BORDERLINE/PASS categorical result
 * (docs/decisions-and-technical-architecture.md §11.4 - moved off a 1-5 scale
 * since fine-grained LLM-as-judge scoring is unreliable). Written by the real
 * Rubric Agent starting Phase 4; not yet populated by any code today.
 */
@Entity
@Table(name = "rubric_evaluations")
public class RubricEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "at_turn", nullable = false)
    private int atTurn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String scores; // {"problem_clarity": "PASS", "scope_boundedness": "BORDERLINE", ...}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "open_gaps", nullable = false)
    private String openGaps = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RubricEvaluation() {
    }

    public RubricEvaluation(UUID sessionId, int atTurn, String scoresJson, String openGapsJson, Verdict verdict) {
        this.sessionId = sessionId;
        this.atTurn = atTurn;
        this.scores = scoresJson;
        this.openGaps = openGapsJson;
        this.verdict = verdict;
    }

    public enum Verdict {
        accept, probe_further
    }
}
