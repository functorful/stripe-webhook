package com.functorful.stripewebhook.secret;

/**
 * Singleton holder for the Stripe webhook signing secret, fetched once at
 * Lambda cold start from AWS Secrets Manager.
 *
 * <p>The secret is loaded eagerly at bean construction. If Secrets Manager
 * returns the {@link #SENTINEL_VALUE} literal, the bean construction throws
 * {@link SentinelSecretException} — this is Tomás's standing veto trigger
 * for PAY-02 / PAY-04: the Lambda must abort at cold start and return 5xx
 * for any in-flight invocation rather than serve traffic with a placeholder
 * credential.
 *
 * <p>The structured error log line emitted on sentinel detection contains
 * <em>only</em> the SecretsManager ARN that returned the sentinel, never
 * the secret value itself.
 */
public final class StripeWebhookSigningSecret {

    /**
     * Sentinel string written by OpenTofu into the prd Secrets Manager slots
     * during PAY-00h bootstrap. PaymentLambda + StripeWebhookLambda must
     * abort at cold start if they read this value at runtime.
     *
     * @see <a href="../../../../../../../infrastructure/runbooks/stripe-test-secrets-bootstrap.md">stripe-test-secrets-bootstrap.md §5</a>
     */
    public static final String SENTINEL_VALUE = "PENDING_PAY-00a";

    private final String secretValue;

    /**
     * @param secretValue the plaintext secret value fetched from Secrets
     *                    Manager. Must NOT be the {@link #SENTINEL_VALUE}.
     * @throws SentinelSecretException if {@code secretValue} equals the
     *                                 sentinel.
     */
    public StripeWebhookSigningSecret(String secretValue, String secretArn) {
        if (SENTINEL_VALUE.equals(secretValue)) {
            throw new SentinelSecretException(secretArn);
        }
        this.secretValue = secretValue;
    }

    /**
     * The raw secret bytes, used as the HMAC-SHA256 key for verifying
     * Stripe's {@code Stripe-Signature} header.
     */
    public byte[] keyBytes() {
        return secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
