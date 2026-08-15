package com.grilld.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4's auto-configuration doesn't register an ObjectMapper bean in
 * every context this app runs in (observed while wiring SessionService's
 * brief-JSON merging) - defining it ourselves is standard practice anyway,
 * since most real apps want to configure it (Java 8 time types here) rather
 * than rely entirely on defaults.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
