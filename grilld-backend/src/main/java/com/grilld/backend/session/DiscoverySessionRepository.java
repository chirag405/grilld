package com.grilld.backend.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiscoverySessionRepository extends JpaRepository<DiscoverySession, UUID> {
}
