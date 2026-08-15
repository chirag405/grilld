# Phase 1 — Testing (the gate for Phase 2)

Per `phased-delivery`: Phase 2 doesn't start until this checklist passes. **All items verified this session, including the real Google login end to end.**

## Automated

- [x] `./mvnw test` passes — Testcontainers-backed integration test boots the full app against a real (temporary) Postgres, confirms Flyway applies cleanly, and confirms the exact expected set of 16 tables exists (`GrilldBackendApplicationTests.flywayMigrationCreatesFullSchema`).
- [x] `OAuth2LoginSuccessHandlerTest` — unit test (Mockito, no DB) proving the post-login handler correctly extracts `googleId`/`email` from Google's `OAuth2User`, calls `UserService`/`TokenService` correctly, and writes the right JSON response.

## Manual

- [x] `docker compose up -d postgres` starts cleanly and reports healthy.
- [x] `./mvnw spring-boot:run` (with `SPRING_PROFILES_ACTIVE=local`) boots without error against the Dockerized Postgres.
- [x] `GET /actuator/health` → `200`, reports the database connection as `UP`.
- [x] `GET /api/v1/me` with no `Authorization` header → `401`.
- [x] `GET /swagger-ui.html` → reachable without auth (redirects to the actual docs page).
- [x] **Real Google login, driven end to end in a browser with real credentials:**
  - Visited `http://localhost:8080/oauth2/authorization/google` → redirected to a genuine Google account chooser showing "to continue to grilld"
  - Selected an account, Google's consent screen correctly showed only what the app actually requests (name/profile picture, email)
  - Redirected back to `/login/oauth2/code/google` → `{"token": "eyJ..."}` — a real RS256-signed JWT
  - `GET /api/v1/me` with that token → `200`, correct `email`, `plan: FREE`, `creditsBalance: 60`
  - Logged in a **second time** with the same account → new token, but the **same `sub` claim** (same user id) — confirmed via `SELECT count(*) FROM users` staying at exactly 1 row. `UserService.findOrCreateFromGoogle` finds, not duplicates.

## A real bug this surfaced

`server.max-http-header-size` (used in `application-local.properties`) has been a no-op since Spring Boot 3.0 — deprecated in favor of `server.max-http-request-header-size`. It silently did nothing, and a real browser session (accumulated cookies across many dev-server restarts this session) tripped Tomcat's 8KB default, failing every browser-driven request with `HTTP 400 — Request header is too large` before it ever reached app code. curl requests worked fine throughout (much smaller headers), which is what made this a browser-specific, not app-logic, problem. Fixed by using the correct property name.

## Sign-off

**Phase 1 is fully done.** Every item is verified, including the one that needed a real external Google account.
