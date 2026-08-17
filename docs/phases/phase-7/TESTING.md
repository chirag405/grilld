# Phase 7 — Testing gate

## Automated checks

- [x] From `grilld-backend/`, run `./mvnw test` with JDK 26 and Docker available.
- [x] Result verified on 2026-08-18: **53 tests, 0 failures, 0 errors, 0 skipped** (35 pre-existing + 18 new for billing).
- [x] `CreditServiceTest` proves atomic deduction (race-free conditional UPDATE), refusal to overdraw, refund, and idempotent grant - all against a real Postgres balance column.
- [x] `GenerationServiceTest` proves the full pre-authorization path: successful run charges and keeps its `credits_charged`, a failed run refunds it, an underfunded account is blocked with no dangling run row, and a request for another user's session is rejected.
- [x] `LemonSqueezySignatureVerifierTest` proves the HMAC-SHA256 verification independently (expected signatures computed with the JDK's own `Mac`, not the verifier's own code called back on itself).
- [x] `LemonSqueezyWebhookControllerTest` drives the real endpoint over MockMvc with genuinely signed payloads: valid signature grants credits for a known variant, invalid signature is rejected with nothing granted, a redelivered webhook for the same order doesn't double-credit, an unrecognized variant and a non-`order_created` event are both acknowledged (200) but grant nothing.
- [x] `BillingControllerTest` proves the balance and checkout-url endpoints over real HTTP + Spring Security (real JWT, no auth bypass).
- [x] Nothing changed on the Python side this phase - `grilld-ai-service/`'s existing gate (`uv run pytest tests/unit_tests`, 20 passed) still applies unmodified; see `docs/phases/phase-6/TESTING.md`.

## Manual checklist

- [ ] Configure real Lemon Squeezy credentials per `SETUP.md`.
- [ ] Call `GET /api/v1/billing/checkout-url?creditPackage=STARTER` with a real JWT; open the returned URL in a browser.
- [ ] Complete a test-mode purchase on Lemon Squeezy's hosted checkout page.
- [ ] Confirm `GET /api/v1/billing/balance` reflects the new credits (60 free-signup + the package's credits) without any manual intervention.
- [ ] Confirm calling `POST /sessions/{id}/generate` for a session with fewer than 50 credits returns HTTP 402 and creates no `generation_runs` row.
- [ ] Confirm a `generate()` call charges 50 credits immediately and that a forced AI-service failure (e.g. stop the Python service mid-run) refunds them once the run lands `FAILED`.

## Gate result

The automated Phase 7 gate passes. The Lemon Squeezy checkout/webhook round trip is a user-operated manual check - it requires a real (test-mode) Lemon Squeezy store and account credentials that don't belong in CI or in this repo, matching the same reasoning `docs/phases/phase-1/TESTING.md` already applies to the real Google OAuth login check.
