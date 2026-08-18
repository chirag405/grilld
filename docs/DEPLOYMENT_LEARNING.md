# Deployment Learning — how Grilld actually got hosted, and why

Phase 10 (`docs/phases/phase-10/`) built the Dockerfiles and the persisted-JWT/
rate-limiting/S3 groundwork, but explicitly left "no managed Postgres / actual
cloud hosting decision" made. This doc picks up exactly there: it's the record
of the real deploy done in this session — which providers, which commands,
every wrong turn taken along the way and *why* it went wrong, so the next
person (or the next session) doesn't have to rediscover any of it.

This is a narrative + reference doc, not a phase folder — no new product code
shipped here, only infrastructure. If a future phase formalizes CI/CD, fold
the durable parts of this into `docs/phases/phase-N/SETUP.md`.

## Final architecture

```
                          ┌─────────────────────┐
   Browser  ───────────▶  │  grilld-frontend     │  Vercel
                          │  (Next.js 16)        │  https://grilld-frontend.vercel.app
                          └──────────┬───────────┘
                                     │ BACKEND_API_URL / NEXT_PUBLIC_BACKEND_URL
                                     ▼
                          ┌──────────────────────┐
                          │  grilld-backend       │  Railway
                          │  (Spring Boot)        │  https://backend-production-db53.up.railway.app
                          └──────────┬───────────┘
                                     │ GRILLD_AI_SERVICE_BASE_URL
                                     │ (Railway private network, no public URL)
                                     ▼
                          ┌──────────────────────┐
                          │  grilld-ai-service     │  Railway
                          │  (LangGraph Platform)  │  ai-service.railway.internal:8080
                          └──────────┬───────────┘
                                     │
                ┌────────────────────┼─────────────────────┐
                ▼                    ▼                     ▼
        ┌───────────────┐   ┌────────────────┐   ┌──────────────────┐
        │ Postgres        │   │ Redis           │   │ Postgres-2qGv     │
        │ (shared —       │   │ (langgraph-api's │   │ (langgraph-api's  │
        │  backend's      │   │  task queue)     │   │  own internal     │
        │  Flyway tables  │   │                  │   │  run/task state,  │
        │  + ai-service's │   │                  │   │  NOT the graph    │
        │  checkpoint      │   │                  │   │  checkpoints)     │
        │  tables)         │   │                  │   │                  │
        └───────────────┘   └────────────────┘   └──────────────────┘
```

Five Railway services (backend, ai-service, Postgres, Redis, Postgres-2qGv)
in one project (`grilld`), plus one Vercel project (`grilld-frontend`).

## Why Railway + Vercel, and what else was considered

- **Frontend → Vercel**: not really a choice — it's a Next.js app, and
  `docs/phases/phase-9/SETUP.md`-era env vars (`BACKEND_API_URL`,
  `NEXT_PUBLIC_BACKEND_URL`) were already written assuming a serverless host
  in front of a separately-hosted backend.
- **Backend + AI service → Railway**: both are long-running Docker containers
  that need to share one Postgres instance — not something Vercel runs.
  Considered and rejected:
  - **Render free tier**: free Postgres **expires 30 days after creation**
    (14-day grace period, then hard-deleted) — unacceptable for a database
    that owns real user/credit data, not just a cache. Free web services also
    sleep after 15 min idle (~1 min cold start) and share a 750 hr/month
    instance-hour budget across the whole workspace — tight for two
    always-on-ish services.
  - **Oracle Cloud Always Free VM**: genuinely permanent free compute, but
    would mean self-managing Docker Compose, a reverse proxy, and TLS by
    hand — more setup than this session had room for. Worth revisiting if
    the Railway trial credit runs out and a truly free option is needed.
  - **Railway Hobby ($5/mo)**: the actual first choice, but the account's
    one-time $5 trial credit had already been spent on a previous session —
    creating a project failed outright with "Your trial has expired." We
    logged into a **second** Railway account (fresh $5/30-day trial) instead
    of paying, per explicit instruction. **This means the current deployment
    is running on a free trial that will eventually need a paid plan (or
    another account) to keep working — it is not a permanent free deploy.**

## Step by step: what happened and why

### 1. Created the Railway project and Postgres

```
railway init --name grilld
railway add --database postgres
```

One project, `production` environment (Railway's default). Postgres is the
shared instance backend and ai-service both point at, per
`docs/phases/phase-10/SETUP.md` §7 ("both services must point at the same
database instance").

### 2. Created empty `backend` and `ai-service` services

```
railway add --service backend
railway add --service ai-service
```

Empty services first, code uploaded separately — this is the CLI-driven
equivalent of "create service, connect source" in the dashboard.

### 3. First deploy attempt: wrong builder (Railpack, not Dockerfile)

`railway up --service backend` picked **Railpack** (Railway's own
auto-detect builder) instead of the repo's hand-written `Dockerfile` — the
deployment's `serviceManifest` showed `"builder": "RAILPACK"`,
`"dockerfilePath": null`. This matters because Phase 10's Dockerfile is a
specific, tested multi-stage build (`amazoncorretto:26` to match the JDK the
project actually compiles against) — letting Railway guess would silently
diverge from what was verified in that phase.

**Fix**: add a `railway.json` to each service's directory forcing the
Dockerfile builder:

```json
{
  "$schema": "https://railway.com/railway.schema.json",
  "build": { "builder": "DOCKERFILE", "dockerfilePath": "Dockerfile" }
}
```

(Now committed at `grilld-backend/railway.json` and
`grilld-ai-service/railway.json`.)

### 4. Second deploy attempt: wrong build context (whole monorepo, not the service dir)

Even after step 3, the build logs showed Railpack analyzing the **repo
root** (`docs/`, all three service folders, root `docker-compose.yml`) —
because `railway up` was run from inside `grilld-backend/`, but the project
had been `init`'d at the repo root, so Railway used the repo root as the
upload context regardless of cwd.

**Fix**: the CLI has a flag for exactly this monorepo case —
`--path-as-root`, run from the repo root:

```
railway up ./grilld-backend --path-as-root --service backend --detach --ci
railway up ./grilld-ai-service --path-as-root --service ai-service --detach --ci
```

This uploads only the named subdirectory as the build context, so
`railway.json`'s relative `dockerfilePath: "Dockerfile"` resolves correctly
and the Dockerfile's own `COPY` paths (`COPY src/ src/`, etc.) make sense.

### 5. Backend build failure: `./mvnw: Permission denied`

```
[build 7/10] RUN ./mvnw -B dependency:go-offline
/bin/sh: line 1: ./mvnw: Permission denied
```

This repo was checked out on Windows, which doesn't track the Unix
executable bit — `mvnw` lost its `+x` permission in the working tree (git's
recorded mode may still say 755, but the actual file Railway's build context
uploaded from this Windows checkout was not executable). The Docker build
context is built from whatever's on disk, not from git's index, so this bit
Railway even though the repo's committed permissions were fine.

**Fix**: `Dockerfile` now does `RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline`
instead of relying on the checked-out file's permission bit. Portable, works
regardless of what OS someone builds from.

### 6. AI service crash #1: missing `REDIS_URI`

```
KeyError: "Config 'REDIS_URI' is missing, and has no default."
```

`grilld-ai-service/docker-compose.yml` (the local-dev compose Phase 10
generated via `langgraph dockerfile --add-docker-compose`) runs a
`langgraph-redis` container alongside `langgraph-api` — Redis is the
platform server's task queue, not optional. Deploying just the `Dockerfile`
in isolation (no compose) meant nothing supplied `REDIS_URI`.

**Fix**: `railway add --database redis`, then set
`REDIS_URI=redis://default:<password>@redis.railway.internal:6379` on
`ai-service` (Railway's private-network hostname — no public exposure
needed, backend and ai-service and their datastores all reach each other
over Railway's internal mesh).

### 7. AI service crash #2: `POSTGRES_URI` needed, and it's *not* `DATABASE_URL`

The same compose file also runs a **second**, separate Postgres
(`langgraph-postgres`, `pgvector/pgvector:pg16`) purely for the LangGraph
Platform server's own internal run/task bookkeeping — completely distinct
from `DATABASE_URL`, which is the *shared* grilld Postgres that
`get_graph()`'s `AsyncPostgresSaver` uses to checkpoint actual graph state
(interview turns, generation runs). The Phase 10 docstring in
`docker-compose.yml` calls this out, but `docs/phases/phase-10/SETUP.md`'s
env var reference never mentions `POSTGRES_URI` at all — a real gap, since
that doc predates any actual cloud deploy.

**Fix**: provisioned a **second** Railway Postgres
(`railway add --database postgres`, landed as service `Postgres-2qGv`) and
set `POSTGRES_URI=postgresql://postgres:<password>@postgres-2qgv.railway.internal:5432/railway`
on `ai-service`. Did *not* reuse the shared Postgres for this — didn't want
LangGraph Platform's internal task-queue tables potentially colliding with
either the backend's Flyway-owned tables or the checkpoint tables in the
same database/schema. Two Postgres instances on Railway, one shared
(business + checkpoints), one dedicated (platform internal state).

### 8. AI service crash #3: LangSmith license check

```
ValueError: License verification failed. Please ensure proper configuration:
- For local development, set a valid LANGSMITH_API_KEY for an account with
  LangGraph Cloud access...
```

Exactly what `docs/phases/phase-10/SETUP.md` §4 warned about: the
`langchain/langgraph-api` base image checks for a license at container
**startup**, even for self-hosted use, independent of whether the Anthropic
key is valid.

**Fix**: set `LANGSMITH_API_KEY` on `ai-service`. Confirmed in the runtime
logs afterward: `Successfully submitted metadata to LangSmith instance`.

### 9. Silent trap: backend defaults to a stub AI client

`HttpAiServiceClient` (the real HTTP client that calls `grilld-ai-service`)
is annotated `@Profile("python-ai-service")` — **not** the default.
`StubAiServiceClient` (`@Profile("!python-ai-service")`) is what runs
otherwise. Nothing about this fails loudly: the backend boots fine, every
endpoint responds, and the AI features just quietly return fake/stubbed
output instead of calling the real service. This is easy to miss because
there's no error, just wrong behavior.

**Fix**: set `SPRING_PROFILES_ACTIVE=python-ai-service` on `backend`.
Confirmed in the boot logs: `The following 1 profile is active:
"python-ai-service"`.

### 10. Wiring backend → ai-service

`HttpAiServiceClient` reads `grilld.ai-service.base-url`
(env: `GRILLD_AI_SERVICE_BASE_URL`, Spring's relaxed-binding form of the
property name — dots and hyphens both become underscores, everything
uppercased).

Needed the ai-service container's actual internal listen port. Local
`docker-compose.yml` maps `8123:8000`, suggesting port 8000 inside the
container — but Railway injects its own `PORT` env var and `uvicorn`
respected it: the runtime logs showed
`Uvicorn running on http://0.0.0.0:8080`, **not** 8000. Found this by
reading the actual startup logs rather than assuming the local compose
port carried over.

**Fix**: `GRILLD_AI_SERVICE_BASE_URL=http://ai-service.railway.internal:8080`
— Railway's private-network DNS name for the service, port 8080 (whatever
Railway's `PORT` happened to be for that deploy). No public domain was
created for `ai-service` — nothing outside the Railway project needs to
reach it directly; only `backend` calls it, over the private network.

### 11. Credentials that had to come from outside this session

None of these can be generated or discovered from the repo — they were
either pasted in directly or found in a local, gitignored file:

| Credential | Source |
|---|---|
| `LANGSMITH_API_KEY` | pasted directly by the user |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | pasted directly by the user |
| `ANTHROPIC_API_KEY`, `TAVILY_API_KEY` | found in `grilld-ai-service/.env` (gitignored, local dev file) |
| `JWT_SIGNING_KEY_JWK` | **generated locally**, not sourced externally — see below |

`GRILLD_AI_MODEL` was overridden from the `.env` file's
`groq:llama-3.3-70b-versatile` (explicitly dev/testing-only per
`docs/phases/phase-10/SETUP.md` §9 — "not something to run production
traffic on") to the production default `anthropic:claude-sonnet-4-6`.

### 12. Generating the persisted JWT signing key

Per `docs/phases/phase-10/SETUP.md` §6, run once per environment:

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw -q exec:java -Dexec.mainClass=com.grilld.backend.tools.JwtKeyGenerator
```

Captured straight into a shell variable and set as the Railway secret in the
same step, rather than printing it to a terminal/file first — it's a private
RSA key; the fewer places it transits in plaintext, the better. Confirmed
working from the boot log: `Loaded persisted JWT signing key (kid=...) -
issued tokens survive restarts.`

### 13. Domains

```
railway domain --service backend
```
→ `https://backend-production-db53.up.railway.app` (Railway-generated,
free `*.up.railway.app` subdomain — no custom domain purchased).

No domain generated for `ai-service` (internal-only, see §10).

### 14. Frontend → Vercel

```
cd grilld-frontend
vercel link --yes
vercel env add BACKEND_API_URL production        # https://backend-production-db53.up.railway.app
vercel env add NEXT_PUBLIC_BACKEND_URL production # same value
vercel --prod --yes
```

Deployed clean on the first attempt — Next.js on Vercel is the
well-trodden path, no surprises. Landed at
`https://grilld-frontend.vercel.app`.

### 15. Closing the loop: `FRONTEND_BASE_URL`

Backend's `SecurityConfig` CORS policy and `OAuth2LoginSuccessHandler`'s
post-login redirect both depend on `FRONTEND_BASE_URL` (default
`http://localhost:3000` otherwise). Once the real Vercel URL existed:

```
railway variables --service backend --set "FRONTEND_BASE_URL=https://grilld-frontend.vercel.app"
railway redeploy --service backend --yes
```

## What's deployed vs. what's still missing

**Working now** (all three services healthy, verified via `/actuator/health`
returning 200 on the backend and a 200 on the frontend root):
- Frontend (Vercel) ↔ Backend (Railway) ↔ AI service (Railway), all wired
- Shared Postgres (business tables + LangGraph checkpoints)
- Dedicated Postgres for LangGraph Platform's internal state
- Redis for LangGraph Platform's task queue
- Persisted JWT signing key (restarts don't log everyone out)
- Google OAuth, Anthropic, Tavily, LangSmith all configured

**Not done** — deliberately out of scope for this pass, same spirit as
Phase 10's own "what's still not done" section:
- **Google Cloud Console redirect URI**: still needs
  `https://backend-production-db53.up.railway.app/login/oauth2/code/google`
  added as an authorized redirect URI in the Google Cloud project (Console
  access wasn't available in this session — see
  `docs/phases/phase-10/SETUP.md` §1, step 3). Login will not work in
  production until this is added.
- **Lemon Squeezy** (billing) — app works fine without it, checkout/webhook
  endpoints just aren't live yet.
- **S3 package storage** — still `PACKAGE_STORAGE_PROVIDER=local` (default);
  fine for a single backend instance, packages won't survive a redeploy.
- **Custom domains** — both services are on their platform-issued
  subdomains (`*.railway.app`, `*.vercel.app`).
- **Railway billing** — running on a second account's free trial (see "Why
  Railway + Vercel" above). This *will* need a paid plan, or another
  account, before the trial credit runs out. Nothing paid has been set up.
- **No CI/CD** — every deploy in this doc was a manual `railway up` /
  `vercel --prod` from a local checkout. A future phase could wire GitHub
  auto-deploys (`railway service source connect --repo ... --branch main`)
  if that's wanted.

## Quick reference: redeploying later

```bash
# Backend (from repo root)
railway up ./grilld-backend --path-as-root --service backend --detach --ci

# AI service
railway up ./grilld-ai-service --path-as-root --service ai-service --detach --ci

# Frontend
cd grilld-frontend && vercel --prod --yes

# Check what's actually running
railway deployment list --service backend --json
railway logs -d --service backend        # runtime logs
railway logs -b --service backend        # build logs
```

`--path-as-root` is not optional for the two Railway services — omitting it
re-triggers the whole-monorepo build-context bug from step 4.
