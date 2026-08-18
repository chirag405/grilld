package com.grilld.backend.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.text.ParseException;
import java.util.UUID;

/**
 * We're not delegating to an external identity provider for API tokens - Google
 * only handles the login handshake (proving "this really is you"). Once that's
 * done, WE mint our own JWT for the frontend/API to use on every later request.
 * That means we need our own signing key, which is what this class sets up.
 *
 * Two modes, chosen by whether {@code grilld.jwt.signing-key-jwk} is set:
 * - Unset (the default): an RSA keypair is generated fresh in memory on every
 *   boot. Every previously issued token becomes invalid on restart - fine for
 *   local dev and tests (no setup required), a real problem the moment more
 *   than one instance runs or a deploy restarts the process.
 * - Set to a persisted RSA JWK (private key included), generated once via
 *   {@link com.grilld.backend.tools.JwtKeyGenerator} and stored as a secret -
 *   the same key survives restarts and can be shared across instances.
 * See docs/phases/phase-10/SETUP.md for how to generate and configure one.
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    private final RSAKey rsaKey;

    public JwtConfig(@Value("${grilld.jwt.signing-key-jwk:}") String signingKeyJwk) {
        this.rsaKey = signingKeyJwk.isBlank() ? generateEphemeralRsaKey() : loadPersistedRsaKey(signingKeyJwk);
    }

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

    private RSAKey loadPersistedRsaKey(String signingKeyJwk) {
        try {
            RSAKey key = RSAKey.parse(signingKeyJwk);
            if (!key.isPrivate()) {
                throw new IllegalStateException(
                        "grilld.jwt.signing-key-jwk does not contain a private key - "
                                + "generate one with com.grilld.backend.tools.JwtKeyGenerator");
            }
            log.info("Loaded persisted JWT signing key (kid={}) - issued tokens survive restarts.", key.getKeyID());
            return key;
        } catch (ParseException e) {
            throw new IllegalStateException("grilld.jwt.signing-key-jwk is not a valid RSA JWK", e);
        }
    }

    private RSAKey generateEphemeralRsaKey() {
        log.warn("Generating an ephemeral in-memory JWT signing key - all issued tokens will be "
                + "invalidated on the next restart. Expected in local dev/tests; set "
                + "grilld.jwt.signing-key-jwk (JWT_SIGNING_KEY_JWK) to a persisted key before "
                + "production use - see docs/phases/phase-10/SETUP.md.");
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
