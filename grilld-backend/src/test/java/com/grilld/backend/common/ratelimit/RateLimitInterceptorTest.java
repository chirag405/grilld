package com.grilld.backend.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsUpToCapacityThenRejectsWith429() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor("test-tier", 3, Duration.ofMinutes(1), objectMapper);
        authenticateAs("user-1");

        for (int i = 0; i < 3; i++) {
            assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null),
                    "request " + i + " should be within capacity");
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, null);

        assertFalse(allowed);
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("Rate limit exceeded"));
    }

    @Test
    void tracksSeparateBucketsPerUser() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor("test-tier", 1, Duration.ofMinutes(1), objectMapper);

        authenticateAs("user-a");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null));
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null));

        authenticateAs("user-b");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null),
                "a different user must have their own, unconsumed bucket");
    }

    @Test
    void fallsBackToRemoteAddressWhenUnauthenticated() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor("test-tier", 1, Duration.ofMinutes(1), objectMapper);
        SecurityContextHolder.clearContext();

        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("10.0.0.1");
        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), null));

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("10.0.0.1");
        assertFalse(interceptor.preHandle(second, new MockHttpServletResponse(), null));
    }

    private void authenticateAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
