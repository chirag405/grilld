package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {

    List<AgentExecution> findByRunId(UUID runId);
}
