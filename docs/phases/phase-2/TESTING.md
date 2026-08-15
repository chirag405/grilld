# Phase 2 — Testing (the gate for Phase 3)

## Automated — all done and passing this session

- [x] `./mvnw test` — full suite passes (3 tests): schema validation, app context, and the full session/turn flow.
- [x] `GrilldBackendApplicationTests.flywayMigrationCreatesFullSchema` — still passes with the Phase 2 entities added (Hibernate's `ddl-auto=validate` catches any entity/column mismatch against the real schema on every run, not just at write time).
- [x] `SessionFlowIntegrationTest.fullInterviewFlowPersistsCorrectly` — the real gate for this phase, all in one test against a real (Testcontainers) Postgres:
  - [x] Starting a session creates all 8 seed slots (`SeedSlots`)
  - [x] The opening question is the "restate first" style question, referencing the raw idea back
  - [x] Answering a turn extracts a fact and marks the corresponding slot `FILLED` with the right value
  - [x] A genuinely new slot spawned mid-interview (`monetization_intent`) gets created, without colliding with an existing seed slot
  - [x] Extracted facts get merged into `ProjectBrief.brief_json`
  - [x] After the stub's configured turn count, the interview reports `concluded: true`

## Manual

- [ ] (Optional, needs Phase 1's Google credentials) Start the app locally, log in, and manually POST to `/api/v1/sessions` and `/api/v1/sessions/{id}/answer` with a real browser/Postman session to see the flow outside of the test suite. Not required to consider this phase done — `SessionFlowIntegrationTest` already covers the same path end to end — but useful if you want to see it with your own eyes.

## Sign-off

Phase 2 is done: every item above that can be automated is automated and passing. There's no required manual step this time (unlike Phase 1's Google OAuth piece) — the stub AI service means the whole loop is testable without any external dependency.
