package com.grilld.backend.generation;

import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Lets the client preview one generated document's actual content before
 * (or instead of) downloading the whole package - the "client-side live
 * preview" this app didn't have anywhere: {@link PackageController} only
 * ever exposed document paths, never content, and the only way to read a
 * generated .md/.mmd file was to download and open the zip. Same ownership
 * pattern as RunReportController/PackageController.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/runs/{runId}/documents")
public class GeneratedDocumentController {

    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final GenerationService generationService;

    public GeneratedDocumentController(GeneratedDocumentRepository generatedDocumentRepository,
                                        GenerationService generationService) {
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.generationService = generationService;
    }

    @GetMapping
    public DocumentContent get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId,
                                @RequestParam String path) {
        UUID owningUserId = generationService.resolveOwningUserId(runId);
        if (!owningUserId.equals(UUID.fromString(jwt.getSubject()))) {
            throw new AccessDeniedException("Run " + runId + " does not belong to the requesting user");
        }
        GeneratedDocument document = generatedDocumentRepository.findByRunIdAndPath(runId, path)
                .orElseThrow(() -> new ResourceNotFoundException("No document \"" + path + "\" for run " + runId));
        return new DocumentContent(document.getPath(), document.getContent());
    }

    public record DocumentContent(String path, String content) {
    }
}
