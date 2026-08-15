package com.grilld.backend.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.memory.WorkingContext;
import com.grilld.backend.memory.WorkingContextAssembler;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
    private final WorkingContextAssembler contextAssembler;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public SessionService(DiscoverySessionRepository sessionRepository, ProjectBriefRepository briefRepository,
                           TurnRepository turnRepository, SlotRepository slotRepository,
                           WorkingContextAssembler contextAssembler, AiServiceClient aiServiceClient,
                           ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.briefRepository = briefRepository;
        this.turnRepository = turnRepository;
        this.slotRepository = slotRepository;
        this.contextAssembler = contextAssembler;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SessionStartResult startSession(UUID userId, String rawIdea) {
        DiscoverySession session = sessionRepository.save(new DiscoverySession(userId, rawIdea));
        briefRepository.save(new ProjectBrief(session.getId()));
        slotRepository.saveAll(SeedSlots.forSession(session.getId()));

        WorkingContext context = contextAssembler.assemble(session.getId());
        InterrogatorTurnResult result = aiServiceClient.nextTurn(context);

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

        if (result.readyToConclude()) {
            session.touch();
            return TurnAnswerResult.markConcluded();
        }

        Turn nextTurn = createTurnFromQuestion(sessionId, pendingTurn.getTurnNumber() + 1, result);
        session.touch();
        return TurnAnswerResult.nextQuestion(nextTurn.getQuestionText());
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
                slot.fill(fact.value(), fact.confidence(), answeredTurn.getId().toString(), answeredTurn.getTurnNumber());
                slotRepository.save(slot);
            });
            briefPatch.put(fact.slotKey(), fact.value());
        }

        for (InterrogatorTurnResult.NewSlot newSlot : result.newSlots()) {
            // Defensive, not just for this stub: a real Interrogator could plausibly
            // propose a "new" slot that already exists (seed, or spawned on an earlier
            // turn). Silently skip rather than let a duplicate-key error surface as a
            // 500 - this is a data quirk from the AI side, not a request the user made.
            if (slotRepository.findBySessionIdAndSlotKey(sessionId, newSlot.key()).isEmpty()) {
                Slot slot = new Slot(sessionId, newSlot.key(), newSlot.description(),
                        Slot.Origin.valueOf(newSlot.origin()), newSlot.importance(), answeredTurn.getTurnNumber());
                slotRepository.save(slot);
            }
        }

        mergeBriefJson(sessionId, briefPatch);

        List<String> spawnedKeys = result.newSlots().stream().map(InterrogatorTurnResult.NewSlot::key).toList();
        List<String> waivedKeys = result.waivedSlots().stream().map(InterrogatorTurnResult.WaivedSlot::key).toList();
        answeredTurn.applyExtraction(toJson(result.extractedFacts()), spawnedKeys, waivedKeys, null, null);
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
