# Phase 1 — Foundation

Repo scaffolding, the Spring Boot skeleton, the full canonical Postgres schema, and Google OAuth2 + JWT authentication. This is the base everything else (Phase 2 onward) is built on top of.

## What this phase added

```
/
├── docker-compose.yml          — local Postgres (postgres:18-alpine)
└── grilld-backend/
    ├── pom.xml                  — Maven build + dependencies
    └── src/main/java/com/grilld/backend/
        ├── GrilldBackendApplication.java
        ├── common/exception/     — GlobalExceptionHandler, ErrorResponse, ResourceNotFoundException
        ├── config/               — OpenApiConfig
        ├── auth/                 — SecurityConfig, JwtConfig, TokenService, OAuth2LoginSuccessHandler
        └── user/                 — User (entity), UserRepository, UserService, MeController, UserResponse
    └── src/main/resources/
        ├── application.properties        — base config, no dev fallbacks, fails fast
        ├── application-local.properties  — dev-only defaults matching docker-compose.yml
        └── db/migration/V1__init_schema.sql — full data model, all 16 tables in one migration
```

## Architecture

**Data flow for a login:**

```
Browser → GET /oauth2/authorization/google
        → (Google login page, off our servers)
        → Google redirects back with proof of identity
        → OAuth2LoginSuccessHandler:
            UserService.findOrCreateFromGoogle(googleId, email)
            TokenService.issueFor(user)  →  RS256-signed JWT
        → {"token": "..."} returned to the client
```

**Data flow for every request after that:**

```
Client → Authorization: Bearer <jwt>
       → SecurityConfig's oauth2ResourceServer validates the signature
         against JwtConfig's public key
       → if valid: controller runs, e.g. MeController.me() reads jwt.getSubject()
         (the user's UUID) and looks them up
       → if invalid/missing: 401, controller never runs
```

**Schema ownership:** Spring Boot (this service) is the sole owner of the Postgres schema — Flyway migrations here are the only thing that ever changes it. The Python AI service (added from Phase 3) will call into this service rather than touching the database directly. See `docs/decisions-and-technical-architecture.md` §11.2 for the full reasoning.

## Key files and what they're responsible for

| File | Responsibility |
|---|---|
| `V1__init_schema.sql` | The entire canonical schema — every table from `docs/product-and-architecture.md` §9 plus the interrogation-engine and slot-waiving tables |
| `SecurityConfig.java` | Which URLs are public vs. require a valid JWT; wires up both OAuth2 login and JWT validation |
| `JwtConfig.java` | The RSA keypair used to sign/verify tokens (ephemeral per restart — see `LEARNING.md`'s auth section and the limitations note below) |
| `OAuth2LoginSuccessHandler.java` | The bridge from "Google confirmed your identity" to "here's your app token" |
| `UserService.java` | find-or-create-on-login logic; this is also the anti-abuse gate — the free credit grant is only reachable through a real Google account |
| `GlobalExceptionHandler.java` | Every API error, from any controller, comes back in the same `ErrorResponse` shape |

## Known limitations (deliberate, not oversights)

- **JWT signing key is ephemeral.** Regenerated on every app restart, so all previously issued tokens become invalid and everyone has to log in again. Acceptable pre-launch; needs a persisted key before real users depend on staying logged in across a deploy.
- **No frontend to redirect to yet.** `OAuth2LoginSuccessHandler` returns the JWT as raw JSON rather than redirecting to a frontend callback URL, since the frontend doesn't exist until Phase 9.
