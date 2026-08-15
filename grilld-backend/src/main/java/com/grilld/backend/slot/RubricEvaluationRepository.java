package com.grilld.backend.slot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RubricEvaluationRepository extends JpaRepository<RubricEvaluation, UUID> {

    List<RubricEvaluation> findBySessionIdOrderByAtTurnDesc(UUID sessionId);
}
