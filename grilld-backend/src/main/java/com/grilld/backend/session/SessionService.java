package com.grilld.backend.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.RubricContext;
import com.grilld.backend.aiservice.RubricResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.memory.WorkingContext;
import com.grilld.backend.memory.WorkingContextAssembler;
import com.grilld.backend.slot.RubricEvaluation;
import com.grilld.backend.slot.RubricEvaluationRepository;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the per-turn loop from interrogation-engine.md §3: assemble
 * context -> call the Interrogator (AiServiceClient) -> persist what came
 * back. This class doesn't know or care whether AiServiceClient is the stub
 * or the real Python service - that's the point of the interface.
 */
@Service
public class SessionService {

    private final DiscoverySessionRepository sessionRepository;
    private final ProjectBriefRepository briefRepository;
    private final TurnRepository turnRepository;
    private final SlotRepository slotRepository;
    private final RubricEvaluationRepository rubricEvaluationRepository;
    private final WorkingContextAssembler contextAssembler;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final RevisionClassifier revisionClassifier;

    public SessionService(DiscoverySessionRepository sessionRepository, ProjectBriefRepository briefRepository,
                           TurnRepository turnRepository, SlotRepository slotRepository,
                           RubricEvaluationRepository rubricEvaluationRepository,
                           WorkingContextAssembler contextAssembler, AiServiceClient aiServiceClient,
                           ObjectMapper objectMapper, RevisionClassifier revisionClassifier) {
        this.sessionRepository = sessionRepository;
        this.briefRepository = briefRepository;
        this.turnRepository = turnRepository;
        this.slotRepository = slotRepository;
        this.rubricEvaluationRepository = rubricEvaluationRepository;
        this.contextAssembler = contextAssembler;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
        this.revisionClassifier = revisionClassifier;
    }

    /**
     * The ownership check every session-scoped controller endpoint runs
     * before touching a session - added in Phase 8's hardening pass once
     * GenerationController.generate()'s equivalent check (Phase 7 task 1)
     * made it obvious the rest of this API surface never verified a caller
     * actually owned the {@code sessionId} in the URL. Deliberately a thin
     * controller-facing method, not a parameter threaded through every
     * existing SessionService method - see LEARNING.md's Phase 8 note for
     * why that would have been a much larger, riskier change for the same
     * security guarantee.
     */
    public void verifyOwnership(UUID sessionId, UUID requestingUserId) {
        DiscoverySession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No session " + sessionId));
        if (!session.getUserId().equals(requestingUserId)) {
            throw new AccessDeniedException("Session " + sessionId + " does not belong to the requesting user");
        }
    }

    @Transactional
    public SessionStartResult startSession(UUID userId, String rawIdea) {
        DiscoverySession session = sessionRepository.save(new DiscoverySession(userId, rawIdea));
        briefRepository.save(new ProjectBrief(session.getId()));
        slotRepository.saveAll(SeedSlots.forSession(session.getId()));

        WorkingContext context = contextAssembler.assemble(session.getId());
        InterrogatorTurnResult result = aiServiceClient.nextTurn(context);
        if (result.nextQuestion() == null) {
            throw new IllegalStateException(
                    "Interrogator returned no opening question for session " + session.getId());
        }

        Turn firstTurn = createTurnFromQuestion(session.getId(), 1, result);
        return new SessionStartResult(session.getId(), firstTurn.getQuestionText());
    }

    @Transactional
    public TurnAnswerResult submitAnswer(UUID sessionId, String answerText) {
        DiscoverySession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No session " + sessionId));

        Turn pendingTurn = turnRepository.findBySessionIdOrderByTurnNumberDesc(sessionId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Session " + sessionId + " has no pending turn"));
        pendingTurn.recordAnswer(answerText);

        WorkingContext context = contextAssembler.assemble(sessionId);
        InterrogatorTurnResult result = aiServiceClient.nextTurn(context);

        applyExtraction(sessionId, pendingTurn, result);

        if (result.readyToConclude() || result.nextQuestion() == null) {
            // A null nextQuestion with readyToConclude=false is malformed
            // structured output from the AI side - treat it the same as a
            // conclude attempt rather than crashing. The rubric gate below
            // will reject it (and retry with a targeted question) unless the
            // brief genuinely is complete enough, which is the right outcome
            // either way.
            return resolveConclusionAttempt(sessionId, session, pendingTurn);
        }

        Turn nextTurn = createTurnFromQuestion(sessionId, pendingTurn.getTurnNumber() + 1, result);
        session.touch();
        return TurnAnswerResult.nextQuestion(nextTurn.getQuestionText());
    }

    /**
     * The escape hatch (product-and-architecture.md §7): "user can force-accept
     * after N rounds ('just generate it')." Unconditional - no rubric check,
     * no minimum brief completeness required. Never trap the user in an
     * interview loop; everything still OPEN gets surfaced to the Orchestrator
     * as unresolved (see GenerationService/AGENT_PRIMARY_OUTPUT's
     * ASSUMPTIONS.md wiring) rather than silently dropped.
     */
    @Transactional
    public void forceConclude(UUID sessionId) {
        DiscoverySession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No session " + sessionId));
        session.markReadyForGeneration();
    }

    /**
     * The Interrogator thinks it's done - but per product-and-architecture.md
     * §7, that's only a proposal. The Rubric Agent is the actual adversarial
     * gate: on "accept" the session really concludes; on "probe_further" its
     * open_gaps get handed straight back to the Interrogator for one more,
     * targeted question (interrogation-engine.md §8) instead of ending.
     */
    private TurnAnswerResult resolveConclusionAttempt(UUID sessionId, DiscoverySession session, Turn lastAnsweredTurn) {
        RubricResult rubric = evaluateRubric(sessionId);
        persistRubricEvaluation(sessionId, lastAnsweredTurn.getTurnNumber(), rubric);

        if (rubric.accepted()) {
            session.markReadyForGeneration();
            return TurnAnswerResult.markConcluded();
        }

        WorkingContext retryContext = contextAssembler.assemble(sessionId, rubric.openGaps());
        InterrogatorTurnResult retryResult = aiServiceClient.nextTurn(retryContext);

        if (retryResult.readyToConclude() || retryResult.nextQuestion() == null) {
            // Never trap the user in a loop (interrogation-engine.md §7): if the
            // Interrogator still can't produce a targeted follow-up after an
            // explicit rejection, accept what we have rather than looping.
            // Everything unresolved is already on record via the RubricEvaluation
            // just persisted above.
            session.markReadyForGeneration();
            return TurnAnswerResult.markConcluded();
        }

        Turn nextTurn = createTurnFromQuestion(sessionId, lastAnsweredTurn.getTurnNumber() + 1, retryResult);
        session.touch();
        return TurnAnswerResult.nextQuestion(nextTurn.getQuestionText());
    }

    @Transactional
    public ScaleCalibrationResult calibrateScale(UUID sessionId) {
        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        ScaleCalibrationResult result = aiServiceClient.calibrateScale(brief.getBriefJson());
        brief.applyScaleCalibration(result.tier(), result.reasoning());
        briefRepository.save(brief);
        return result;
    }

    @Transactional
    public void overrideScaleTier(UUID sessionId, String tier) {
        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        brief.overrideScaleTier(tier);
        briefRepository.save(brief);
    }

    private RubricResult evaluateRubric(UUID sessionId) {
        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        List<RubricContext.SlotSnapshot> slots = slotRepository.findBySessionId(sessionId).stream()
                .map(s -> new RubricContext.SlotSnapshot(s.getSlotKey(), s.getStatus().name(), s.getValue(), s.getImportance()))
                .toList();
        return aiServiceClient.evaluateRubric(new RubricContext(sessionId, brief.getBriefJson(), slots));
    }

    private void persistRubricEvaluation(UUID sessionId, int atTurn, RubricResult rubric) {
        Map<String, String> scoresByDimension = new LinkedHashMap<>();
        rubric.dimensions().forEach(d -> scoresByDimension.put(d.dimension(), d.score()));

        RubricEvaluation evaluation = new RubricEvaluation(
                sessionId, atTurn, toJson(scoresByDimension), toJson(rubric.openGaps()),
                RubricEvaluation.Verdict.valueOf(rubric.verdict()));
        rubricEvaluationRepository.save(evaluation);
    }

    private Turn createTurnFromQuestion(UUID sessionId, int turnNumber, InterrogatorTurnResult result) {
        InterrogatorTurnResult.NextQuestion question = result.nextQuestion();
        Turn turn = new Turn(sessionId, turnNumber, question.text(), question.targetsSlots(), question.inputMode());
        return turnRepository.save(turn);
    }

    private void applyExtraction(UUID sessionId, Turn answeredTurn, InterrogatorTurnResult result) {
        ObjectNode briefPatch = objectMapper.createObjectNode();

        for (InterrogatorTurnResult.ExtractedFact fact : result.extractedFacts()) {
            slotRepository.findBySessionIdAndSlotKey(sessionId, fact.slotKey()).ifPresent(slot -> {
                if (isContradiction(slot, fact)) {
                    spawnContradictionResolutionSlot(sessionId, slot, fact, answeredTurn.getTurnNumber());
                    // Deliberately don't overwrite the existing value yet - the resolution
                    // slot surfaces the conflict on a future turn rather than silently
                    // picking a side. See interrogation-engine.md §9's ContradictionDetector
                    // guardrail; this is the Java-side home for it (decisions-and-technical-
                    // architecture.md §11.1 - the Python Interrogator only sees a compacted
                    // text summary, not the structured brief, so it can't reliably do this
                    // comparison itself).
                    return;
                }
                slot.fill(fact.value(), fact.confidence(), answeredTurn.getId().toString(), answeredTurn.getTurnNumber());
                slotRepository.save(slot);
                briefPatch.put(fact.slotKey(), fact.value());
            });
        }

        for (InterrogatorTurnResult.NewSlot newSlot : result.newSlots()) {
            // Defensive, not just for this stub: a real Interrogator could plausibly
            // propose a "new" slot that already exists (seed, or spawned on an earlier
            // turn). Silently skip rather than let a duplicate-key error surface as a
            // 500 - this is a data quirk from the AI side, not a request the user made.
            if (slotRepository.findBySessionIdAndSlotKey(sessionId, newSlot.key()).isEmpty()) {
                Slot slot = newSlot.parentSlotKey() == null
                        ? new Slot(sessionId, newSlot.key(), newSlot.description(),
                                Slot.Origin.valueOf(newSlot.origin()), newSlot.importance(), answeredTurn.getTurnNumber())
                        : new Slot(sessionId, newSlot.key(), newSlot.description(),
                                Slot.Origin.valueOf(newSlot.origin()), newSlot.importance(), answeredTurn.getTurnNumber(),
                                newSlot.parentSlotKey());
                slotRepository.save(slot);

                // The reverse link: RevisionClassifier's blast-radius traversal walks a
                // parent's `unlocks` outward to find already-FILLED descendants that a
                // contradiction on the parent would invalidate (§7). Was never wired up
                // before this phase - parentSlotKey came through the AI service's
                // response but was silently dropped.
                if (newSlot.parentSlotKey() != null) {
                    slotRepository.findBySessionIdAndSlotKey(sessionId, newSlot.parentSlotKey())
                            .ifPresent(parent -> {
                                parent.addUnlockedSlot(newSlot.key());
                                slotRepository.save(parent);
                            });
                }
            }
        }

        mergeBriefJson(sessionId, briefPatch);

        List<String> spawnedKeys = result.newSlots().stream().map(InterrogatorTurnResult.NewSlot::key).toList();
        List<String> waivedKeys = result.waivedSlots().stream().map(InterrogatorTurnResult.WaivedSlot::key).toList();
        answeredTurn.applyExtraction(toJson(result.extractedFacts()), spawnedKeys, waivedKeys, null, null);
    }

    private boolean isContradiction(Slot existingSlot, InterrogatorTurnResult.ExtractedFact fact) {
        return existingSlot.getStatus() == Slot.Status.FILLED
                && existingSlot.getValue() != null
                && !existingSlot.getValue().equals(fact.value());
    }

    private void spawnContradictionResolutionSlot(UUID sessionId, Slot existingSlot,
                                                    InterrogatorTurnResult.ExtractedFact fact, int atTurn) {
        String resolutionKey = existingSlot.getSlotKey() + "_contradiction_turn_" + atTurn;
        if (slotRepository.findBySessionIdAndSlotKey(sessionId, resolutionKey).isPresent()) {
            return; // already flagged this exact conflict, don't duplicate
        }

        RevisionClassifier.Classification classification = revisionClassifier.classify(existingSlot);
        String description = classification.type() == RevisionClassifier.Type.MAJOR_REVISION
                ? majorRevisionDescription(existingSlot, fact, classification)
                : minorCorrectionDescription(existingSlot, fact);

        Slot resolutionSlot = new Slot(sessionId, resolutionKey, description, Slot.Origin.PROBE, 5, atTurn);
        slotRepository.save(resolutionSlot);
    }

    private String minorCorrectionDescription(Slot existingSlot, InterrogatorTurnResult.ExtractedFact fact) {
        return "Contradiction on " + existingSlot.getSlotKey() + ": was \""
                + existingSlot.getValue() + "\", now \"" + fact.value() + "\" - which is the real answer?";
    }

    /**
     * MAJOR_REVISION never silently cascades (§7) - the description itself is
     * the surfaced confirmation prompt today, since there's no dedicated
     * confirm-then-regenerate UI/pipeline built yet for either revision class
     * (that's real, separate scope - see LEARNING.md's Phase 6 task 6 note).
     * The "[MAJOR REVISION]" prefix is a deliberate, greppable marker so this
     * class of resolution slot is distinguishable from an ordinary one
     * without needing a schema change.
     */
    private String majorRevisionDescription(Slot existingSlot, InterrogatorTurnResult.ExtractedFact fact,
                                              RevisionClassifier.Classification classification) {
        StringBuilder description = new StringBuilder("[MAJOR REVISION] This looks like more than a quick fix - ")
                .append(existingSlot.getSlotKey()).append(" was \"").append(existingSlot.getValue())
                .append("\", now \"").append(fact.value()).append("\".");
        if (classification.touchesSeedSlot()) {
            description.append(" That's a core assumption (").append(existingSlot.getSlotKey()).append(")");
            if (!classification.invalidatedDescendantSlotKeys().isEmpty()) {
                description.append(", and it also affects ").append(classification.invalidatedDescendantSlotKeys().size())
                        .append(" already-answered question(s) building on it");
            }
            description.append('.');
        } else {
            description.append(" It invalidates ").append(classification.invalidatedDescendantSlotKeys().size())
                    .append(" already-answered question(s) that built on it.");
        }
        description.append(" Confirm this is a real pivot, not a one-off correction, before we treat it that way.");
        return description.toString();
    }

    private void mergeBriefJson(UUID sessionId, ObjectNode patch) {
        if (patch.isEmpty()) {
            return;
        }
        ProjectBrief brief = briefRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No brief for session " + sessionId));
        try {
            ObjectNode current = (ObjectNode) objectMapper.readTree(brief.getBriefJson());
            current.setAll(patch);
            brief.updateBrief(objectMapper.writeValueAsString(current));
            briefRepository.save(brief);
        } catch (Exception e) {
            throw new IllegalStateException("Could not merge brief JSON for session " + sessionId, e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize extraction result", e);
        }
    }

    public record SessionStartResult(UUID sessionId, String question) {
    }

    public record TurnAnswerResult(String question, boolean concluded) {
        static TurnAnswerResult nextQuestion(String question) {
            return new TurnAnswerResult(question, false);
        }

        static TurnAnswerResult markConcluded() {
            return new TurnAnswerResult(null, true);
        }
    }
}
