package com.grilld.backend.billing;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pure unit test (no Spring context, no Testcontainers) - LemonSqueezySignatureVerifier
 * has no dependency worth booting a whole app for. The "known good" case computes its
 * expected signature independently, with the JDK's own Mac/HmacSHA256 rather than by
 * calling the verifier's own hashing code back on itself - a self-consistent bug in
 * both places at once would otherwise pass silently.
 */
class LemonSqueezySignatureVerifierTest {

    private static final String SECRET = "test-signing-secret";

    private String independentHmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsACorrectlySignedBody() throws Exception {
        LemonSqueezySignatureVerifier verifier = new LemonSqueezySignatureVerifier(SECRET);
        String body = "{\"meta\":{\"event_name\":\"order_created\"}}";

        boolean valid = verifier.isValid(body.getBytes(StandardCharsets.UTF_8), independentHmac(SECRET, body));

        assertTrue(valid);
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        LemonSqueezySignatureVerifier verifier = new LemonSqueezySignatureVerifier(SECRET);
        String signedBody = "{\"meta\":{\"event_name\":\"order_created\"}}";
        String tamperedBody = "{\"meta\":{\"event_name\":\"order_refunded\"}}";

        boolean valid = verifier.isValid(tamperedBody.getBytes(StandardCharsets.UTF_8), independentHmac(SECRET, signedBody));

        assertFalse(valid);
    }

    @Test
    void rejectsASignatureComputedWithTheWrongSecret() throws Exception {
        LemonSqueezySignatureVerifier verifier = new LemonSqueezySignatureVerifier(SECRET);
        String body = "{\"meta\":{\"event_name\":\"order_created\"}}";

        boolean valid = verifier.isValid(body.getBytes(StandardCharsets.UTF_8), independentHmac("wrong-secret", body));

        assertFalse(valid);
    }

    @Test
    void rejectsABlankSignatureHeader() {
        LemonSqueezySignatureVerifier verifier = new LemonSqueezySignatureVerifier(SECRET);

        assertFalse(verifier.isValid("{}".getBytes(StandardCharsets.UTF_8), ""));
        assertFalse(verifier.isValid("{}".getBytes(StandardCharsets.UTF_8), null));
    }

    @Test
    void throwsRatherThanSilentlyAcceptingAnythingWhenNoSecretIsConfigured() {
        LemonSqueezySignatureVerifier verifier = new LemonSqueezySignatureVerifier("");

        assertThrows(IllegalStateException.class, () -> verifier.isValid("{}".getBytes(StandardCharsets.UTF_8), "anything"));
    }
}
