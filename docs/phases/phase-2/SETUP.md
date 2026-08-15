# Phase 2 — Setup

No new credentials or services beyond Phase 1 (see `docs/phases/phase-1/SETUP.md` for Java/Docker/JAVA_HOME notes and Google OAuth setup, which this phase also relies on for authenticated endpoints).

## Running Phase 2 locally

Same as Phase 1:

```powershell
docker compose up -d postgres
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
$env:SPRING_PROFILES_ACTIVE = "local"
./mvnw spring-boot:run
```

`POST /api/v1/sessions` and `POST /api/v1/sessions/{id}/answer` are behind auth like everything else under `/api/v1/**` - you need a real JWT from the Google login flow (Phase 1's SETUP.md) to call them manually via a tool like curl or Postman.

## Running the automated tests

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw test
```

No Google credentials needed - `SessionFlowIntegrationTest` exercises the full session/turn flow (including auth) by generating a JWT directly through the same `TokenService`/`UserService` beans a real login uses, so the entire pipeline is covered by an automated, repeatable test rather than requiring manual login every time it's checked.
