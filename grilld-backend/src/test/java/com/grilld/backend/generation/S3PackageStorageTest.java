package com.grilld.backend.generation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs S3PackageStorage against a real S3 API (MinIO, not a mock/stub) via
 * Testcontainers - proves the AWS SDK v2 wiring (endpoint override, path-style
 * access, request/response shapes) actually works, not just that the code
 * compiles. MinIO over LocalStack: no account/auth-token required to run
 * locally (LocalStack now requires one - see docs/phases/phase-10/README.md).
 */
@Testcontainers
class S3PackageStorageTest {

    private static final String BUCKET = "grilld-packages-test";

    private static MinIOContainer minio;
    private static S3Client s3Client;
    private static S3PackageStorage storage;

    @BeforeAll
    static void startMinioAndCreateBucket() {
        minio = new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");
        minio.start();

        s3Client = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        storage = new S3PackageStorage(s3Client, BUCKET);
    }

    @AfterAll
    static void stopMinio() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void savedZipBytesLoadBackIdentically() {
        UUID runId = UUID.randomUUID();
        byte[] zipBytes = "not a real zip, just test bytes".getBytes(StandardCharsets.UTF_8);

        String storageUrl = storage.save(runId, zipBytes);

        assertEquals("s3://" + BUCKET + "/" + runId + ".zip", storageUrl);
        assertArrayEquals(zipBytes, storage.load(storageUrl));
    }

    @Test
    void loadingAMissingKeyThrows() {
        String missingUrl = "s3://" + BUCKET + "/" + UUID.randomUUID() + ".zip";

        Exception ex = assertThrows(Exception.class, () -> storage.load(missingUrl));
        assertTrue(ex instanceof NoSuchKeyException || ex.getCause() instanceof NoSuchKeyException,
                "expected a NoSuchKeyException somewhere in the failure, got: " + ex);
    }

    @Test
    void loadingANonS3UrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> storage.load("file:///not/an/s3/url.zip"));
    }
}
