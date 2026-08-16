package com.grilld.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots the full app against a real Postgres container (not H2) - real infra,
 * real Flyway migration, matches how docs/decisions-and-technical-architecture.md
 * §11.2 treats Postgres as the single source of truth. Extend this per phase
 * rather than relying only on manual verification.
 */
@Testcontainers
@SpringBootTest
class GrilldBackendApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayMigrationCreatesFullSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name != 'flyway_schema_history'",
                String.class);

        Set<String> expected = Set.of(
                "users", "discovery_sessions", "interview_answers", "slots", "turns",
                "expertise_profiles", "rubric_evaluations", "slot_waives", "project_briefs",
                "generation_runs", "agent_executions", "platform_settings", "packages",
                "package_documents", "project_phases", "credit_transactions", "generated_documents"
        );

        assertEquals(expected, Set.copyOf(tables));
    }
}
