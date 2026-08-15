package com.grilld.backend.brief;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectBriefRepository extends JpaRepository<ProjectBrief, UUID> {

    Optional<ProjectBrief> findBySessionId(UUID sessionId);
}
