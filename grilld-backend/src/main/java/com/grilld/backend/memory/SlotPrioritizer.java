package com.grilld.backend.memory;

import com.grilld.backend.slot.Slot;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ranks open slots so the Interrogator's judgment is spent on phrasing, not
 * triage - interrogation-engine.md §6: "the Interrogator receives open slots
 * pre-ranked by Java."
 *
 * priority = importance x information_gain x blocking_factor / estimated_effort
 *
 *  - information_gain: how many other slots this one's `unlocks` list names
 *    (a proxy for "how much of the remaining interview this shortens")
 *  - blocking_factor: 1 plus however many other open slots are parented to
 *    this one (per interrogation-engine.md §6, "never ask a slot whose parent
 *    is unfilled" - a slot blocking several children is worth resolving sooner)
 *  - estimated_effort: not yet modeled (would come from answer-length signals
 *    the real Interrogator agent has visibility into, Phase 4) - defaults to 1
 */
@Component
public class SlotPrioritizer {

    public List<WorkingContext.RankedSlot> rank(List<Slot> openSlots) {
        Map<String, Long> childrenPerParentKey = openSlots.stream()
                .filter(s -> s.getParentSlotKey() != null)
                .collect(Collectors.groupingBy(Slot::getParentSlotKey, Collectors.counting()));

        return openSlots.stream()
                .map(slot -> {
                    int informationGain = Math.max(1, slot.getUnlocks().size());
                    long blockingFactor = 1 + childrenPerParentKey.getOrDefault(slot.getSlotKey(), 0L);
                    double estimatedEffort = 1.0;
                    double priority = (slot.getImportance() * informationGain * blockingFactor) / estimatedEffort;
                    return new WorkingContext.RankedSlot(slot.getSlotKey(), slot.getDescription(), slot.getImportance(), priority);
                })
                .sorted(Comparator.comparingDouble(WorkingContext.RankedSlot::priority).reversed())
                .toList();
    }
}
