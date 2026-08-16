package com.grilld.backend.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The only {@link PackageStorage} implementation that exists today - a plain
 * directory on local disk. Real object storage (S3/R2) is deliberately not
 * built yet (§10.7); this is what makes "a real package zip downloads"
 * (the original Phase 6 gate) true without needing cloud credentials no one
 * has configured.
 */
@Component
public class LocalFilesystemPackageStorage implements PackageStorage {

    private final Path baseDir;

    public LocalFilesystemPackageStorage(@Value("${grilld.packages.local-storage-dir:./data/packages}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public String save(UUID runId, byte[] zipBytes) {
        try {
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(runId + ".zip");
            Files.write(file, zipBytes);
            return file.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save package zip for run " + runId, e);
        }
    }

    @Override
    public byte[] load(String storageUrl) {
        try {
            return Files.readAllBytes(Path.of(URI.create(storageUrl)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load package zip from " + storageUrl, e);
        }
    }
}
