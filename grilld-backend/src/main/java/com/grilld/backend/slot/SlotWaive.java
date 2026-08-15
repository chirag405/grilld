package com.grilld.backend.slot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One row of `slot_waives` - a record of a slot being skipped, and why. Never
 * deleted, even when reversed (was_reversed=true) - deletion is exactly what
 * would make over-waiving unrecoverable. See
 * docs/decisions-and-technical-architecture.md §6.
 */
@Entity
@Table(name = "slot_waives")
public class SlotWaive {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "slot_key", nullable = false)
    private String slotKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier;

    @Column(name = "justifying_quote", nullable = false)
    private String justifyingQuote;

    @Column(name = "at_turn", nullable = false)
    private int atTurn;

    @Column(name = "was_reversed", nullable = false)
    private boolean wasReversed = false;

    @Column(name = "reversed_at_turn")
    private Integer reversedAtTurn;

    @Column(name = "was_manually_restored", nullable = false)
    private boolean wasManuallyRestored = false;

    protected SlotWaive() {
    }

    public SlotWaive(UUID sessionId, String slotKey, Tier tier, String justifyingQuote, int atTurn) {
        this.sessionId = sessionId;
        this.slotKey = slotKey;
        this.tier = tier;
        this.justifyingQuote = justifyingQuote;
        this.atTurn = atTurn;
    }

    public enum Tier {
        HARD_WAIVE, SOFT_DEPRIORITIZE
    }
}
