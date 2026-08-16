package com.grilld.backend.generation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Triggers the specialist roster against a scale-calibrated brief
 * (Phase 5's stated gate: "a full generation run, triggered via API, no UI,
 * produces every expected doc artifact"). Since Phase 6, {@code generate}
 * returns immediately with the new run's id and status {@code IN_PROGRESS} -
 * the actual multi-minute run happens on a background thread
 * (GenerationService.generate). Watch it via {@code GET .../runs/{runId}/events}
 * (RunReportController).
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class GenerationController {

    private final GenerationService generationService;

    public GenerationController(GenerationService generationService) {
        this.generationService = generationService;
    }

    @PostMapping("/{sessionId}/generate")
    public GenerationService.GenerationRunResult generate(@PathVariable UUID sessionId) {
        return generationService.generate(sessionId);
    }
}
