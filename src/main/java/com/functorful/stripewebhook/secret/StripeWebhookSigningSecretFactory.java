package com.functorful.stripewebhook.secret;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * Micronaut factory that fetches the Stripe webhook signing secret from
 * AWS Secrets Manager <strong>once</strong> at Lambda cold start and caches
 * the resulting {@link StripeWebhookSigningSecret} as a singleton.
 *
 * <p>Reads the secret ARN from the {@code STRIPE_WEBHOOK_SIGNING_SECRET_ARN}
 * env var (provisioned by OpenTofu in
 * {@code aws-stripe-webhook-lambda.tofu}). If the fetched value matches
 * {@link StripeWebhookSigningSecret#SENTINEL_VALUE}, this factory throws
 * {@link SentinelSecretException} which propagates out of bean creation,
 * aborting cold start. Any in-flight invocation receives 5xx (Lambda
 * runtime behavior on init failure).
 */
@Slf4j
@Factory
public class StripeWebhookSigningSecretFactory {

    @Singleton
    public StripeWebhookSigningSecret signingSecret(
            SecretsManagerClient secretsManagerClient,
            @Value("${stripe.webhook.signing-secret-arn}") String signingSecretArn
    ) {
        log.info("Fetching Stripe webhook signing secret from Secrets Manager (cold start). arn={}",
                signingSecretArn);

        GetSecretValueResponse response;
        try {
            response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder().secretId(signingSecretArn).build()
            );
        } catch (RuntimeException e) {
            // Do NOT log the exception toString — the AWS SDK includes the
            // request payload in some error messages, which can leak
            // adjacent context. Only log the ARN we tried to read.
            log.error("Failed to fetch Stripe webhook signing secret. arn={} errorClass={}",
                    signingSecretArn, e.getClass().getSimpleName());
            throw e;
        }

        try {
            return new StripeWebhookSigningSecret(response.secretString(), signingSecretArn);
        } catch (SentinelSecretException sentinelException) {
            // Tomás's veto-list wording: log structured error with ARN
            // ONLY, never the secret value. The SentinelSecretException
            // message carries the ARN; the secret value is never put on a
            // log line by either this class or the exception class.
            log.error("Stripe webhook signing secret is the PENDING_PAY-00a sentinel; "
                            + "aborting cold start. arn={}",
                    sentinelException.getSecretArn());
            throw sentinelException;
        }
    }
}
