package com.grilld.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The single place that decides "who's allowed to call what." Spring Security
 * works as a chain of filters that every HTTP request passes through before it
 * reaches a controller; this bean configures that chain.
 *
 * Two authentication mechanisms are active for two different purposes:
 *  - oauth2Login: the browser-based "log in with Google" handshake (used once,
 *    at login time, to prove identity).
 *  - oauth2ResourceServer (JWT): validates the Bearer token our own
 *    TokenService issued, on every subsequent API call. This is what actually
 *    protects /api/v1/** day to day.
 */
@Configuration
public class SecurityConfig {

    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final String frontendBaseUrl;

    public SecurityConfig(OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           @Value("${grilld.frontend.base-url}") String frontendBaseUrl) {
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * The frontend (Phase 9) is a different origin from the API in every
     * environment (separate Next.js deployment), so every `fetch()` it makes
     * with an `Authorization` header needs an explicit CORS allowance - the
     * browser blocks the response otherwise, silently, with no server-side
     * log of anything having gone wrong.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendBaseUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                // CSRF protects cookie/session-based auth from cross-site form submission.
                // We authenticate API calls with a Bearer token, which isn't automatically
                // sent by the browser the way cookies are, so this class of attack doesn't
                // apply here - disabling is the standard, not a shortcut.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/oauth2/**",
                                "/login/**",
                                // Lemon Squeezy calls this server-to-server, with no JWT - its own
                                // HMAC signature (LemonSqueezySignatureVerifier) is the real auth
                                // check here, done inside the controller, not this filter chain.
                                "/api/v1/billing/webhooks/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                        .successHandler(oAuth2LoginSuccessHandler)
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
