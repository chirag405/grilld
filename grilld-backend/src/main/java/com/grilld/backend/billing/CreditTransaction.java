package com.grilld.backend.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of `credit_transactions` (V1__init_schema.sql) - the audit trail
 * product-and-architecture.md §9 requires: "never mutate balance without a
 * row here". Every CreditService write inserts exactly one of these
 * alongside its balance change, positive delta for a grant/refund, negative
 * for a deduction. `reason` doubles as an idempotency key for webhook-driven
 * grants (see CreditService.grantIdempotent) - it is never generated from
 * user input, always from a server-known event id.
 */
@Entity
@Table(name = "credit_transactions")
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int delta;

    @Column(nullable = false)
    private String reason;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CreditTransaction() {
    }

    public CreditTransaction(UUID userId, int delta, String reason, UUID runId) {
        this.userId = userId;
        this.delta = delta;
        this.reason = reason;
        this.runId = runId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getDelta() {
        return delta;
    }

    public String getReason() {
        return reason;
    }

    public UUID getRunId() {
        return runId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
