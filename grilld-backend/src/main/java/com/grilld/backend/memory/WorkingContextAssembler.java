package com.grilld.backend.memory;

import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.session.DiscoverySession;
import com.grilld.backend.session.DiscoverySessionRepository;
import com.grilld.backend.session.Turn;
import com.grilld.backend.session.TurnRepository;
import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the per-turn WorkingContext sent to the Python AI service. The
 * "critical class" from docs/product-and-architecture.md §8's module list -
 * assembled fresh from Postgres every call, never carried over in memory
 * between turns, per the "state lives outside the conversation" principle
 * (product-and-architecture.md §2).
 */
@Service
public class WorkingContextAssembler {

    private static final int RECENT_TURNS_COUNT = 3; // N≈3, interrogation-engine.md §3

    private static final Set<Slot.Status> ANSWERED_STATUSES =
            EnumSet.of(Slot.Status.FILLED, Slot.Status.ASSUMED, Slot.Status.WAIVED);

    private final DiscoverySessionRepository sessionRepository;
    private final ProjectBriefRepository briefRepository;
    private final TurnRepository turnRepository;
    private final SlotRepository slotRepository;
    private final SlotPrioritizer slotPrioritizer;

    public WorkingContextAssembler(DiscoverySessionRepository sessionRepository, ProjectBriefRepository briefRepository,
                                    TurnRepository turnRepository, SlotRepository slotRepository,
                                    SlotPrioritizer slotPrioritizer) {
        this.sessionRepository = sessionRepository;
        this.briefRepository = briefRepository;
        this.turnRepository = turnRepository;
        this.slotRepository = slotRepository;
        this.slotPrioritizer = slotPrioritizer;
    }

    public WorkingContext assemble(UUID sessionId) {
        return assemble(sessionId, List.of());
    }

    public WorkingContext assemble(UUID sessionId, List<String> openGaps) {
        DiscoverySession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("No session " + sessionId));

        String compactedSummary = briefRepository.findBySessionId(sessionId)
                .map(ProjectBrief::getCompactedSummary)
                .orElse(null);

        List<WorkingContext.RecentTurn> recentTurns = turnRepository.findBySessionIdOrderByTurnNumberDesc(sessionId)
                .stream()
                .limit(RECENT_TURNS_COUNT)
                .map(t -> new WorkingContext.RecentTurn(t.getTurnNumber(), t.getQuestionText(), t.getAnswerText()))
                .toList();

        List<Slot> allSlots = slotRepository.findBySessionId(sessionId);

        List<Slot> openSlots = allSlots.stream()
                .filter(s -> s.getStatus() == Slot.Status.OPEN)
                .toList();

        List<String> answeredTopics = allSlots.stream()
                .filter(s -> ANSWERED_STATUSES.contains(s.getStatus()))
                .map(Slot::getSlotKey)
                .toList();

        return new WorkingContext(
                sessionId,
                session.getRawIdea(),
                compactedSummary,
                recentTurns,
                slotPrioritizer.rank(openSlots),
                answeredTopics,
                openGaps
        );
    }
}
