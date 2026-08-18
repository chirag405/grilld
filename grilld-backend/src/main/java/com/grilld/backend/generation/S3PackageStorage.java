package com.grilld.backend.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Real object storage for package zips - survives a redeploy and works across
 * more than one backend instance, unlike {@link LocalFilesystemPackageStorage}.
 * Opt in with grilld.packages.storage-provider=s3 (see S3StorageConfig for the
 * client, docs/phases/phase-10/SETUP.md for the bucket/credential setup).
 */
@Component
@ConditionalOnProperty(prefix = "grilld.packages", name = "storage-provider", havingValue = "s3")
public class S3PackageStorage implements PackageStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3PackageStorage(S3Client s3Client, @Value("${grilld.aws.s3-bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String save(UUID runId, byte[] zipBytes) {
        String key = runId + ".zip";
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/zip").build(),
                RequestBody.fromBytes(zipBytes));
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public byte[] load(String storageUrl) {
        URI uri = URI.create(storageUrl);
        if (!"s3".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not an s3:// storage URL: " + storageUrl);
        }
        String key = uri.getPath().startsWith("/") ? uri.getPath().substring(1) : uri.getPath();
        try (ResponseInputStream<GetObjectResponse> object =
                     s3Client.getObject(GetObjectRequest.builder().bucket(uri.getHost()).key(key).build())) {
            return object.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load package zip from " + storageUrl, e);
        }
    }
}
