package com.grilld.backend.slot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SlotWaiveRepository extends JpaRepository<SlotWaive, UUID> {
}
