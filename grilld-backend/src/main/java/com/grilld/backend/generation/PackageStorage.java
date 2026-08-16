package com.grilld.backend.generation;

import java.util.UUID;

/**
 * Where a packaged blueprint's zip bytes actually live. §10.7 leaves the real
 * storage provider (S3/R2) "intentionally... undecided" until an actual
 * deployment exists to configure credentials for - same seam pattern as
 * {@code AiServiceClient}: one interface, swap the implementation once that
 * decision is made, nothing above this layer needs to change.
 */
public interface PackageStorage {

    /** Persists the zip and returns a URL {@link #load} can resolve later (a {@code file://} path for the local implementation). */
    String save(UUID runId, byte[] zipBytes);

    byte[] load(String storageUrl);
}
