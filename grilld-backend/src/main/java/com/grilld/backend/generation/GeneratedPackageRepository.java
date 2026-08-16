package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GeneratedPackageRepository extends JpaRepository<GeneratedPackage, UUID> {

    List<GeneratedPackage> findByRunIdOrderByCreatedAtDesc(UUID runId);
}
