package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, UUID> {

    List<GenerationRun> findByBriefIdOrderByStartedAtDesc(UUID briefId);

    /** The resume sweep's staleness query (GenerationResumeSweep) - a run stuck at {@code status} with no activity since {@code threshold}. */
    List<GenerationRun> findByStatusAndUpdatedAtBefore(GenerationRun.Status status, Instant threshold);
}
