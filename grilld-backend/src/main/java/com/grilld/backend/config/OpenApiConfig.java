package com.grilld.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation. Genuinely load-bearing here, not decorative: the Python AI
 * service and (from Phase 9 on) the frontend both integrate against this API's
 * contract, so the OpenAPI spec is the source of truth for that contract - see
 * docs/decisions-and-technical-architecture.md §11.3.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI grilldOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Grilld API")
                        .description("Platform API: auth, sessions, briefs, generation runs, billing. "
                                + "Agent/LLM logic lives in the separate Python AI service, not here.")
                        .version("v1"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .schemaRequirement(BEARER_SCHEME, new SecurityScheme()
                        .name(BEARER_SCHEME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"));
    }
}
