# Phase 8 — Hardening pass

The kickoff plan reserves Phase 8 as a buffer over Phases 1-7 before frontend work starts: no new features, just closing gaps the first seven phases left behind and confirming everything still passes together. It has no task list of its own for the same reason Phase 6/7's scope wasn't fully known until the phases before them landed - what needs hardening depends on what actually shipped.

## What this phase found and fixed

Wiring `requestingUserId` through `GenerationController.generate()` in Phase 7 (to charge the right account) made a pre-existing authorization gap impossible to miss: every other `sessionId`/`runId`-scoped endpoint took the id straight from the URL with no check that the authenticated caller owned it.

| Endpoint | Before | After |
|---|---|---|
| `POST /sessions/{id}/answer` | Any authenticated user could answer any session's questions | `SessionService.verifyOwnership` checked first |
| `POST /sessions/{id}/scale-tier` (calibrate) | Same | Same |
| `PUT /sessions/{id}/scale-tier` (override) | Same | Same |
| `POST /sessions/{id}/force-conclude` | Same | Same |
| `GET .../runs/{runId}/report` | Any authenticated user could read any run's Run Report | `GenerationService.resolveOwningUserId` checked first |
| `GET .../runs/{runId}/events` (SSE) | Same | Same |
| `GET .../runs/{runId}/package` | Any authenticated user could see any package's manifest | Same |
| `GET .../runs/{runId}/package/download` | Any authenticated user could download any package | Same |

`SessionService.verifyOwnership(sessionId, userId)` and `GenerationService.resolveOwningUserId(runId)` (which walks `runId -> brief -> session -> userId` for the two controllers that only have a run id) are new, small methods - the fix is contained to the three controllers that needed it, not threaded through every existing `SessionService` method signature. See LEARNING.md's Phase 8 section for why: 12 existing test files call those methods directly, bypassing HTTP and this whole gap entirely, and none of them needed to change for this fix.

## Deliberate boundaries

- The free 60-credit signup grant has no `credit_transactions` row of its own - only changes after it do. A documented, intentional reading of "never mutate balance without a row" (the grant is initial state, not a mutation), not an oversight - see LEARNING.md.
- This is the only hardening pass performed; it is not an exhaustive security audit of Phases 1-7. It targeted the one gap that became visible while doing Phase 7 task 1's work, not a systematic re-review of every endpoint.
