package com.grilld.backend.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One file a generation run actually produced - the real content, not just
 * the path {@link AgentExecution#getOutputRef()} already records. Persisted
 * once, when the run completes (see GenerationService.runGeneration), from
 * the full {@code GenerationResult} the AI service returns at the end of the
 * stream - this is what {@link PackagerService} zips up.
 */
@Entity
@Table(name = "generated_documents")
public class GeneratedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GeneratedDocument() {
    }

    public GeneratedDocument(UUID runId, String path, String content) {
        this.runId = runId;
        this.path = path;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }
}
