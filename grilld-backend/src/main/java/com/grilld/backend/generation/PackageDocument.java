package com.grilld.backend.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One row of `package_documents` - a manifest entry for one file inside a
 * {@link GeneratedPackage}'s zip. {@code phaseNumber} is null for anything
 * outside the Roadmap Agent's phased plan (most files); populated once §6's
 * per-phase skill unlocking is built - not this task's scope.
 */
@Entity
@Table(name = "package_documents")
public class PackageDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(nullable = false)
    private String path;

    @Column(name = "phase_number")
    private Integer phaseNumber;

    protected PackageDocument() {
    }

    public PackageDocument(UUID packageId, String docType, String path) {
        this.packageId = packageId;
        this.docType = docType;
        this.path = path;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public String getDocType() {
        return docType;
    }

    public String getPath() {
        return path;
    }

    public Integer getPhaseNumber() {
        return phaseNumber;
    }
}
