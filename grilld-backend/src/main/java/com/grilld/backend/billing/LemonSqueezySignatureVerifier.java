package com.grilld.backend.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Signature} header Lemon Squeezy sends on every
 * webhook request, per docs.lemonsqueezy.com/help/webhooks/signing-requests
 * (fetched directly, Aug 2026 - see LEARNING.md's Phase 7 task 2 note for why
 * WebFetch alone couldn't reach it and curl with a browser UA was used
 * instead): an HMAC-SHA256 hex digest of the *raw* request body, using the
 * signing secret configured in the Lemon Squeezy dashboard, compared with a
 * constant-time equality check (never String.equals - a timing side-channel
 * on a signature comparison is a real vulnerability class).
 */
@Component
public class LemonSqueezySignatureVerifier {

    private final String webhookSecret;

    public LemonSqueezySignatureVerifier(@Value("${grilld.lemonsqueezy.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "grilld.lemonsqueezy.webhook-secret is not configured - cannot verify webhook signatures");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] expected = hmacSha256Hex(rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmacSha256Hex(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is a JDK-guaranteed algorithm; only reachable if webhookSecret is empty
            // (rejected as blank above) or the JVM's crypto providers are broken - either way not
            // a per-request condition worth a checked exception up the call chain.
            throw new IllegalStateException("Unable to compute HMAC-SHA256", e);
        }
    }
}
