# Phase 1 — Testing (the gate for Phase 2)

Per `phased-delivery`: Phase 2 doesn't start until this checklist passes. Items marked **[done this session]** were already verified while building; the remaining items need your Google OAuth credentials (see `SETUP.md`) and a final look, since that part genuinely can't be verified without them.

## Automated

- [x] **[done this session]** `./mvnw test` passes — Testcontainers-backed integration test boots the full app against a real (temporary) Postgres, confirms Flyway applies cleanly, and confirms the exact expected set of 16 tables exists (`GrilldBackendApplicationTests.flywayMigrationCreatesFullSchema`).
- [x] **[done this session]** `OAuth2LoginSuccessHandlerTest` — unit test (Mockito, no DB) proving the post-login handler correctly extracts `googleId`/`email` from Google's `OAuth2User`, calls `UserService`/`TokenService` correctly, and writes the right JSON response. This covers the handler's own logic; it does not (and cannot) cover the actual OAuth2 redirect handshake with Google itself — that's the manual item below.

## Manual — already verified this session

- [x] **[done this session]** `docker compose up -d postgres` starts cleanly and reports healthy.
- [x] **[done this session]** `./mvnw spring-boot:run` (with `SPRING_PROFILES_ACTIVE=local`) boots without error against the Dockerized Postgres.
- [x] **[done this session]** `GET /actuator/health` → `200`, reports the database connection as `UP`.
- [x] **[done this session]** `GET /api/v1/me` with no `Authorization` header → `401`.
- [x] **[done this session]** `GET /swagger-ui.html` → reachable without auth (redirects to the actual docs page).

## Manual — needs your Google OAuth credentials (see SETUP.md)

- [ ] Set `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, start the app.
- [ ] Visit `http://localhost:8080/oauth2/authorization/google` in a browser.
- [ ] Confirm it redirects to a real Google login page.
- [ ] Log in with your own Google account (added as a test user on the OAuth consent screen).
- [ ] Confirm you're redirected back and see `{"token": "eyJ..."}` in the response.
- [ ] Copy that token and confirm `GET /api/v1/me` with `Authorization: Bearer <token>` now returns `200` with your user's `email`, `plan` (`FREE`), and `creditsBalance` (`60`).
- [ ] Log in a second time with the same account; confirm no duplicate user row is created (same `id` comes back both times) — this proves `UserService.findOrCreateFromGoogle` is actually finding, not just creating.

## Sign-off

Phase 1 is done once every box above is checked. The last group needs you specifically, since it's the one thing that requires a real external Google account — everything else is either automated or was already run and confirmed working this session.
