package com.grilld.backend.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grilld.backend.common.exception.ErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user token-bucket rate limiting for endpoints that trigger real cost -
 * AI service calls, credit-affecting actions - where a bug or an abusive
 * client hammering the API is the actual pre-launch risk (Phase 9's
 * scoping doc flagged this as the main gap: credits are the only cost gate
 * today, and a caller can burn a lot of Anthropic spend before running out
 * of them). Each protected endpoint group gets its own interceptor instance
 * (own bucket store, own limit) - see {@link RateLimitConfig}.
 *
 * Buckets live in an in-memory map, so this limits per-instance, not
 * cluster-wide. Fine for a single backend instance; once more than one
 * instance runs behind a load balancer, swap the in-memory bucket store for
 * bucket4j's Redis-backed ProxyManager (same Bucket API, different
 * construction) - not needed until that day comes.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final String tierName;
    private final int capacity;
    private final Duration refillPeriod;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitInterceptor(String tierName, int capacity, Duration refillPeriod, ObjectMapper objectMapper) {
        this.tierName = tierName;
        this.capacity = capacity;
        this.refillPeriod = refillPeriod;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String key = bucketKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Rate limit exceeded for " + tierName + " - try again in " + retryAfterSeconds + "s.",
                request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
        return false;
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder().capacity(capacity).refillIntervally(capacity, refillPeriod).build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String bucketKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
