package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PackageDocumentRepository extends JpaRepository<PackageDocument, UUID> {

    List<PackageDocument> findByPackageId(UUID packageId);
}
