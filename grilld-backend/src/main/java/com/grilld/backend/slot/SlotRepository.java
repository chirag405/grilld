package com.grilld.backend.slot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    List<Slot> findBySessionId(UUID sessionId);

    Optional<Slot> findBySessionIdAndSlotKey(UUID sessionId, String slotKey);

    List<Slot> findBySessionIdAndStatus(UUID sessionId, Slot.Status status);
}
