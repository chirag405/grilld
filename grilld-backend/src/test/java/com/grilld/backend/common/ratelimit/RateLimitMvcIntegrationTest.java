package com.grilld.backend.common.ratelimit;

import com.grilld.backend.auth.TokenService;
import com.grilld.backend.user.User;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RateLimitInterceptorTest already proves the token-bucket logic in isolation;
 * this proves the other half - that RateLimitConfig actually wires it into the
 * real Spring MVC dispatch chain for a real endpoint. The whole suite disables
 * rate limiting by default (pom.xml surefire config) precisely so this one
 * class can turn it back on, with a capacity small enough to trip in two calls,
 * without touching every other test that hits these endpoints many times.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "grilld.ratelimit.enabled=true",
        "grilld.ratelimit.interview.capacity=1",
        "grilld.ratelimit.interview.window-seconds=60"
})
class RateLimitMvcIntegrationTest {

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
    void secondSessionCreationWithinTheWindowIsRejectedWith429() throws Exception {
        User user = userService.findOrCreateFromGoogle("google-ratelimit-test-sub", "ratelimit-test@example.com");
        String token = tokenService.issueFor(user);

        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"rawIdea\":\"first idea, should be allowed\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"rawIdea\":\"second idea, should be rate limited\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
