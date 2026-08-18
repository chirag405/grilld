package com.grilld.backend.billing;

import com.grilld.backend.user.User;
import com.grilld.backend.user.UserRepository;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real webhook endpoint over MockMvc with a genuinely HMAC-signed
 * payload (computed independently, same principle as
 * LemonSqueezySignatureVerifierTest) rather than a hand-typed fake signature
 * - proves signature verification, event-name filtering, variant-to-package
 * mapping, and idempotent crediting all wire together correctly through real
 * Spring Security (this endpoint is permitAll'd, not JWT-protected - see
 * SecurityConfig) and a real Postgres balance.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "grilld.lemonsqueezy.webhook-secret=webhook-test-secret",
        "grilld.lemonsqueezy.starter-variant-id=1001",
        "grilld.lemonsqueezy.topup-variant-id=1002"
})
class LemonSqueezyWebhookControllerTest {

    private static final String SECRET = "webhook-test-secret";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CreditTransactionRepository creditTransactionRepository;

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String orderCreatedPayload(String orderId, String userId, String variantId, String status) {
        return """
                {
                  "meta": {
                    "event_name": "order_created",
                    "custom_data": { "user_id": "%s" }
                  },
                  "data": {
                    "type": "orders",
                    "id": "%s",
                    "attributes": {
                      "status": "%s",
                      "test_mode": true,
                      "first_order_item": { "variant_id": %s }
                    }
                  }
                }
                """.formatted(userId, orderId, status, variantId);
    }

    @Test
    void paidOrderForTheStarterVariantGrantsSixtyCredits() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-starter-google-id", "webhook-starter@example.com", null, null);
        String body = orderCreatedPayload("order-starter-1", user.getId().toString(), "1001", "paid");

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created")
                        .header("X-Signature", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(60, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance(),
                "60 from the Starter package - no free-signup grant any more");
    }

    @Test
    void paidOrderForTheTopupVariantGrantsFiftyCredits() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-topup-google-id", "webhook-topup@example.com", null, null);
        String body = orderCreatedPayload("order-topup-1", user.getId().toString(), "1002", "paid");

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created")
                        .header("X-Signature", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(50, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance());
    }

    @Test
    void redeliveredWebhookForTheSameOrderDoesNotDoubleCredit() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-replay-google-id", "webhook-replay@example.com", null, null);
        String body = orderCreatedPayload("order-replay-1", user.getId().toString(), "1002", "paid");
        String signature = sign(body);

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created").header("X-Signature", signature)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created").header("X-Signature", signature)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        assertEquals(50, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance(),
                "a retried/redelivered webhook for the same order id must not grant twice");
        assertEquals(1, creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(t -> t.getReason().equals("LEMON_SQUEEZY_ORDER:order-replay-1")).count());
    }

    @Test
    void invalidSignatureIsRejectedAndGrantsNothing() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-badsig-google-id", "webhook-badsig@example.com", null, null);
        String body = orderCreatedPayload("order-badsig-1", user.getId().toString(), "1002", "paid");

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created")
                        .header("X-Signature", "0000000000000000000000000000000000000000000000000000000000000000")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertEquals(0, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance(),
                "an invalid signature must not grant credits");
    }

    @Test
    void unrecognizedVariantIsAcknowledgedButGrantsNothing() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-unknown-google-id", "webhook-unknown@example.com", null, null);
        String body = orderCreatedPayload("order-unknown-1", user.getId().toString(), "9999", "paid");

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_created")
                        .header("X-Signature", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(0, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance());
    }

    @Test
    void nonOrderCreatedEventsAreAcknowledgedAndIgnored() throws Exception {
        User user = userService.findOrCreateFromGoogle("webhook-otherevent-google-id", "webhook-otherevent@example.com", null, null);
        String body = orderCreatedPayload("order-refund-1", user.getId().toString(), "1002", "paid");

        mockMvc.perform(post("/api/v1/billing/webhooks/lemonsqueezy")
                        .header("X-Event-Name", "order_refunded")
                        .header("X-Signature", sign(body))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        assertEquals(0, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance());
    }
}
