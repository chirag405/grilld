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
 * One row of `packages` - the zipped blueprint (product-and-architecture.md
 * §5's package tree) assembled from a completed {@link GenerationRun}'s
 * {@link GeneratedDocument} rows. {@code storageUrl} is whatever
 * {@link PackageStorage} implementation is active - a local file:// path
 * today, real object storage once that infra decision is made (§10.7).
 */
@Entity
@Table(name = "packages")
public class GeneratedPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "storage_url")
    private String storageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected GeneratedPackage() {
    }

    public GeneratedPackage(UUID runId) {
        this.runId = runId;
    }

    public void markReady(String storageUrl) {
        this.status = Status.READY;
        this.storageUrl = storageUrl;
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getStorageUrl() {
        return storageUrl;
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        PENDING, READY, FAILED
    }
}
