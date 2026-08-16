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
 * One row of `agent_executions` - one specialist's contribution to a
 * {@link GenerationRun}. Since Phase 6, this is populated live: a row is
 * created on the specialist's STARTED event and updated on COMPLETED/FAILED,
 * as HttpAiServiceClient parses the real SSE stream from the Python service
 * (docs/decisions-and-technical-architecture.md §10.2, §11.3) - not written
 * all-at-once after a single blocking call. {@link RunReportService} reads
 * {@code narration}/{@code status}/{@code error} off these rows to assemble
 * the Run Report (§10.3) on every update.
 */
@Entity
@Table(name = "agent_executions")
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.RUNNING;

    private String error;

    @Column(name = "output_ref")
    private String outputRef;

    private String narration;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt = Instant.now();

    protected AgentExecution() {
    }

    public AgentExecution(UUID runId, String agentName) {
        this.runId = runId;
        this.agentName = agentName;
    }

    public void markCompleted(String outputRef, String narration) {
        this.status = Status.COMPLETED;
        this.outputRef = outputRef;
        this.narration = narration;
        this.heartbeatAt = Instant.now();
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.heartbeatAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getAgentName() {
        return agentName;
    }

    public Status getStatus() {
        return status;
    }

    public String getOutputRef() {
        return outputRef;
    }

    public String getNarration() {
        return narration;
    }

    public String getError() {
        return error;
    }

    public enum Status {
        RUNNING, COMPLETED, FAILED
    }
}
