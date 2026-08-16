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

    // Summed from the specialist's own subgraph "model" node usage_metadata
    // (HttpAiServiceClient.accumulateSubgraphTokens) - feeds the cost circuit
    // breaker (§10.6). Null until COMPLETED; null forever for a run predating
    // this field or one where the AI service never reports usage.
    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

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

    /** Resets an existing row back to RUNNING - the resumeStaleRun() case where Python re-reports an agent that already had a row from before a Spring restart. */
    public void markStarted() {
        this.status = Status.RUNNING;
        this.error = null;
        this.heartbeatAt = Instant.now();
    }

    public void markCompleted(String outputRef, String narration, Integer inputTokens, Integer outputTokens) {
        this.status = Status.COMPLETED;
        this.outputRef = outputRef;
        this.narration = narration;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
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

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public enum Status {
        RUNNING, COMPLETED, FAILED
    }
}
