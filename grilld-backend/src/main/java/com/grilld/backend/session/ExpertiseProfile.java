package com.grilld.backend.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * One row of `expertise_profiles`, keyed directly by session_id (one profile
 * per session, not a separate generated id) - see interrogation-engine.md §5.
 * Not yet populated by real logic; that's the ExpertiseProfiler piece of the
 * Python Interrogator, Phase 4. The table/entity exists now so Phase 4 has
 * somewhere to write to without a schema change.
 */
@Entity
@Table(name = "expertise_profiles")
public class ExpertiseProfile {

    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(nullable = false)
    private int level = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String domains = "{}"; // {"backend": 4, "devops": 2, ...}

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "named_technologies")
    private List<String> namedTechnologies = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "known_gaps")
    private List<String> knownGaps = List.of();

    @Column(name = "updated_at_turn", nullable = false)
    private int updatedAtTurn;

    protected ExpertiseProfile() {
    }

    public ExpertiseProfile(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getLevel() {
        return level;
    }
}
