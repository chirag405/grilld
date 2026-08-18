# Grilld — Deployment Setup

Every credential and account needed to run Grilld in production, in one
place. Each phase's own `docs/phases/phase-N/SETUP.md` covers the same
ground incrementally as it was introduced; this file is the consolidated,
deploy-oriented version — what you need, where to get it, and which of the
three services (`grilld-backend`, `grilld-ai-service`, `grilld-frontend`)
each one goes into.

Nothing here is optional-but-nice unless explicitly marked **(optional)**.
Everything else blocks either boot or a real feature working in production.

## Quick reference

| # | Credential | Blocks | Get it from |
|---|---|---|---|
| 1 | Google OAuth client ID/secret (production) | Login, entirely | [Google Cloud Console](https://console.cloud.google.com/) |
| 2 | Anthropic API key | Every AI feature (interview, generation) | [console.anthropic.com](https://console.anthropic.com/) |
| 3 | Tavily API key | Market/competition research, tech/infra agents | [tavily.com](https://tavily.com/) |
| 4 | LangSmith API key | Running the AI service container at all | [smith.langchain.com](https://smith.langchain.com/) |
| 5 | Lemon Squeezy store + webhook secret | Selling credits | [lemonsqueezy.com](https://lemonsqueezy.com/) |
| 6 | A persisted JWT signing key | Sessions surviving a restart | Self-generated (no account) |
| 7 | Managed Postgres | Everything — it's the database | Any Postgres host (Railway, Supabase, RDS, Neon, …) |
| 8 | S3-compatible object storage **(optional)** | Package zips surviving a redeploy | AWS S3, Cloudflare R2, or Wasabi |
| 9 | Groq API key **(optional)** | A free fallback model for testing, not needed in production | [console.groq.com](https://console.groq.com/) |

---

## 1. Google OAuth — login

**Where it's used:** `grilld-backend` (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`).

The dev credentials from `docs/phases/phase-1/SETUP.md` only work for
`localhost` redirects and cap out at 100 test users while the app is in
Google's "Testing" publishing status — neither is fit for real users.

1. [Google Cloud Console](https://console.cloud.google.com/) → create a
   project (or reuse the dev one).
2. **APIs & Services → OAuth consent screen** — fill in the real app name,
   support email, and your production domain. Submit for verification if you
   want to move out of "Testing" status (Google reviews it; can take days —
   start this early). While still in Testing, you can add specific Google
   accounts as test users instead of waiting on verification.
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**,
   type **Web application**.
   - Authorized redirect URI: `https://<your-backend-domain>/login/oauth2/code/google`
     — this exact path, Spring Security's default callback for a
     registration named `google`. Add both your staging and production
     backend domains here if they differ.
4. Copy the **Client ID** and **Client Secret** → set as
   `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` on the backend deployment.
5. Set `grilld.frontend.base-url` (env: `FRONTEND_BASE_URL`) on the backend
   to your real frontend domain — this is where `OAuth2LoginSuccessHandler`
   redirects after a successful login, and what `SecurityConfig`'s CORS
   policy allows to call the API.

## 2. Anthropic API key — every AI feature

**Where it's used:** `grilld-ai-service` (`ANTHROPIC_API_KEY`).

All LLM calls happen in the AI service, never in the backend. Nothing AI-related
works without this.

1. [console.anthropic.com](https://console.anthropic.com/) → **API Keys → Create Key**.
2. Costs real money per call. Add billing before real traffic.
3. `GRILLD_AI_MODEL` (default `anthropic:claude-sonnet-4-6`) picks the model —
   raise or lower it per environment without a code change.

## 3. Tavily API key — live web research

**Where it's used:** `grilld-ai-service` (`TAVILY_API_KEY`).

The Market Analyst, Competition Analyst, Tech Architect, and Infra Agent all
do live web search during generation. Without this, generation runs
involving those agents fail.

1. [tavily.com](https://tavily.com/) → sign up → **Dashboard → API Keys**.
2. Free tier: 1,000 searches/month — likely enough for early production
   traffic; watch usage and upgrade if generation volume grows.

## 4. LangSmith API key — required just to run the AI service

**Where it's used:** `grilld-ai-service` container (`LANGSMITH_API_KEY`).

Separate from Anthropic — the `langchain/langgraph-api` base image the AI
service's Dockerfile builds on checks for a license/API key **at container
startup**, even for self-hosted "Lite" use. Without this, the AI service
container will not start at all, regardless of whether your Anthropic key
is valid.

1. [smith.langchain.com](https://smith.langchain.com/) → sign up (free tier exists).
2. **Settings → API Keys → Create API Key**.
3. Set `LANGSMITH_API_KEY` on the AI service deployment.

## 5. Lemon Squeezy — selling credits

**Where it's used:** `grilld-backend` (`LEMONSQUEEZY_WEBHOOK_SECRET`,
`LEMONSQUEEZY_STORE_SUBDOMAIN`, `LEMONSQUEEZY_STARTER_VARIANT_ID`,
`LEMONSQUEEZY_TOPUP_VARIANT_ID`).

Without these four set, the app boots fine and every non-billing feature
works — `LemonSqueezyCheckoutService`/`LemonSqueezySignatureVerifier` only
throw the moment a checkout URL or webhook is actually requested. Needed
before you can take real payments.

1. Create a store at [lemonsqueezy.com](https://lemonsqueezy.com/). It
   starts in **Test mode** — switch to live mode when you're ready to take
   real payments (test-mode orders still fire real webhooks, so you can
   fully verify the flow before going live).
2. **Store subdomain**: Settings → General, the `{subdomain}` in
   `https://{subdomain}.lemonsqueezy.com` → `LEMONSQUEEZY_STORE_SUBDOMAIN`.
3. Create two one-time-purchase products (`product-and-architecture.md` §10's
   MVP tiers):
   - **Starter** — $12, grants 60 credits
   - **Top-up** — $10, grants 50 credits
   - Each product's **variant id** (an integer — visible in the product's
     dashboard URL, or via the Products API; this, not the product id, is
     what the webhook actually matches against) → `LEMONSQUEEZY_STARTER_VARIANT_ID`
     / `LEMONSQUEEZY_TOPUP_VARIANT_ID`.
4. **Webhook**: Settings → Webhooks → create one pointing at
   `https://<your-backend-domain>/api/v1/billing/webhooks/lemonsqueezy`,
   subscribed to at least `order_created`. Pick any signing secret string
   (6–40 characters) when creating it → `LEMONSQUEEZY_WEBHOOK_SECRET`.

## 6. Persisted JWT signing key

**Where it's used:** `grilld-backend` (`JWT_SIGNING_KEY_JWK`). No account needed — self-generated.

Without this, the backend generates a fresh signing key every time it
boots, which logs out every user on every restart/redeploy — fine for local
dev, not for production.

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw -q exec:java -Dexec.mainClass=com.grilld.backend.tools.JwtKeyGenerator
```

Prints a single-line JSON RSA JWK (private key included) to stdout. Store it
as the `JWT_SIGNING_KEY_JWK` secret. Generate a **different** key per
environment — never share one between staging and production, never commit
it, never log it.

## 7. Managed Postgres

**Where it's used:** `grilld-backend` (`DB_URL`, `DB_USER`, `DB_PASSWORD`) and
`grilld-ai-service` (`DATABASE_URL`) — **both services must point at the
same database instance**; they own different schemas within it
(`grilld-backend` owns the business tables via Flyway, `grilld-ai-service`
owns only its own LangGraph checkpoint tables).

Local dev uses the repo root's `docker-compose.yml` (a throwaway container).
Production needs a real, backed-up Postgres instance — any managed provider
works (Railway, Supabase, Neon, AWS RDS, etc.); nothing in the code is
provider-specific.

1. Provision a Postgres 16+ instance.
2. Set on `grilld-backend`: `DB_URL=jdbc:postgresql://<host>:<port>/<db>`,
   `DB_USER`, `DB_PASSWORD`. Flyway migrates the schema automatically on boot.
3. Set on `grilld-ai-service`: `DATABASE_URL=postgresql://<user>:<password>@<host>:<port>/<db>`
   (same instance, plain `postgresql://` URL rather than JDBC form).
4. Confirm your provider's backup policy — nothing in this codebase backs up
   the database itself.

## 8. S3-compatible object storage (optional)

**Where it's used:** `grilld-backend` (`PACKAGE_STORAGE_PROVIDER=s3`,
`AWS_S3_BUCKET`, `AWS_REGION`, optionally `AWS_S3_ENDPOINT`,
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`).

Not required to launch — the default (`PACKAGE_STORAGE_PROVIDER=local`)
writes package zips to local disk, which works fine for a single backend
instance. Needed once you run more than one backend instance, or want
generated packages to survive a redeploy.

1. Create a private bucket on AWS S3, Cloudflare R2, or Wasabi (no public
   access needed — the backend streams bytes through itself, never hands
   out a direct bucket URL).
2. Set `PACKAGE_STORAGE_PROVIDER=s3`, `AWS_S3_BUCKET=<bucket>`,
   `AWS_REGION=<region>` (real AWS: e.g. `us-east-1`; Cloudflare R2: `auto`).
3. Credentials:
   - Real AWS with an IAM role attached to the compute (ECS task role, EC2
     instance profile): leave `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`
     unset — picked up automatically.
   - Everything else (R2, Wasabi, or AWS without a role): set both explicitly.
4. Non-AWS providers only: set `AWS_S3_ENDPOINT` to that provider's
   S3-compatible endpoint (e.g. R2's `https://<account-id>.r2.cloudflarestorage.com`).
   Leave unset for real AWS S3.

## 9. Groq API key (optional, dev/testing only)

**Where it's used:** `grilld-ai-service` (`GROQ_API_KEY`).

A free, rate-limited (not credit-limited) alternative to Anthropic for local
iteration — not something to run production traffic on (unreliable
tool-calling for the research agents). Get one at
[console.groq.com](https://console.groq.com/) if you want cheaper local
testing; skip it entirely for a production deployment.

---

## Per-service environment variable reference

### `grilld-backend`

| Variable | Required | Notes |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Yes | §7 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Yes | §1 |
| `FRONTEND_BASE_URL` | Yes | Your real frontend domain — OAuth redirect target + CORS allow-origin |
| `JWT_SIGNING_KEY_JWK` | Strongly recommended | §6 — without it, every restart logs everyone out |
| `LEMONSQUEEZY_WEBHOOK_SECRET`, `LEMONSQUEEZY_STORE_SUBDOMAIN`, `LEMONSQUEEZY_STARTER_VARIANT_ID`, `LEMONSQUEEZY_TOPUP_VARIANT_ID` | For billing | §5 — app boots and works fine without these, billing endpoints don't |
| `PACKAGE_STORAGE_PROVIDER`, `AWS_*` | Optional | §8 |
| `RATELIMIT_*` | Optional | Tune per-tier rate limits; sane defaults ship as-is |
| `SERVER_PORT` | Optional | Defaults to 8080 |

### `grilld-ai-service`

| Variable | Required | Notes |
|---|---|---|
| `ANTHROPIC_API_KEY` | Yes | §2 |
| `TAVILY_API_KEY` | Yes | §3 |
| `LANGSMITH_API_KEY` | Yes | §4 — container won't start without it |
| `DATABASE_URL` | Yes | §7 — same Postgres instance as the backend |
| `GRILLD_AI_MODEL` | Optional | Defaults to `anthropic:claude-sonnet-4-6` |
| `GROQ_API_KEY` | Optional | §9, dev only |

### `grilld-frontend`

| Variable | Required | Notes |
|---|---|---|
| `BACKEND_API_URL` | Yes | Backend's real origin, server-side only |
| `NEXT_PUBLIC_BACKEND_URL` | Yes | Same value, exposed to the browser for the "Sign in with Google" link only — never put a secret behind a `NEXT_PUBLIC_` variable |

---

## Suggested order

1. **Managed Postgres (§7)** first — everything else needs somewhere to write.
2. **Google OAuth (§1)** + **JWT key (§6)** — get login working end to end.
3. **Anthropic (§2)** + **Tavily (§3)** + **LangSmith (§4)** — get the AI service actually running.
4. **Lemon Squeezy (§5)** — only once you're ready to take real payments; everything else works without it.
5. **S3 storage (§8)** — only once you're running more than one backend instance.
