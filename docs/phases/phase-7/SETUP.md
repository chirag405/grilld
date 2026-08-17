# Phase 7 — Setup

Use the shared prerequisites from `docs/phases/phase-6/SETUP.md` (Postgres, `langgraph dev`, Spring Boot with JDK 26). Phase 7 adds one new external dependency: a Lemon Squeezy store, needed only to exercise the checkout/webhook flow - the app boots and every other endpoint works fine without it.

**This part requires a human with a Lemon Squeezy account - it can't be automated.** Nothing else in this phase is blocked on it: `./mvnw test` passes with zero Lemon Squeezy configuration (see TESTING.md), and `generate()`'s credit deduction works against the free 60-credit signup grant regardless.

## Getting Lemon Squeezy credentials

1. Create a Lemon Squeezy account and a store at [lemonsqueezy.com](https://lemonsqueezy.com) if you don't have one. Lemon Squeezy stores default to **Test mode** - leave it on for local development; test-mode orders still fire real webhooks and are exactly what `LemonSqueezyWebhookControllerTest`'s `test_mode: true` fixtures model.
2. **Store subdomain**: shown in Settings → General (the `{subdomain}` in `https://{subdomain}.lemonsqueezy.com`). This is `LEMONSQUEEZY_STORE_SUBDOMAIN`.
3. **Products/variants**: create two one-time-purchase products matching `product-and-architecture.md` §10's MVP tiers:
   - "Starter" - $12, one-time
   - "Top-up" - $10, one-time
   Each product has at least one variant; the variant id (an integer, visible in the product's dashboard URL or via the Products API) is what the webhook actually charges against - not the product id. Set `LEMONSQUEEZY_STARTER_VARIANT_ID` and `LEMONSQUEEZY_TOPUP_VARIANT_ID` to these.
4. **Webhook signing secret**: Settings → Webhooks → create a webhook pointing at `https://<your-deployed-host>/api/v1/billing/webhooks/lemonsqueezy` (for local testing, tunnel it - e.g. `ngrok http 8080` - and use the tunnel URL), subscribed to at least `order_created`. Choose any signing secret string (6-40 characters) when creating it; that value is `LEMONSQUEEZY_WEBHOOK_SECRET`. Verified live against docs.lemonsqueezy.com/help/webhooks - see LEARNING.md's Phase 7 task 2 note.

## Configuration

| Property | Env var | Required for |
|---|---|---|
| `grilld.lemonsqueezy.webhook-secret` | `LEMONSQUEEZY_WEBHOOK_SECRET` | Verifying incoming webhooks - unset means every webhook is rejected (fails loudly, not silently). |
| `grilld.lemonsqueezy.store-subdomain` | `LEMONSQUEEZY_STORE_SUBDOMAIN` | Building checkout URLs. |
| `grilld.lemonsqueezy.starter-variant-id` | `LEMONSQUEEZY_STARTER_VARIANT_ID` | Mapping a paid order to the Starter package (60 credits) and building its checkout URL. |
| `grilld.lemonsqueezy.topup-variant-id` | `LEMONSQUEEZY_TOPUP_VARIANT_ID` | Same, for the Top-up package (50 credits). |

All four default to an empty string if unset - the app still boots and every non-billing endpoint works. `LemonSqueezyCheckoutService`/`LemonSqueezySignatureVerifier` only throw `IllegalStateException` at the moment a checkout URL or webhook is actually requested without them configured.

## Verification

```powershell
cd grilld-backend
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk26.0.1_8"
./mvnw test
```

Docker is required (Testcontainers Postgres); no Lemon Squeezy credentials are needed for the automated gate - `LemonSqueezyWebhookControllerTest`/`LemonSqueezySignatureVerifierTest`/`BillingControllerTest` all set their own test-only secret and variant ids via `@TestPropertySource`.

To manually verify a real purchase end to end once Lemon Squeezy credentials are configured: start the app with those env vars set, call `GET /api/v1/billing/checkout-url?creditPackage=STARTER` with a real JWT, open the returned URL, complete a test-mode purchase, and confirm `GET /api/v1/billing/balance` reflects the new credits within a few seconds (Lemon Squeezy retries on failure, so it may take up to ~2 minutes in the worst case).
