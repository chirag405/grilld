package com.grilld.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TurnRepository extends JpaRepository<Turn, UUID> {

    List<Turn> findBySessionIdOrderByTurnNumberDesc(UUID sessionId);

    int countBySessionId(UUID sessionId);
}
