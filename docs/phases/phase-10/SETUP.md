# Phase 10 — Setup

Everything in this phase is **off or ephemeral by default** - local dev and
the automated test suite need nothing new. The items below are what a real
deployment needs to configure to get the hardened behavior.

## Persisted JWT signing key

Generate one (needs the project already built, i.e. `./mvnw compile` run at
least once):

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw -q exec:java -Dexec.mainClass=com.grilld.backend.tools.JwtKeyGenerator
```

Prints a single-line JSON RSA JWK (private key included). Store it as the
`JWT_SIGNING_KEY_JWK` secret for that environment - never commit it, never
log it, generate a **different** one per environment (staging and production
should not share a signing key).

Leaving it unset keeps the current ephemeral behavior (a fresh key every
boot, all sessions invalidated on restart) - fine for local dev, not for a
real deployment that restarts.

## Rate limiting

No setup required - on by default (`grilld.ratelimit.enabled=true` unless
overridden). Tune limits per environment via `RATELIMIT_*` env vars if the
defaults (see `README.md`'s table) are wrong for real traffic; see
`application.properties` for the full list of `RATELIMIT_*` names.

## S3-compatible package storage

Only needed once more than one backend instance runs, or a redeploy needs to
preserve previously-generated packages. Leaving `PACKAGE_STORAGE_PROVIDER`
unset keeps the current local-disk behavior.

To switch to S3 (real AWS, or any S3-compatible provider):

1. Create a bucket (private, no public access needed - `PackageController`
   streams bytes through the backend, it never hands out a direct bucket URL).
2. Set:
   - `PACKAGE_STORAGE_PROVIDER=s3`
   - `AWS_S3_BUCKET=<bucket name>`
   - `AWS_REGION=<region>` (real AWS: e.g. `us-east-1`; R2: `auto`)
3. Credentials - two options:
   - Real AWS with an IAM role attached to the compute (ECS task role, EC2
     instance profile, etc.): leave `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
     unset - the SDK's default credential chain picks up the role automatically.
   - Everything else (R2, Wasabi, MinIO, or AWS without a role): set
     `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` explicitly.
4. Non-AWS providers only: set `AWS_S3_ENDPOINT` to that provider's S3-compatible
   endpoint (e.g. Cloudflare R2's `https://<account-id>.r2.cloudflarestorage.com`).
   Leave unset for real AWS S3.

## Docker images

Build locally to try either image without deploying anywhere:

```powershell
cd grilld-backend
docker build -t grilld-backend:local .
docker run --rm -p 8080:8080 `
  -e DB_URL="jdbc:postgresql://host.docker.internal:5432/grilld" `
  -e DB_USER="grilld" -e DB_PASSWORD="grilld_dev_only" `
  -e GOOGLE_CLIENT_ID="..." -e GOOGLE_CLIENT_SECRET="..." `
  grilld-backend:local
```

(`host.docker.internal` reaches the host's Postgres from inside the
container on Docker Desktop - point `DB_URL` at a real managed Postgres
instead for an actual deployment.)

```powershell
cd grilld-ai-service
docker build -t grilld-ai-service:local .
```

Building the AI service image needs nothing beyond Docker - it only installs
dependencies. **Running** it (`docker compose up`, or the built image
directly) is a different story:

### LangSmith API key (required to run the AI service container - not optional)

The `langchain/langgraph-api` base image checks for a license/API key at
startup, even for self-hosted "Lite" use - it will not serve requests
without one. This is a real external account the user needs to create;
nothing here can substitute for it.

1. Sign up at https://smith.langchain.com (a free tier exists).
2. Create an API key (Settings → API Keys).
3. Add `LANGSMITH_API_KEY=<key>` to `grilld-ai-service/.env` (already
   gitignored - never commit it) or export it before `docker compose up`.

Once set:

```powershell
cd grilld-ai-service
docker compose up
```

starts the full stack (LangGraph API server + its own internal Postgres/Redis),
listening on `http://localhost:8123`, checkpointing graph state into the
shared grilld Postgres via `DATABASE_URL` (defaults to
`host.docker.internal:5432` for local Docker Desktop use - override for a
real deployment).
