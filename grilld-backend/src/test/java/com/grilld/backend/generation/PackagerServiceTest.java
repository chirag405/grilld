package com.grilld.backend.generation;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.session.SessionService;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves PackagerService produces a real, readable zip from a run's
 * GeneratedDocument rows, and records a correct manifest - end to end
 * through LocalFilesystemPackageStorage (real disk I/O under target/, not a
 * mock), since "does the zip a client downloads actually contain what it
 * claims to" is exactly the kind of thing a mock would hide a bug in.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "grilld.packages.local-storage-dir=target/test-packages")
class PackagerServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Autowired
    GenerationRunRepository generationRunRepository;

    @Autowired
    GeneratedDocumentRepository generatedDocumentRepository;

    @Autowired
    GeneratedPackageRepository packageRepository;

    @Autowired
    PackageDocumentRepository packageDocumentRepository;

    @Autowired
    PackageStorage packageStorage;

    @Autowired
    PackagerService packagerService;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    private UUID freshRunId(String googleId, String email) {
        User user = userService.findOrCreateFromGoogle(googleId, email);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());

        ProjectBrief brief = briefRepository.findBySessionId(started.sessionId()).orElseThrow();
        return generationRunRepository.save(new GenerationRun(brief.getId())).getId();
    }

    @Test
    void producesADownloadableZipMatchingThePersistedDocuments() throws Exception {
        UUID runId = freshRunId("packager-google-id", "packager@example.com");

        generatedDocumentRepository.save(new GeneratedDocument(runId, "/docs/PROJECT_BRIEF.md", "brief content"));
        generatedDocumentRepository.save(new GeneratedDocument(runId, "/diagrams/architecture.mmd", "graph TD; A-->B;"));

        GeneratedPackage result = packagerService.packageRun(runId);

        assertEquals(GeneratedPackage.Status.READY, result.getStatus());
        assertTrue(result.getStorageUrl() != null && !result.getStorageUrl().isBlank());

        List<PackageDocument> manifest = packageDocumentRepository.findByPackageId(result.getId());
        assertEquals(2, manifest.size());
        assertTrue(manifest.stream().anyMatch(d -> d.getPath().equals("/docs/PROJECT_BRIEF.md") && d.getDocType().equals("docs")));
        assertTrue(manifest.stream().anyMatch(d -> d.getPath().equals("/diagrams/architecture.mmd") && d.getDocType().equals("diagrams")));

        byte[] zipBytes = packageStorage.load(result.getStorageUrl());
        Map<String, String> entries = readZip(zipBytes);
        assertEquals(2, entries.size());
        assertEquals("brief content", entries.get("docs/PROJECT_BRIEF.md"));
        assertEquals("graph TD; A-->B;", entries.get("diagrams/architecture.mmd"));
    }

    private Map<String, String> readZip(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes()));
            }
        }
        return entries;
    }
}
