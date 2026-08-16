package com.grilld.backend.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Global spend kill-switch, checked before every new run starts (§10.6) - a
 * safety net against a systemic bug (not a single run's cost, but many runs
 * firing far more LLM calls than intended while nobody's watching), distinct
 * from the per-agent token caps and per-run credit pre-authorization already
 * specced elsewhere.
 *
 * <p>Reads/writes the {@code platform_settings} key-value table
 * ({@code daily_spend_cap_usd}, {@code kill_switch_active}) seeded by
 * {@code V1__init_schema.sql}. Cost is computed from {@code agent_executions}'
 * real token counts (§10.2's {@code usage_metadata} - see
 * HttpAiServiceClient.accumulateSubgraphTokens) against a single configured
 * per-model rate - every specialist currently inherits the Orchestrator's one
 * default model (Phase 5's documented deferral of the roster's per-agent
 * Opus/Sonnet split), so one rate is honest, not a simplification hiding
 * anything. Whichever model {@code GRILLD_AI_MODEL} is actually configured to
 * must have its rate reflected here - defaults match Claude Sonnet 5's
 * current intro pricing as of this writing (docs/decisions-and-technical-
 * architecture.md §9), not a permanent number.
 *
 * <p><b>Manually resettable only</b> - {@link #resetKillSwitch()} - no
 * auto-recovery once tripped, by design: a threshold that silently resets
 * would defeat the point of a circuit breaker.
 */
@Service
public class CostCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CostCircuitBreakerService.class);
    private static final String DAILY_SPEND_CAP_KEY = "daily_spend_cap_usd";
    private static final String KILL_SWITCH_KEY = "kill_switch_active";

    private final AgentExecutionRepository agentExecutionRepository;
    private final PlatformSettingsRepository platformSettingsRepository;
    private final double inputRatePerMillionTokensUsd;
    private final double outputRatePerMillionTokensUsd;
    private final long windowMs;

    public CostCircuitBreakerService(
            AgentExecutionRepository agentExecutionRepository,
            PlatformSettingsRepository platformSettingsRepository,
            @Value("${grilld.generation.cost.input-rate-per-million-tokens-usd:2.00}") double inputRatePerMillionTokensUsd,
            @Value("${grilld.generation.cost.output-rate-per-million-tokens-usd:10.00}") double outputRatePerMillionTokensUsd,
            @Value("${grilld.generation.cost.window-ms:86400000}") long windowMs) {
        this.agentExecutionRepository = agentExecutionRepository;
        this.platformSettingsRepository = platformSettingsRepository;
        this.inputRatePerMillionTokensUsd = inputRatePerMillionTokensUsd;
        this.outputRatePerMillionTokensUsd = outputRatePerMillionTokensUsd;
        this.windowMs = windowMs;
    }

    public boolean isKillSwitchActive() {
        return platformSettingsRepository.findById(KILL_SWITCH_KEY)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(false);
    }

    @Scheduled(fixedDelayString = "${grilld.generation.cost.check-interval-ms:3600000}")
    public void checkSpend() {
        if (isKillSwitchActive()) {
            return; // already tripped - stays tripped until a human calls resetKillSwitch()
        }

        double capUsd = platformSettingsRepository.findById(DAILY_SPEND_CAP_KEY)
                .map(setting -> Double.parseDouble(setting.getValue()))
                .orElse(Double.MAX_VALUE);

        Instant threshold = Instant.now().minusMillis(windowMs);
        double spendUsd = agentExecutionRepository.findByStartedAtAfter(threshold).stream()
                .mapToDouble(this::costOf)
                .sum();

        if (spendUsd >= capUsd) {
            log.warn("Cost circuit breaker tripped: ${} spent in the last {}ms (cap ${}). Blocking new runs until manually reset.",
                    spendUsd, windowMs, capUsd);
            setKillSwitch(true);
        }
    }

    /** Manual reset only (§10.6) - no auto-recovery once tripped. */
    public void resetKillSwitch() {
        setKillSwitch(false);
    }

    private void setKillSwitch(boolean active) {
        PlatformSetting setting = platformSettingsRepository.findById(KILL_SWITCH_KEY)
                .orElseGet(() -> new PlatformSetting(KILL_SWITCH_KEY, "false"));
        setting.updateValue(String.valueOf(active));
        platformSettingsRepository.save(setting);
    }

    private double costOf(AgentExecution execution) {
        double inputTokens = execution.getInputTokens() == null ? 0 : execution.getInputTokens();
        double outputTokens = execution.getOutputTokens() == null ? 0 : execution.getOutputTokens();
        return (inputTokens / 1_000_000.0) * inputRatePerMillionTokensUsd
                + (outputTokens / 1_000_000.0) * outputRatePerMillionTokensUsd;
    }
}
