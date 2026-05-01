package com.functorful.stripewebhook.signature;

import com.functorful.stripewebhook.secret.StripeWebhookSigningSecret;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies Stripe's {@code Stripe-Signature} header against a webhook
 * signing secret using HMAC-SHA256 with a 5-minute timestamp tolerance
 * window (symmetric: rejects timestamps too old AND too far in the future).
 *
 * <p>Header format (per Stripe docs):
 * <pre>
 *   Stripe-Signature: t=&lt;unix-ts&gt;,v1=&lt;hex-hmac&gt;[,v1=&lt;hex-hmac&gt;]...
 * </pre>
 * Multiple {@code v1} values appear during signing-secret rotation; this
 * verifier accepts the request if <em>any</em> of them matches.
 *
 * <p>The signed payload is {@code "${timestamp}.${raw-body}"}. Constant-
 * time comparison via {@link MessageDigest#isEqual} guards against timing
 * oracles.
 *
 * <p>All failures throw {@link InvalidSignatureException} with a generic
 * detail message; the caller is responsible for translating that to a
 * sanitised 400 response (no detail leakage).
 */
@Slf4j
@Singleton
public class StripeSignatureVerifier {

    /** ≤ 5-minute timestamp tolerance per the PAY-02 spec (T1 / R2). */
    public static final long TIMESTAMP_TOLERANCE_SECONDS = 5L * 60L;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Clock clock;

    public StripeSignatureVerifier() {
        this(Clock.systemUTC());
    }

    /** Test-only constructor — production code uses the no-arg form. */
    public StripeSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    /**
     * Verify the signature header against the raw request body and the
     * signing secret. Returns silently on success.
     *
     * @param signatureHeader the value of the {@code Stripe-Signature}
     *                        request header. May be {@code null}.
     * @param rawBody         the raw request body as received over the
     *                        wire — verbatim, no JSON parsing or
     *                        re-encoding.
     * @param secret          the webhook signing secret holder.
     * @throws InvalidSignatureException on missing header, malformed
     *                                   header, stale or future
     *                                   timestamp, or signature mismatch.
     */
    public void verify(String signatureHeader, String rawBody, StripeWebhookSigningSecret secret) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("missing Stripe-Signature header");
        }

        ParsedSignatureHeader parsed = parse(signatureHeader);

        long now = clock.instant().getEpochSecond();
        long delta = Math.abs(now - parsed.timestamp);
        if (delta > TIMESTAMP_TOLERANCE_SECONDS) {
            // Don't echo the timestamp into the message — log it locally
            // for ops, but the thrown exception (which the dispatcher may
            // log at WARN) carries no detail.
            log.warn("Stripe-Signature timestamp outside tolerance window. delta_seconds={}", delta);
            throw new InvalidSignatureException("timestamp outside tolerance window");
        }

        byte[] expected = computeHmac(parsed.timestamp, rawBody, secret);

        for (String candidateHex : parsed.v1Signatures) {
            byte[] candidate;
            try {
                candidate = HexFormat.of().parseHex(candidateHex);
            } catch (IllegalArgumentException malformed) {
                // Skip malformed v1 entries; we may have a valid sibling.
                continue;
            }
            if (MessageDigest.isEqual(expected, candidate)) {
                return;
            }
        }

        throw new InvalidSignatureException("signature mismatch");
    }

    private static ParsedSignatureHeader parse(String header) {
        Long timestamp = null;
        java.util.List<String> v1 = new java.util.ArrayList<>();

        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(value);
                    } catch (NumberFormatException malformed) {
                        throw new InvalidSignatureException("malformed Stripe-Signature header");
                    }
                }
                case "v1" -> v1.add(value);
                default -> {
                    /* unknown scheme — ignore (forward compatibility) */
                }
            }
        }

        if (timestamp == null) {
            throw new InvalidSignatureException("malformed Stripe-Signature header");
        }
        if (v1.isEmpty()) {
            throw new InvalidSignatureException("malformed Stripe-Signature header");
        }

        return new ParsedSignatureHeader(timestamp, v1);
    }

    private static byte[] computeHmac(long timestamp, String rawBody, StripeWebhookSigningSecret secret) {
        String signedPayload = timestamp + "." + rawBody;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.keyBytes(), HMAC_ALGORITHM));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandatory in every JCA provider; reaching here
            // means the JVM is broken.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Convenience for {@link Instant} consumers in tests. */
    public Instant currentInstant() {
        return clock.instant();
    }

    private record ParsedSignatureHeader(long timestamp, java.util.List<String> v1Signatures) {
    }
}
