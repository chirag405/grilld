package com.grilld.backend.generation;

import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Lets a client check whether a run's blueprint package is ready and
 * download the actual zip (product-and-architecture.md §5) - the original
 * Phase 6 gate's "a real package zip downloads," backed by
 * {@link LocalFilesystemPackageStorage} today (§10.7 defers real object
 * storage).
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/runs/{runId}/package")
public class PackageController {

    private final GeneratedPackageRepository packageRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final PackageStorage packageStorage;
    private final GenerationService generationService;

    public PackageController(GeneratedPackageRepository packageRepository,
                              PackageDocumentRepository packageDocumentRepository,
                              PackageStorage packageStorage, GenerationService generationService) {
        this.packageRepository = packageRepository;
        this.packageDocumentRepository = packageDocumentRepository;
        this.packageStorage = packageStorage;
        this.generationService = generationService;
    }

    @GetMapping
    public PackageStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        verifyOwnership(runId, jwt);
        GeneratedPackage generatedPackage = latestFor(runId);
        List<String> paths = packageDocumentRepository.findByPackageId(generatedPackage.getId())
                .stream().map(PackageDocument::getPath).toList();
        return new PackageStatusResponse(generatedPackage.getId(), generatedPackage.getStatus().name(), paths);
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId) {
        verifyOwnership(runId, jwt);
        GeneratedPackage generatedPackage = latestFor(runId);
        if (generatedPackage.getStatus() != GeneratedPackage.Status.READY) {
            throw new ResourceNotFoundException("Package for run " + runId + " is not ready yet");
        }
        byte[] zipBytes = packageStorage.load(generatedPackage.getStorageUrl());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("grilld-blueprint-" + runId + ".zip").build().toString())
                .body(zipBytes);
    }

    private void verifyOwnership(UUID runId, Jwt jwt) {
        UUID owningUserId = generationService.resolveOwningUserId(runId);
        if (!owningUserId.equals(UUID.fromString(jwt.getSubject()))) {
            throw new AccessDeniedException("Run " + runId + " does not belong to the requesting user");
        }
    }

    private GeneratedPackage latestFor(UUID runId) {
        return packageRepository.findByRunIdOrderByCreatedAtDesc(runId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No package for run " + runId));
    }

    public record PackageStatusResponse(UUID packageId, String status, List<String> documentPaths) {
    }
}
