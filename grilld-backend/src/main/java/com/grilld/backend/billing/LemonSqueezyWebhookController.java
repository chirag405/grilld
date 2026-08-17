package com.grilld.backend.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Receives Lemon Squeezy's server-to-server order notifications and turns a
 * paid order into a credit grant. This is the only entry point that mutates
 * `users.credits_balance` from outside Grilld's own backend - everything
 * else (checkout URL, confirming a purchase happened) lives entirely on
 * Lemon Squeezy's side, matching decisions-and-technical-architecture.md
 * §11.2's "Spring owns canonical state" split.
 * <p>
 * Body shape and header names verified against docs.lemonsqueezy.com/help/webhooks
 * (Aug 2026) - see LemonSqueezySignatureVerifier's Javadoc and LEARNING.md's
 * Phase 7 task 2 note for how that doc was actually fetched (WebFetch was
 * blocked by the docs site; curl with a browser user agent worked).
 */
@RestController
@RequestMapping("/api/v1/billing/webhooks")
public class LemonSqueezyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LemonSqueezyWebhookController.class);

    private final LemonSqueezySignatureVerifier signatureVerifier;
    private final LemonSqueezyProductCatalog productCatalog;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;

    public LemonSqueezyWebhookController(LemonSqueezySignatureVerifier signatureVerifier,
                                          LemonSqueezyProductCatalog productCatalog,
                                          CreditService creditService, ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.productCatalog = productCatalog;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads the body as raw bytes (not {@code @RequestBody}, which would let
     * Spring's message converter consume/re-serialize it before signature
     * verification ever sees the exact bytes Lemon Squeezy signed).
     */
    @PostMapping("/lemonsqueezy")
    public ResponseEntity<Void> handle(HttpServletRequest request,
                                        @RequestHeader("X-Signature") String signature,
                                        @RequestHeader("X-Event-Name") String eventName) throws IOException {
        byte[] rawBody = request.getInputStream().readAllBytes();

        if (!signatureVerifier.isValid(rawBody, signature)) {
            log.warn("Rejected a Lemon Squeezy webhook with an invalid signature (event={})", eventName);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (!"order_created".equals(eventName)) {
            // MVP only sells one-time products (product-and-architecture.md §11
            // scope: "Free credits + Lemon Squeezy top-up") - subscription
            // lifecycle events aren't relevant yet. Acknowledged, not retried.
            return ResponseEntity.ok().build();
        }

        Map<String, Object> payload = objectMapper.readValue(rawBody, new TypeReference<Map<String, Object>>() {
        });
        handleOrderCreated(payload);
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private void handleOrderCreated(Map<String, Object> payload) {
        Map<String, Object> meta = (Map<String, Object>) payload.get("meta");
        Map<String, Object> customData = meta == null
                ? Map.of() : (Map<String, Object>) meta.getOrDefault("custom_data", Map.of());
        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        Map<String, Object> attributes = (Map<String, Object>) data.get("attributes");

        String orderId = String.valueOf(data.get("id"));
        String status = (String) attributes.get("status");
        Object testMode = attributes.get("test_mode");
        Map<String, Object> firstOrderItem = (Map<String, Object>) attributes.get("first_order_item");
        String variantId = firstOrderItem == null ? null : String.valueOf(firstOrderItem.get("variant_id"));
        Object userIdRaw = customData.get("user_id");

        // "paid" is checked, not the event name alone - order_created fires as soon
        // as the order exists, and a declined/pending card still creates one.
        if (!"paid".equals(status)) {
            log.info("Ignoring Lemon Squeezy order {} with status '{}' (not paid)", orderId, status);
            return;
        }
        if (userIdRaw == null) {
            log.warn("Lemon Squeezy order {} has no custom_data.user_id - cannot credit anyone", orderId);
            return;
        }

        Optional<CreditPackage> creditPackage = productCatalog.packageForVariant(variantId);
        if (creditPackage.isEmpty()) {
            log.warn("Lemon Squeezy order {} paid for unrecognized variant {} - no credits granted",
                    orderId, variantId);
            return;
        }

        UUID userId = UUID.fromString(userIdRaw.toString());
        int credits = creditPackage.get().credits();
        boolean granted = creditService.grantIdempotent(userId, credits, "LEMON_SQUEEZY_ORDER:" + orderId);
        log.info("Lemon Squeezy order {} ({}, test_mode={}): {} credits {} for user {}",
                orderId, creditPackage.get(), testMode, credits,
                granted ? "granted" : "already granted (idempotent replay)", userId);
    }
}
