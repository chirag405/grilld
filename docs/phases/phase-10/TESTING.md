# Phase 10 — Testing gate

## Automated checks

- [x] From `grilld-backend/`, run `./mvnw test` with JDK 26 and Docker available.
- [x] Result verified on 2026-08-18: **68 tests, 0 failures, 0 errors, 0 skipped**
  (56 pre-existing + 5 `JwtConfigTest` + 4 rate-limit tests + 3 `S3PackageStorageTest`).
- [x] `JwtConfigTest` proves: blank signing key still falls back to a working
  ephemeral key; a persisted JWK is loaded and actually signs/verifies tokens;
  a token issued before a restart is still accepted by a fresh `JwtConfig`
  built from the same persisted key (the actual bug this fixes); a malformed
  or public-only JWK fails construction fast rather than silently falling
  back to an insecure ephemeral key.
- [x] `RateLimitInterceptorTest` proves the token-bucket logic in isolation:
  capacity is enforced then a `429` with the right body follows, separate
  users get separate buckets, and an unauthenticated caller falls back to a
  per-IP bucket.
- [x] `RateLimitMvcIntegrationTest` proves the other half - that
  `RateLimitConfig` actually wires the interceptor into the real Spring MVC
  dispatch chain for a real endpoint (`POST /api/v1/sessions`), with rate
  limiting deliberately re-enabled via `@TestPropertySource` since it's off
  suite-wide by default.
- [x] `S3PackageStorageTest` proves `S3PackageStorage` against a real S3 API
  (Testcontainers MinIO, not a mock): a saved zip loads back byte-identical,
  a missing key throws `NoSuchKeyException`, a non-`s3://` URL is rejected.
- [x] Nothing changed on the Python side this phase.

## Manual / infrastructure checks

- [x] `docker build` succeeds for both `grilld-backend/Dockerfile` and
  `grilld-ai-service/Dockerfile`.
- [x] **Real container run, not just a successful build** - verified
  2026-08-18: started `grilld-backend:test` against the real local
  `grilld-postgres` container (`DB_URL=jdbc:postgresql://host.docker.internal:5432/grilld`),
  confirmed in the container logs that Flyway validated the existing schema
  and Tomcat started, `GET /actuator/health` returned `{"status":"UP"}` with
  HTTP 200, and an unauthenticated `GET /api/v1/billing/balance` correctly
  returned 401 - proving the JAR, JDK, and Spring Security config all work
  from inside the built image, not just on the host.
- [ ] `docker compose up` for `grilld-ai-service` - **not run**, needs a real
  `LANGSMITH_API_KEY` (see `SETUP.md`) that doesn't exist in this environment.
  Flagged, not faked.
- [ ] A real S3 bucket (AWS, R2, or otherwise) - **not exercised**;
  `S3PackageStorageTest`'s MinIO run is the closest available substitute
  without a real cloud account. Point `PACKAGE_STORAGE_PROVIDER=s3` at a real
  bucket and confirm a real generation run's package downloads correctly
  once such an account exists.
- [ ] A persisted JWT key surviving an actual backend restart in a real
  (non-Docker-Desktop-localhost) deployment - the restart-survival behavior
  itself is proven by `JwtConfigTest`; this item is only about confirming it
  in a real deployed environment once one exists.

## Gate result

The automated gate passes in full (68/68). The infrastructure items that
need a real cloud account (LangSmith, a real S3 bucket) are explicitly
flagged rather than claimed - they're the same category of "needs the user's
own external action" already established for Google OAuth and Lemon Squeezy
in earlier phases.
