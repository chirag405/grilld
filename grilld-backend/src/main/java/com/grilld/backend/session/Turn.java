package com.grilld.backend.session;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of `turns` - one question/answer exchange in the interrogation.
 * `factsExtracted` is stored as raw JSON text (not modeled as Java classes):
 * its shape is defined by the Interrogator's structured-output contract
 * (interrogation-engine.md §3), which lives on the Python side and can evolve
 * without a Java-side migration every time a field is added.
 */
@Entity
@Table(name = "turns")
public class Turn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "question_text")
    private String questionText;

    @Enumerated(EnumType.STRING)
    private Technique technique;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "targets_slots")
    private List<String> targetsSlots = List.of();

    @Column(name = "answer_text")
    private String answerText;

    @Column(name = "input_mode")
    private String inputMode; // voice_primary | chips | number | text

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "facts_extracted", nullable = false)
    private String factsExtracted = "[]"; // raw JSON array, shape owned by the Python Interrogator contract

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "slots_spawned")
    private List<String> slotsSpawned = List.of();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "slots_waived")
    private List<String> slotsWaived = List.of();

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Turn() {
    }

    public Turn(UUID sessionId, int turnNumber, String questionText, List<String> targetsSlots, String inputMode) {
        this.sessionId = sessionId;
        this.turnNumber = turnNumber;
        this.questionText = questionText;
        this.targetsSlots = targetsSlots;
        this.inputMode = inputMode;
    }

    public void recordAnswer(String answerText) {
        this.answerText = answerText;
    }

    public void applyExtraction(String factsExtractedJson, List<String> slotsSpawned, List<String> slotsWaived,
                                 Integer tokensIn, Integer tokensOut) {
        this.factsExtracted = factsExtractedJson;
        this.slotsSpawned = slotsSpawned;
        this.slotsWaived = slotsWaived;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public String getQuestionText() {
        return questionText;
    }

    public Technique getTechnique() {
        return technique;
    }

    public List<String> getTargetsSlots() {
        return targetsSlots;
    }

    public String getAnswerText() {
        return answerText;
    }

    public String getInputMode() {
        return inputMode;
    }

    public String getFactsExtracted() {
        return factsExtracted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Mirrors interrogation-engine.md §4's technique table. */
    public enum Technique {
        FREE_ELICITATION, LADDERING, CONCRETIZATION, CONTRAST_TRIADIC,
        ASSUMPTION_SURFACING, CONTRADICTION_RESOLUTION, SCENARIO_PROJECTION, EXPERTISE_PROBE
    }
}
