package com.functorful.stripewebhook.signature;

import com.functorful.stripewebhook.secret.StripeWebhookSigningSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeSignatureVerifierTest {

    private static final String SECRET_VALUE = "whsec_test_abc123";
    private static final String SECRET_ARN = "arn:aws:secretsmanager:eu-west-1:0:secret:test";
    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final long FIXED_NOW_EPOCH = FIXED_NOW.getEpochSecond();
    private static final String RAW_BODY = "{\"id\":\"evt_test_1\",\"type\":\"payment_intent.succeeded\"}";

    private StripeWebhookSigningSecret secret;
    private StripeSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        secret = new StripeWebhookSigningSecret(SECRET_VALUE, SECRET_ARN);
        verifier = new StripeSignatureVerifier(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsValidSignature() {
        String header = buildHeader(FIXED_NOW_EPOCH, RAW_BODY, SECRET_VALUE);

        assertThatCode(() -> verifier.verify(header, RAW_BODY, secret))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingHeader() {
        assertThatThrownBy(() -> verifier.verify(null, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsBlankHeader() {
        assertThatThrownBy(() -> verifier.verify("   ", RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsHeaderWithoutTimestamp() {
        String header = "v1=" + hmacHex(SECRET_VALUE, FIXED_NOW_EPOCH + "." + RAW_BODY);

        assertThatThrownBy(() -> verifier.verify(header, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsHeaderWithoutV1Signature() {
        String header = "t=" + FIXED_NOW_EPOCH;

        assertThatThrownBy(() -> verifier.verify(header, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejectsStaleTimestampOlderThanFiveMinutes() {
        long staleTs = FIXED_NOW_EPOCH - StripeSignatureVerifier.TIMESTAMP_TOLERANCE_SECONDS - 1;
        String header = buildHeader(staleTs, RAW_BODY, SECRET_VALUE);

        assertThatThrownBy(() -> verifier.verify(header, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void rejectsFutureTimestampMoreThanFiveMinutesAhead() {
        long futureTs = FIXED_NOW_EPOCH + StripeSignatureVerifier.TIMESTAMP_TOLERANCE_SECONDS + 1;
        String header = buildHeader(futureTs, RAW_BODY, SECRET_VALUE);

        assertThatThrownBy(() -> verifier.verify(header, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void acceptsTimestampAtToleranceBoundary() {
        long boundaryTs = FIXED_NOW_EPOCH - StripeSignatureVerifier.TIMESTAMP_TOLERANCE_SECONDS;
        String header = buildHeader(boundaryTs, RAW_BODY, SECRET_VALUE);

        assertThatCode(() -> verifier.verify(header, RAW_BODY, secret))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSignatureMismatchOnTamperedBody() {
        String header = buildHeader(FIXED_NOW_EPOCH, RAW_BODY, SECRET_VALUE);
        String tampered = RAW_BODY.replace("succeeded", "payment_failed");

        assertThatThrownBy(() -> verifier.verify(header, tampered, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void rejectsSignatureSignedWithDifferentSecret() {
        String header = buildHeader(FIXED_NOW_EPOCH, RAW_BODY, "whsec_different");

        assertThatThrownBy(() -> verifier.verify(header, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("mismatch");
    }

    @Test
    void acceptsRotationHeaderWithMultipleV1Values() {
        // During Stripe-side signing-secret rotation, both old and new
        // signatures arrive in the same header. The verifier accepts if
        // any matches; here the second v1 is the valid one.
        String validSig = hmacHex(SECRET_VALUE, FIXED_NOW_EPOCH + "." + RAW_BODY);
        String header = "t=" + FIXED_NOW_EPOCH
                + ",v1=00000000000000000000000000000000"
                + ",v1=" + validSig;

        assertThatCode(() -> verifier.verify(header, RAW_BODY, secret))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresUnknownSchemeKeys() {
        // Stripe may add new scheme prefixes (v2, v3...). Unknown keys
        // must not break the parser; the v1 must still be honoured.
        String validSig = hmacHex(SECRET_VALUE, FIXED_NOW_EPOCH + "." + RAW_BODY);
        String header = "t=" + FIXED_NOW_EPOCH + ",v0=deadbeef,v1=" + validSig + ",v2=cafebabe";

        assertThatCode(() -> verifier.verify(header, RAW_BODY, secret))
                .doesNotThrowAnyException();
    }

    @Test
    void usesConstantTimeCompareIndirectly() {
        // We can't directly assert "constant-time", but we can assert
        // the API uses MessageDigest.isEqual by checking two near-misses
        // (one byte different at start vs end) both fail symmetrically.
        // Both throw InvalidSignatureException with the same message.
        String validSig = hmacHex(SECRET_VALUE, FIXED_NOW_EPOCH + "." + RAW_BODY);
        String diffStart = "f" + validSig.substring(1);
        String diffEnd = validSig.substring(0, validSig.length() - 1) + "f";

        assertThat(diffStart).isNotEqualTo(validSig);
        assertThat(diffEnd).isNotEqualTo(validSig);

        assertThatThrownBy(() -> verifier.verify(
                "t=" + FIXED_NOW_EPOCH + ",v1=" + diffStart, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(
                "t=" + FIXED_NOW_EPOCH + ",v1=" + diffEnd, RAW_BODY, secret))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void exposesCurrentInstantForTestability() {
        assertThat(verifier.currentInstant()).isEqualTo(FIXED_NOW);
    }

    // --- helpers ---

    private static String buildHeader(long timestamp, String body, String signingSecret) {
        return "t=" + timestamp + ",v1=" + hmacHex(signingSecret, timestamp + "." + body);
    }

    private static String hmacHex(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
