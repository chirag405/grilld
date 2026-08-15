package com.grilld.backend.session;

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
 * One row of `discovery_sessions` - one in-progress (or finished) interrogation.
 * Everything else in this package (Turn, ExpertiseProfile) and in `slot`/`brief`
 * hangs off a session_id. See docs/interrogation-engine.md §2 for the design
 * this maps to.
 */
@Entity
@Table(name = "discovery_sessions")
public class DiscoverySession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "raw_idea", nullable = false)
    private String rawIdea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "scale_tier")
    private String scaleTier; // T0-T3; set by the Scale Calibrator agent, Phase 5

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DiscoverySession() {
    }

    public DiscoverySession(UUID userId, String rawIdea) {
        this.userId = userId;
        this.rawIdea = rawIdea;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRawIdea() {
        return rawIdea;
    }

    public Status getStatus() {
        return status;
    }

    public String getScaleTier() {
        return scaleTier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public enum Status {
        ACTIVE, READY_FOR_GENERATION, COMPLETED, ABANDONED
    }
}
