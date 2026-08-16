package com.grilld.backend.session;

import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies how much a contradicting fact actually changes, per
 * docs/decisions-and-technical-architecture.md §7 - the difference between
 * "actually it's freemium, not free" (one slot, fix it and move on) and
 * "forget invoicing, I want a scheduling tool instead" (most of the brief is
 * now wrong). Extends the existing contradiction handling
 * (SessionService.isContradiction/spawnContradictionResolutionSlot -
 * interrogation-engine.md §11's "ContradictionDetector") rather than
 * replacing it: this only decides how the resulting resolution slot gets
 * framed, not whether one gets spawned at all.
 */
@Component
public class RevisionClassifier {

    // ">~30% of currently FILLED slots invalidated" per §7 - a rough MVP
    // threshold, same spirit as the cost circuit breaker's cap: the mechanism
    // matters more than the exact number until real usage data exists.
    private static final double MAJOR_REVISION_THRESHOLD = 0.30;

    private final SlotRepository slotRepository;

    public RevisionClassifier(SlotRepository slotRepository) {
        this.slotRepository = slotRepository;
    }

    public Classification classify(Slot contradictedSlot) {
        List<Slot> allSlots = slotRepository.findBySessionId(contradictedSlot.getSessionId());

        Set<String> invalidated = new LinkedHashSet<>();
        collectDescendants(contradictedSlot, allSlots, invalidated);

        long filledCount = allSlots.stream().filter(s -> s.getStatus() == Slot.Status.FILLED).count();
        long invalidatedFilledCount = allSlots.stream()
                .filter(s -> invalidated.contains(s.getSlotKey()) && s.getStatus() == Slot.Status.FILLED)
                .count();

        boolean touchesSeedSlot = contradictedSlot.getOrigin() == Slot.Origin.SEED;
        boolean exceedsBlastRadiusThreshold = filledCount > 0
                && (double) invalidatedFilledCount / filledCount > MAJOR_REVISION_THRESHOLD;

        Type type = (touchesSeedSlot || exceedsBlastRadiusThreshold) ? Type.MAJOR_REVISION : Type.MINOR_CORRECTION;
        return new Classification(type, Set.copyOf(invalidated), touchesSeedSlot);
    }

    /** Walks `unlocks` outward from the contradicted slot to every descendant it would invalidate. */
    private void collectDescendants(Slot slot, List<Slot> allSlots, Set<String> collected) {
        for (String childKey : slot.getUnlocks()) {
            if (collected.add(childKey)) {
                allSlots.stream()
                        .filter(s -> s.getSlotKey().equals(childKey))
                        .findFirst()
                        .ifPresent(child -> collectDescendants(child, allSlots, collected));
            }
        }
    }

    public record Classification(Type type, Set<String> invalidatedDescendantSlotKeys, boolean touchesSeedSlot) {
    }

    public enum Type {
        MINOR_CORRECTION, MAJOR_REVISION
    }
}
