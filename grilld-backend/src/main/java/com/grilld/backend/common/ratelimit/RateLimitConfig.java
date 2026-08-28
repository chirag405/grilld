package com.grilld.backend.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Wires one {@link RateLimitInterceptor} per cost tier to the specific
 * mutating endpoints that actually spend money or write state, not to
 * read-only GETs. Three tiers, because "answer a question" and "run a full
 * blueprint generation" have wildly different real costs:
 *
 * - interview: cheap-ish (one AI turn), generous limit - session create/answer/calibrate.
 * - generation: expensive (a full multi-agent run), tight limit.
 * - billing: not costly by itself, but a checkout-url loop is still abuse worth capping.
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final int interviewCapacity;
    private final int interviewWindowSeconds;
    private final int generationCapacity;
    private final int generationWindowSeconds;
    private final int billingCapacity;
    private final int billingWindowSeconds;

    public RateLimitConfig(
            ObjectMapper objectMapper,
            @Value("${grilld.ratelimit.enabled:true}") boolean enabled,
            @Value("${grilld.ratelimit.interview.capacity:20}") int interviewCapacity,
            @Value("${grilld.ratelimit.interview.window-seconds:60}") int interviewWindowSeconds,
            @Value("${grilld.ratelimit.generation.capacity:5}") int generationCapacity,
            @Value("${grilld.ratelimit.generation.window-seconds:3600}") int generationWindowSeconds,
            @Value("${grilld.ratelimit.billing.capacity:10}") int billingCapacity,
            @Value("${grilld.ratelimit.billing.window-seconds:60}") int billingWindowSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.interviewCapacity = interviewCapacity;
        this.interviewWindowSeconds = interviewWindowSeconds;
        this.generationCapacity = generationCapacity;
        this.generationWindowSeconds = generationWindowSeconds;
        this.billingCapacity = billingCapacity;
        this.billingWindowSeconds = billingWindowSeconds;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!enabled) {
            return;
        }

        registry.addInterceptor(new RateLimitInterceptor(
                        "interview", interviewCapacity, Duration.ofSeconds(interviewWindowSeconds), objectMapper))
                .addPathPatterns(
                        "/api/v1/sessions",
                        "/api/v1/sessions/*/answer",
                        "/api/v1/sessions/*/scale-tier",
                        "/api/v1/sessions/*/force-conclude",
                        // Same per-turn cost profile as answering a question - see TranscriptionController.
                        "/api/v1/voice/transcribe");

        registry.addInterceptor(new RateLimitInterceptor(
                        "generation", generationCapacity, Duration.ofSeconds(generationWindowSeconds), objectMapper))
                .addPathPatterns("/api/v1/sessions/*/generate");

        registry.addInterceptor(new RateLimitInterceptor(
                        "billing", billingCapacity, Duration.ofSeconds(billingWindowSeconds), objectMapper))
                .addPathPatterns("/api/v1/billing/checkout-url");
    }
}
