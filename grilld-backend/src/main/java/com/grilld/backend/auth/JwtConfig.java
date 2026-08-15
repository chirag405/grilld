package com.grilld.backend.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * We're not delegating to an external identity provider for API tokens - Google
 * only handles the login handshake (proving "this really is you"). Once that's
 * done, WE mint our own JWT for the frontend/API to use on every later request.
 * That means we need our own signing key, which is what this class sets up.
 *
 * Current limitation, deliberate for Phase 1: the RSA keypair is generated
 * fresh in memory every time the app starts, rather than loaded from a
 * persisted secret. Every previously issued token becomes invalid on restart
 * (everyone has to log in again). Fine for local development; revisit with a
 * persisted key before this goes anywhere near production traffic.
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    private final RSAKey rsaKey = generateRsaKey();

    @Bean
    public JwtEncoder jwtEncoder() {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder() throws Exception {
        RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    private RSAKey generateRsaKey() {
        log.warn("Generating an ephemeral in-memory JWT signing key - all issued tokens will be "
                + "invalidated on the next restart. Expected in local dev; must be replaced with a "
                + "persisted key before production use.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate RSA key for JWT signing", e);
        }
    }
}
