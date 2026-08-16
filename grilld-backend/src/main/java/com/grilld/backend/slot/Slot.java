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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row of `slots` - one atomic piece of knowledge the interrogation needs
 * (or has already established). See interrogation-engine.md §2 for the full
 * design: origin (SEED/DERIVED/PROBE), status lifecycle, and how `unlocks`
 * drives spawning new slots when this one gets filled.
 */
@Entity
@Table(name = "slots")
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "slot_key", nullable = false)
    private String slotKey;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private int importance;

    private String value;

    private Double confidence;

    @Column(name = "parent_slot_key")
    private String parentSlotKey;

    @Column(nullable = false)
    private int depth = 0;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> unlocks = List.of();

    @Column(name = "evidence_ref")
    private String evidenceRef;

    @Column(name = "created_at_turn", nullable = false)
    private int createdAtTurn;

    @Column(name = "filled_at_turn")
    private Integer filledAtTurn;

    protected Slot() {
    }

    public Slot(UUID sessionId, String slotKey, String description, Origin origin, int importance, int createdAtTurn) {
        this.sessionId = sessionId;
        this.slotKey = slotKey;
        this.description = description;
        this.origin = origin;
        this.importance = importance;
        this.createdAtTurn = createdAtTurn;
    }

    /** A DERIVED/PROBE slot spawned from an existing one - see {@link #addUnlockedSlot} on the parent side of this link. */
    public Slot(UUID sessionId, String slotKey, String description, Origin origin, int importance, int createdAtTurn,
                String parentSlotKey) {
        this(sessionId, slotKey, description, origin, importance, createdAtTurn);
        this.parentSlotKey = parentSlotKey;
    }

    public void fill(String value, double confidence, String evidenceRef, int atTurn) {
        this.value = value;
        this.confidence = confidence;
        this.evidenceRef = evidenceRef;
        this.filledAtTurn = atTurn;
        this.status = Status.FILLED;
    }

    /** Records that filling/changing this slot is what caused {@code childSlotKey} to be spawned - the RevisionClassifier's blast-radius traversal (docs/decisions-and-technical-architecture.md §7). */
    public void addUnlockedSlot(String childSlotKey) {
        List<String> updated = new ArrayList<>(this.unlocks);
        updated.add(childSlotKey);
        this.unlocks = updated;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public String getDescription() {
        return description;
    }

    public Origin getOrigin() {
        return origin;
    }

    public Status getStatus() {
        return status;
    }

    public int getImportance() {
        return importance;
    }

    public String getValue() {
        return value;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getParentSlotKey() {
        return parentSlotKey;
    }

    public List<String> getUnlocks() {
        return unlocks;
    }

    public enum Origin {
        SEED, DERIVED, PROBE
    }

    public enum Status {
        OPEN, FILLED, ASSUMED, WAIVED, BLOCKED
    }
}
