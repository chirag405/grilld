package com.grilld.backend.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A plain directory on local disk - the default {@link PackageStorage}, since
 * it needs no cloud credentials to work (what originally made "a real package
 * zip downloads", the Phase 6 gate, true with nothing configured). Does not
 * survive a redeploy and does not work across more than one backend instance
 * (each has its own disk) - {@link S3PackageStorage} is the real answer for
 * a production deployment; switch with grilld.packages.storage-provider=s3
 * (see docs/phases/phase-10/SETUP.md).
 */
@Component
@ConditionalOnProperty(prefix = "grilld.packages", name = "storage-provider", havingValue = "local", matchIfMissing = true)
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
