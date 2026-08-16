package com.grilld.backend.session;

import com.grilld.backend.slot.Slot;
import com.grilld.backend.slot.SlotRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the blast-radius rules from docs/decisions-and-technical-
 * architecture.md §7 in isolation - no Spring context or database, a mocked
 * SlotRepository standing in for "every slot in this session."
 */
class RevisionClassifierTest {

    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final RevisionClassifier classifier = new RevisionClassifier(slotRepository);
    private final UUID sessionId = UUID.randomUUID();

    private Slot filledSlot(String key, Slot.Origin origin) {
        Slot slot = new Slot(sessionId, key, "desc", origin, 5, 1);
        slot.fill("some value", 0.9, "evidence", 1);
        return slot;
    }

    @Test
    void aNonSeedSlotWithNoDescendantsAndSmallBlastRadiusIsMinor() {
        Slot contradicted = filledSlot("monetization_intent", Slot.Origin.DERIVED);
        Slot other = filledSlot("problem_statement", Slot.Origin.SEED);

        when(slotRepository.findBySessionId(sessionId)).thenReturn(List.of(contradicted, other));

        RevisionClassifier.Classification result = classifier.classify(contradicted);

        assertEquals(RevisionClassifier.Type.MINOR_CORRECTION, result.type());
        assertFalse(result.touchesSeedSlot());
        assertTrue(result.invalidatedDescendantSlotKeys().isEmpty());
    }

    @Test
    void aSeedSlotIsAlwaysMajorRegardlessOfBlastRadius() {
        Slot contradicted = filledSlot("problem_statement", Slot.Origin.SEED);
        when(slotRepository.findBySessionId(sessionId)).thenReturn(List.of(contradicted));

        RevisionClassifier.Classification result = classifier.classify(contradicted);

        assertEquals(RevisionClassifier.Type.MAJOR_REVISION, result.type());
        assertTrue(result.touchesSeedSlot());
    }

    @Test
    void invalidatingMoreThanThirtyPercentOfFilledSlotsIsMajorEvenWithoutTouchingSeed() {
        // 4 filled slots total; contradicted (non-SEED) unlocks 2 of the other 3 -
        // 2/4 = 50%, over the 30% threshold, so MAJOR despite no SEED slot involved.
        Slot contradicted = filledSlot("tech_stack_choice", Slot.Origin.DERIVED);
        Slot child1 = filledSlot("hosting_choice", Slot.Origin.DERIVED);
        Slot child2 = filledSlot("database_choice", Slot.Origin.DERIVED);
        Slot unrelated = filledSlot("timeline", Slot.Origin.SEED);
        contradicted.addUnlockedSlot("hosting_choice");
        contradicted.addUnlockedSlot("database_choice");

        when(slotRepository.findBySessionId(sessionId))
                .thenReturn(List.of(contradicted, child1, child2, unrelated));

        RevisionClassifier.Classification result = classifier.classify(contradicted);

        assertEquals(RevisionClassifier.Type.MAJOR_REVISION, result.type());
        assertFalse(result.touchesSeedSlot(), "MAJOR here should come from blast radius, not the SEED rule");
        assertEquals(2, result.invalidatedDescendantSlotKeys().size());
    }

    @Test
    void descendantTraversalWalksMultipleLevelsDeep() {
        Slot contradicted = filledSlot("root", Slot.Origin.DERIVED);
        Slot child = filledSlot("child", Slot.Origin.DERIVED);
        Slot grandchild = filledSlot("grandchild", Slot.Origin.DERIVED);
        contradicted.addUnlockedSlot("child");
        child.addUnlockedSlot("grandchild");

        when(slotRepository.findBySessionId(sessionId))
                .thenReturn(List.of(contradicted, child, grandchild));

        RevisionClassifier.Classification result = classifier.classify(contradicted);

        assertTrue(result.invalidatedDescendantSlotKeys().contains("child"));
        assertTrue(result.invalidatedDescendantSlotKeys().contains("grandchild"),
                "expected the grandchild to be reached transitively via child's own unlocks");
    }
}
