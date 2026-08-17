# Phase 8 — Setup

No new setup. Phase 8 introduces no new dependency, credential, or configuration - it's application code only, layered on everything Phases 1-7 already set up. See `docs/phases/phase-7/SETUP.md` (and its own chain back through Phase 1) for the full prerequisite list.

## Verification

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw test
```

Docker is required (Testcontainers Postgres). No Lemon Squeezy, Google, or AI-service credentials are needed for the automated gate.
