package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    List<AgentExecution> findByRunId(UUID runId);

    List<AgentExecution> findByRunIdOrderByStartedAtAsc(UUID runId);

    Optional<AgentExecution> findByRunIdAndAgentName(UUID runId, String agentName);

    /** The cost circuit breaker's rolling-window scan (§10.6) - every step that started within the window. */
    List<AgentExecution> findByStartedAtAfter(Instant threshold);
}
