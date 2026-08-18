package com.grilld.backend.billing;

import com.grilld.backend.auth.TokenService;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import com.jayway.jsonpath.JsonPath;
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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the account-facing billing endpoints over real HTTP + real Spring
 * Security (same TokenService-issued-JWT pattern as SessionFlowIntegrationTest)
 * - the user's own view of their balance and a real, well-formed checkout URL.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "grilld.lemonsqueezy.store-subdomain=grilld-test",
        "grilld.lemonsqueezy.starter-variant-id=1001",
        "grilld.lemonsqueezy.topup-variant-id=1002"
})
class BillingControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserService userService;

    @Autowired
    TokenService tokenService;

    @Test
    void balanceStartsAtZeroForABrandNewUser() throws Exception {
        User user = userService.findOrCreateFromGoogle("billing-balance-google-id", "billing-balance@example.com", null, null);
        String token = tokenService.issueFor(user);

        String body = mockMvc.perform(get("/api/v1/billing/balance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Integer balance = JsonPath.read(body, "$.creditsBalance");
        assertEquals(0, balance);
    }

    @Test
    void balanceRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/billing/balance")).andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutUrlPointsAtTheConfiguredStoreAndVariantWithTheUserIdEmbedded() throws Exception {
        User user = userService.findOrCreateFromGoogle("billing-checkout-google-id", "billing-checkout@example.com", null, null);
        String token = tokenService.issueFor(user);

        String body = mockMvc.perform(get("/api/v1/billing/checkout-url")
                        .param("creditPackage", "STARTER")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String checkoutUrl = JsonPath.read(body, "$.checkoutUrl");
        String decoded = URLDecoder.decode(checkoutUrl, StandardCharsets.UTF_8);
        assertTrue(checkoutUrl.startsWith("https://grilld-test.lemonsqueezy.com/buy/1001?"),
                "expected the Starter variant's checkout URL, got: " + checkoutUrl);
        assertTrue(decoded.contains("checkout[custom][user_id]=" + user.getId()),
                "expected the user id embedded as custom checkout data, got: " + decoded);
    }
}
