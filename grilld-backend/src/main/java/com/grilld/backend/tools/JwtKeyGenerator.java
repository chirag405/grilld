package com.grilld.backend.tools;

import com.nimbusds.jose.jwk.RSAKey;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Standalone generator for a persisted JWT signing key (see JwtConfig). Not a
 * Spring bean and not run by the application itself - a one-off operational
 * step, run once per environment, whose output becomes a secret:
 *
 * <pre>
 *   ./mvnw -q exec:java -Dexec.mainClass=com.grilld.backend.tools.JwtKeyGenerator
 * </pre>
 *
 * Prints a single-line RSA JWK (private key included) to stdout. Store the
 * output as the {@code JWT_SIGNING_KEY_JWK} secret for that environment -
 * never commit it, never log it, never reuse one across environments.
 */
public final class JwtKeyGenerator {

    private JwtKeyGenerator() {
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        System.out.println(jwk.toJSONString());
    }
}
