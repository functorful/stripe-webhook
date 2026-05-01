package com.functorful.stripewebhook.secret;

/**
 * Thrown at Lambda cold start when the fetched Secrets Manager value
 * matches {@link StripeWebhookSigningSecret#SENTINEL_VALUE}, indicating
 * the production cutover (PAY-00a) was forgotten or only partially run.
 *
 * <p>Tomás's standing veto for PAY-02 / PAY-04: the Lambda must abort at
 * cold start, log a structured error containing the SecretsManager ARN
 * that returned the sentinel (and <strong>only</strong> the ARN — never
 * the secret value), and return 5xx for any in-flight invocation.
 *
 * <p>The exception message intentionally carries the ARN only. Logging
 * frameworks may serialize {@link Throwable#getMessage()} to error
 * trackers; ARN is not sensitive, secret value would be.
 */
public final class SentinelSecretException extends RuntimeException {

    private final String secretArn;

    public SentinelSecretException(String secretArn) {
        super("Stripe webhook signing secret returned the PENDING_PAY-00a sentinel; "
                + "production cutover incomplete. arn=" + secretArn);
        this.secretArn = secretArn;
    }

    public String getSecretArn() {
        return secretArn;
    }
}
