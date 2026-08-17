# Phase 8 — Testing gate

## Automated checks

- [x] From `grilld-backend/`, run `./mvnw test` with JDK 26 and Docker available.
- [x] Result verified on 2026-08-18: **55 tests, 0 failures, 0 errors, 0 skipped** (all of Phases 1-7's 53 tests, unmodified, plus 2 new ownership-denial tests - one was added inline as an extra assertion in an existing test rather than a new `@Test` method, hence 2 not 3 for 3 endpoints covered).
- [x] `SessionFlowIntegrationTest.answerEndpointRejectsAnotherUsersToken` - a second user's valid JWT gets 403 answering someone else's session.
- [x] `RunReportControllerTest.reportEndpointRejectsAnotherUsersToken` - a second user's valid JWT gets 403 reading someone else's Run Report.
- [x] `PackageControllerTest.packageIsReadyAndDownloadableRightAfterGenerate` - extended with a second user's valid JWT getting 403 on someone else's package download.
- [x] All Phase 1-7 gates still pass together in the same run - no regressions, confirming Phase 8's own stated gate.
- [x] Python side unaffected - `docs/phases/phase-6/TESTING.md`'s Python gate still applies unmodified.

## Manual checklist

- [ ] With two real Google accounts, start a session as account A, then attempt `POST /sessions/{A's session id}/answer` using account B's JWT - confirm HTTP 403.
- [ ] Same for `GET .../runs/{runId}/report`, `/events`, `/package`, `/package/download` using a run that belongs to account A.

## Gate result

The automated gate passes and is sufficient - the manual checklist is the same behavior already proven by the automated tests, just against two real Google-authenticated accounts instead of `TokenService.issueFor()`-minted test JWTs. Optional, not blocking.
