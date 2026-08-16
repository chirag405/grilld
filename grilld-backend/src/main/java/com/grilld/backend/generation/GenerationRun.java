package com.grilld.backend.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of `generation_runs` - one attempt at turning a finalized brief
 * into the full blueprint package (docs/product-and-architecture.md §5),
 * via the specialist roster (Phase 5). `creditsCharged` is a placeholder
 * (always 0) until billing exists (Phase 7) - not real charging yet.
 */
@Entity
@Table(name = "generation_runs")
public class GenerationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "credits_charged", nullable = false)
    private int creditsCharged = 0;

    @Column(name = "run_report_md")
    private String runReportMd;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected GenerationRun() {
    }

    public GenerationRun(UUID briefId) {
        this.briefId = briefId;
    }

    /**
     * Rewrites the Run Report in place (§10.3) - called on every
     * agent_executions change via RunReportService, not just at the end.
     * Does not touch status/completedAt.
     */
    public void updateRunReport(String runReportMd) {
        this.runReportMd = runReportMd;
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorSummary) {
        this.status = Status.FAILED;
        this.failureReason = errorSummary;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBriefId() {
        return briefId;
    }

    public Status getStatus() {
        return status;
    }

    public String getRunReportMd() {
        return runReportMd;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public enum Status {
        IN_PROGRESS, COMPLETED, FAILED
    }
}
