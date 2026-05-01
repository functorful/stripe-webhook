package com.functorful.stripewebhook.secret;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class StripeWebhookSigningSecretTest {

    private static final String SECRET_ARN = "arn:aws:secretsmanager:eu-west-1:0:secret:test";

    @Test
    void exposesKeyBytesAsUtf8() {
        StripeWebhookSigningSecret secret = new StripeWebhookSigningSecret("whsec_abc123", SECRET_ARN);

        assertThat(secret.keyBytes())
                .containsExactly("whsec_abc123".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsSentinelValueAtConstruction() {
        assertThatThrownBy(() -> new StripeWebhookSigningSecret(
                StripeWebhookSigningSecret.SENTINEL_VALUE, SECRET_ARN))
                .isInstanceOf(SentinelSecretException.class)
                // Tomás's veto wording: the exception message carries the
                // ARN so operators can identify which secret returned the
                // sentinel without exposing the secret value.
                .hasMessageContaining(SECRET_ARN);
    }

    @Test
    void sentinelExceptionCarriesArn() {
        SentinelSecretException ex = catchThrowableOfType(
                () -> new StripeWebhookSigningSecret(StripeWebhookSigningSecret.SENTINEL_VALUE, SECRET_ARN),
                SentinelSecretException.class);

        assertThat(ex.getSecretArn()).isEqualTo(SECRET_ARN);
    }
}
