package com.grilld.backend.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of `project_briefs` - the canonical, structured output of the
 * interrogation (docs/product-and-architecture.md §2's Layer 1). Every
 * finalized answer updates `briefJson`; every downstream agent (Phase 4
 * onward) reads only from this, never from raw turn history.
 *
 * `@Version` maps to the `version` column and gives us Hibernate's built-in
 * optimistic locking for free: if two requests try to update the same brief
 * at once, the second save fails with an exception instead of silently
 * overwriting the first - the concurrent-tab-edit guard from
 * docs/decisions-and-technical-architecture.md §10.1.
 */
@Entity
@Table(name = "project_briefs")
public class ProjectBrief {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brief_json", nullable = false)
    private String briefJson = "{}";

    @Column(name = "compacted_summary")
    private String compactedSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rubric_scores")
    private String rubricScores;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    protected ProjectBrief() {
    }

    public ProjectBrief(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public void updateBrief(String briefJson) {
        this.briefJson = briefJson;
    }

    public void updateCompactedSummary(String compactedSummary) {
        this.compactedSummary = compactedSummary;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getBriefJson() {
        return briefJson;
    }

    public String getCompactedSummary() {
        return compactedSummary;
    }

    public int getVersion() {
        return version;
    }
}
