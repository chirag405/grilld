# Phase 7 — Billing

Phase 7 turns `users.credits_balance` and `credit_transactions` from schema that's existed since Phase 1 but never been written to, into a real, atomic, audited credit system - scoped to what `product-and-architecture.md` §11's MVP list actually asks for: free signup credits (already real since Phase 1's `User` default) plus a Lemon Squeezy one-time top-up. The recurring Builder/Pro/Team subscription tiers in §10's pricing table are a deliberate, spec-backed post-MVP deferral (see LEARNING.md's Phase 7 notes).

## Runtime flow

```text
POST /sessions/{id}/generate  (JWT required now - was open before Phase 7)
  -> verify the JWT's user actually owns the session
  -> CreditService.deductForRun: atomic conditional UPDATE, 402 if short
  -> only on success: create the generation_runs row, charge it, dispatch
  -> run FAILS -> CreditService.refundForRun gives the charge back
  -> run COMPLETES -> charge stands, package delivered (Phase 6)

GET  /billing/checkout-url?creditPackage=STARTER|TOPUP  (JWT required)
  -> LemonSqueezyCheckoutService builds a hosted checkout URL, no purchase here

Lemon Squeezy (buyer completes payment on Lemon Squeezy's own page)
  -> POST /billing/webhooks/lemonsqueezy  (no JWT - HMAC signature instead)
  -> LemonSqueezySignatureVerifier checks X-Signature against the raw body
  -> order_created + status=paid -> map first_order_item.variant_id to a
     CreditPackage (never trust custom_data for the credit amount)
  -> CreditService.grantIdempotent, keyed on the Lemon Squeezy order id

GET  /billing/balance  (JWT required)
  -> current credits_balance + the user's own credit_transactions audit trail
```

## Main additions

| Area | Key files | Responsibility |
|---|---|---|
| Credit domain | `CreditService`, `CreditTransaction`(+Repository) | The sole writer of `users.credits_balance` - atomic deduction, refund, idempotent grant, always with a matching audit row. |
| Pre-authorization | `GenerationService`, `GenerationController` | Verify session ownership, charge before creating a run, refund on failure. |
| Lemon Squeezy | `LemonSqueezySignatureVerifier`, `LemonSqueezyProductCatalog`, `LemonSqueezyCheckoutService`, `LemonSqueezyWebhookController` | Verify webhook authenticity, map a paid variant to a trusted credit amount, build checkout URLs. |
| Account view | `BillingController` | The authenticated user's own balance + transaction history + checkout URL. |
| Errors | `InsufficientCreditsException` (402) | New failure mode alongside the existing `GenerationBlockedException` (503, cost circuit breaker). |

No new migration - `users`, `credit_transactions`, and `generation_runs.credits_charged` have been part of the schema since `V1__init_schema.sql`; Phase 7 is entirely application code that finally writes to them for real.

## Deliberate boundaries

- Only the two one-time products from MVP scope (`product-and-architecture.md` §11 item 7) are wired: Starter (60 credits) and Top-up (50 credits). The recurring Builder/Pro/Team subscription tiers, rollover balances, and team seats are out of scope - explicitly cut from MVP.
- Only the flat full-blueprint charge (50 credits) is a blocking pre-authorization gate. Per-action metering below that (interview turns, single-doc regen, phase check-ins) exists in the spec's pricing table but isn't wired to block anything yet.
- The webhook only handles `order_created`; other Lemon Squeezy event types (subscription lifecycle, refunds) are acknowledged with 200 and otherwise ignored, since nothing downstream reacts to them yet. A refunded order does not currently claw back credits.
- Setting up an actual Lemon Squeezy store, products, and webhook endpoint is a manual dashboard task - see SETUP.md for exactly what's needed and where each value comes from.
