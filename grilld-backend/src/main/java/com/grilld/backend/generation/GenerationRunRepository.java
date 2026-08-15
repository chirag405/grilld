package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {

    List<GenerationRun> findByBriefIdOrderByStartedAtDesc(UUID briefId);
}
