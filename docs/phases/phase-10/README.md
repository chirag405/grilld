# Phase 10 — Production hardening

Everything in Phases 1-9 makes the product *work*. Nothing in them makes it
safe or possible to actually deploy: the JWT signing key was ephemeral, there
was no cost gate finer-grained than "run out of credits," package zips only
ever lived on local disk, and neither service had a Dockerfile. Phase 10 closes
those specific gaps - it adds no product feature, only deployability and
abuse-resistance, laid over the existing backend and AI service unchanged.

## 1. Persisted JWT signing key

`JwtConfig` (`grilld-backend/src/main/java/com/grilld/backend/auth/JwtConfig.java`)
generated a fresh RSA keypair in memory on every boot - fine for a single dev
process that never restarts, a real problem the moment the backend runs as a
redeployable service (every deploy would silently log everyone out).

- `grilld.jwt.signing-key-jwk` (env: `JWT_SIGNING_KEY_JWK`) - unset (default):
  unchanged ephemeral behavior, zero setup for local dev and every existing test.
  Set: a persisted RSA JWK (private key included) is parsed and used instead,
  so the same key - and therefore the same valid tokens - survives a restart.
- `com.grilld.backend.tools.JwtKeyGenerator` - a `main()`-only CLI (not a Spring
  bean, never runs as part of the app itself) that prints a fresh JWK to stdout.
  Run once per environment; the output becomes that environment's secret.

## 2. Per-user rate limiting

Credits are the only existing cost gate, and they're checked once, at
generation time - a bug or an abusive client could still hammer the
per-turn interview endpoint or loop `checkout-url` indefinitely before ever
running out of credits.

`common/ratelimit/RateLimitInterceptor.java` is a `HandlerInterceptor`
wrapping a bucket4j token bucket, keyed by JWT subject (falls back to remote
IP if unauthenticated, though every guarded route requires auth in practice).
`RateLimitConfig` (a `WebMvcConfigurer`) registers three separately-tuned
instances against the specific mutating routes that cost something, not
broad wildcards that would also throttle reads:

| Tier | Routes | Default limit |
|---|---|---|
| `interview` | `POST /api/v1/sessions`, `.../answer`, `.../scale-tier`, `.../force-conclude` | 20 / 60s |
| `generation` | `POST /api/v1/sessions/*/generate` | 5 / 3600s |
| `billing` | `GET /api/v1/billing/checkout-url` | 10 / 60s |

All three are overridable via `grilld.ratelimit.*` properties (see
`application.properties`). A rejected request gets a real `429` with the same
`ErrorResponse` shape every other error uses, plus `Retry-After` and
`X-RateLimit-Remaining` headers.

Buckets live in an in-memory `ConcurrentHashMap` per interceptor instance -
this limits per backend instance, not cluster-wide. That's the correct
tradeoff for a single instance (today's deployment); once more than one
instance runs behind a load balancer, swap in bucket4j's Redis-backed
`ProxyManager` (same `Bucket` API, different construction) instead of the
in-memory map - not needed until that day comes.

Rate limiting is **disabled by default in the test suite**
(`grilld.ratelimit.enabled=false`, set via a `systemPropertyVariables` block
in `pom.xml`'s surefire config) because most existing tests deliberately hit
these endpoints many times per method (a full interview loop, etc.) and don't
care about rate limiting. `RateLimitMvcIntegrationTest` explicitly turns it
back on via `@TestPropertySource` to prove the real MVC wiring still works.

## 3. S3-compatible package storage

`LocalFilesystemPackageStorage` (Phase 6) writes zips to a directory on local
disk - it doesn't survive a redeploy and doesn't work once more than one
backend instance exists (each has its own disk). `S3PackageStorage`
(`generation/S3PackageStorage.java`) is a drop-in second implementation of the
same `PackageStorage` interface, built on AWS SDK v2, that works against real
AWS S3 or any S3-compatible provider (Cloudflare R2, Wasabi, self-hosted
MinIO) via an optional endpoint override.

`grilld.packages.storage-provider` (env: `PACKAGE_STORAGE_PROVIDER`) picks
which one is active - `local` (default, unchanged behavior) or `s3`. Both
implementations carry `@ConditionalOnProperty` so exactly one `PackageStorage`
bean ever exists; `S3StorageConfig` builds the `S3Client` bean only when
`storage-provider=s3`, from `grilld.aws.*` properties (region, bucket,
optional endpoint override + path-style access for non-AWS providers,
optional static credentials - falls back to the SDK's default credential
chain, the right choice for IAM-role-based AWS deployments, when unset).

Storage URLs are `s3://bucket/key`, mirroring the existing `file://...` shape
`LocalFilesystemPackageStorage` already returns - `PackageController` doesn't
need to know or care which one produced the URL it's loading.

## 4. Docker images for both services

**`grilld-backend/Dockerfile`** - hand-written multi-stage build:
- Build stage: `amazoncorretto:26` (matches the JDK this project actually
  compiles and tests against - `pom.xml` targets release 26), runs the Maven
  wrapper, then extracts Spring Boot's layered jar
  (`java -Djarmode=tools -jar application.jar extract --layers`).
- Runtime stage: `amazoncorretto:26-al2023-headless` (JRE only, no build
  tools), copies the four extracted layers in dependency-stability order
  (`dependencies` → `spring-boot-loader` → `snapshot-dependencies` →
  `application`) so a source-only change only invalidates the last, smallest
  layer. Runs as a fixed non-root UID (`1001:1001`).

**`grilld-ai-service/Dockerfile` + `docker-compose.yml`** - generated with
`langgraph dockerfile --add-docker-compose`, not hand-rolled: `app.py`'s
`get_graph()` factory is already written to the exact `RunnableConfig` /
`ServerRuntime` signature the LangGraph Platform server itself requires (see
that file's own docstring), so running this service behind the real
`langchain/langgraph-api` image is the packaging the code was already built
for, not a guess made at this phase. The generated compose stack's own
`langgraph-postgres`/`langgraph-redis` services are the platform server's
internal run/task state only - a manually-added `DATABASE_URL` environment
variable on the `langgraph-api` service points at the *shared* grilld
Postgres instead (via `host.docker.internal` for local Docker Desktop use),
which is what `get_graph()`'s `AsyncPostgresSaver` actually checkpoints graph
state into.

Both images were verified as real, running containers, not just successful
`docker build`s - see `TESTING.md`.

## What's still not done (deliberately out of scope for this phase)

- No CI pipeline (`.github/workflows`) runs any of this automatically yet.
- No managed Postgres / actual cloud hosting decision has been made - these
  Dockerfiles are what a real deploy would use, not a deploy itself.
- The AI service's `langgraph-api` container needs a `LANGSMITH_API_KEY` to
  even start (checked at boot, even for self-hosted "Lite" use) - not
  something this phase can supply. See `SETUP.md`.
- No structured/JSON logging or external log aggregation - `GlobalExceptionHandler`
  already avoids leaking stack traces in error *responses*, but console logs
  are still plain text.
