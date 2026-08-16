package com.grilld.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * A generation run drives one long-lived, blocking HTTP call (the SSE stream
 * from grilld-ai-service - see HttpAiServiceClient.generateBlueprint) that
 * spends nearly all of its time waiting on the network, not on CPU. That is
 * exactly the case virtual threads exist for: no pool-size tuning tradeoff
 * (too small queues runs, too large wastes platform threads) because a
 * blocked virtual thread costs almost nothing - a new one is created per run
 * and discarded when it finishes.
 */
@Configuration
public class AsyncConfig {

    @Bean
    public TaskExecutor generationExecutor() {
        return new VirtualThreadTaskExecutor("generation-run-");
    }
}
