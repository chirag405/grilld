package com.grilld.backend.auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtConfigTest {

    @Test
    void blankSigningKeyFallsBackToAWorkingEphemeralKey() throws Exception {
        JwtConfig config = new JwtConfig("");

        assertTokenRoundTrips(config);
    }

    @Test
    void persistedSigningKeyIsLoadedAndProducesAWorkingEncoderDecoderPair() throws Exception {
        String jwk = freshPrivateRsaJwk();

        JwtConfig config = new JwtConfig(jwk);

        assertTokenRoundTrips(config);
    }

    @Test
    void restartingWithTheSamePersistedKeyAcceptsTokensIssuedBeforeTheRestart() throws Exception {
        String jwk = freshPrivateRsaJwk();
        JwtConfig beforeRestart = new JwtConfig(jwk);
        JwtEncoder encoder = beforeRestart.jwtEncoder();
        String token = encode(encoder, "user-123");

        JwtConfig afterRestart = new JwtConfig(jwk);
        JwtDecoder decoder = afterRestart.jwtDecoder();
        Jwt decoded = decoder.decode(token);

        assertEquals("user-123", decoded.getSubject());
    }

    @Test
    void invalidSigningKeyJwkFailsFastAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new JwtConfig("not-a-valid-jwk"));
    }

    @Test
    void publicOnlyJwkIsRejectedBecauseItCannotSignTokens() throws Exception {
        RSAKey publicOnly = RSAKey.parse(freshPrivateRsaJwk()).toPublicJWK();

        assertThrows(IllegalStateException.class, () -> new JwtConfig(publicOnly.toJSONString()));
    }

    private void assertTokenRoundTrips(JwtConfig config) throws Exception {
        JwtEncoder encoder = config.jwtEncoder();
        JwtDecoder decoder = config.jwtDecoder();

        String token = encode(encoder, "user-abc");
        Jwt decoded = decoder.decode(token);

        assertEquals("user-abc", decoded.getSubject());
    }

    private String encode(JwtEncoder encoder, String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("grilld")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(subject)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String freshPrivateRsaJwk() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return jwk.toJSONString();
    }
}
