package com.grilld.backend.generation;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a completed {@link GenerationRun}'s {@link GeneratedDocument}
 * rows into the zipped blueprint (product-and-architecture.md §5), uploads
 * it via {@link PackageStorage}, and records the manifest
 * ({@link GeneratedPackage} + {@link PackageDocument} rows).
 *
 * <p><b>Deliberately doesn't render Mermaid `.mmd` sources to SVG/PNG.</b>
 * That needs the "Mermaid CLI sidecar" §10.7 explicitly leaves undecided
 * until real hosting infra exists (Node + a headless Chromium install) - the
 * zip includes the Diagram Agent's real `.mmd` source files as-is, which are
 * genuinely useful on their own (any Mermaid-aware editor/viewer renders
 * them), just not pre-rendered. Revisit alongside that infra decision.
 */
@Service
public class PackagerService {

    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final GeneratedPackageRepository packageRepository;
    private final PackageDocumentRepository packageDocumentRepository;
    private final PackageStorage packageStorage;

    public PackagerService(GeneratedDocumentRepository generatedDocumentRepository,
                            GeneratedPackageRepository packageRepository,
                            PackageDocumentRepository packageDocumentRepository,
                            PackageStorage packageStorage) {
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.packageRepository = packageRepository;
        this.packageDocumentRepository = packageDocumentRepository;
        this.packageStorage = packageStorage;
    }

    public GeneratedPackage packageRun(UUID runId) {
        GeneratedPackage generatedPackage = packageRepository.save(new GeneratedPackage(runId));
        List<GeneratedDocument> documents = generatedDocumentRepository.findByRunId(runId);

        try {
            byte[] zipBytes = zip(documents);
            String storageUrl = packageStorage.save(runId, zipBytes);
            generatedPackage.markReady(storageUrl);
            packageRepository.save(generatedPackage);

            for (GeneratedDocument document : documents) {
                packageDocumentRepository.save(
                        new PackageDocument(generatedPackage.getId(), docTypeOf(document.getPath()), document.getPath()));
            }
        } catch (RuntimeException e) {
            generatedPackage.markFailed();
            packageRepository.save(generatedPackage);
        }

        return generatedPackage;
    }

    private byte[] zip(List<GeneratedDocument> documents) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (GeneratedDocument document : documents) {
                zip.putNextEntry(new ZipEntry(zipEntryNameOf(document.getPath())));
                zip.write(document.getContent().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build package zip", e);
        }
    }

    // Every generated path is virtual and absolute ("/docs/STRATEGY.md") - zip
    // entries can't start with a slash, so this becomes "docs/STRATEGY.md".
    private String zipEntryNameOf(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    // The package tree's top-level folder ("docs", "diagrams", "agent-kit",
    // "infra-stubs") doubles as package_documents.doc_type - simple, and
    // matches how the tree is already organized (product-and-architecture.md §5).
    private String docTypeOf(String path) {
        String withoutLeadingSlash = zipEntryNameOf(path);
        int slashIndex = withoutLeadingSlash.indexOf('/');
        return slashIndex == -1 ? "root" : withoutLeadingSlash.substring(0, slashIndex);
    }
}
