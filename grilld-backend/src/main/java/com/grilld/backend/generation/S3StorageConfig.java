package com.grilld.backend.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * Builds the {@link S3Client} used by {@link S3PackageStorage}, only when
 * package storage is actually configured for S3 (see LocalFilesystemPackageStorage
 * for the zero-setup default). One client works against real AWS S3 or any
 * S3-compatible provider (Cloudflare R2, Wasabi, self-hosted MinIO) - an
 * endpoint override plus path-style access is the only thing that differs,
 * both left unset for real AWS. See docs/phases/phase-10/SETUP.md.
 */
@Configuration
@ConditionalOnProperty(prefix = "grilld.packages", name = "storage-provider", havingValue = "s3")
public class S3StorageConfig {

    @Bean
    public S3Client s3Client(
            @Value("${grilld.aws.region:us-east-1}") String region,
            @Value("${grilld.aws.s3-endpoint:}") String endpointOverride,
            @Value("${grilld.aws.access-key-id:}") String accessKeyId,
            @Value("${grilld.aws.secret-access-key:}") String secretAccessKey) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));

        if (!endpointOverride.isBlank()) {
            // R2/Wasabi/MinIO all need their own endpoint and path-style bucket
            // addressing (bucket.example.com vs example.com/bucket) instead of
            // AWS's virtual-hosted-style default.
            builder.endpointOverride(URI.create(endpointOverride)).forcePathStyle(true);
        }
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        // If neither key is set, S3Client falls back to its default credential chain
        // (env vars, instance/task role, etc.) - the right choice for real AWS deploys
        // using IAM roles instead of long-lived keys.

        return builder.build();
    }
}
