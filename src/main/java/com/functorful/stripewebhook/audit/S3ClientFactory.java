package com.functorful.stripewebhook.audit;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Micronaut factory for the {@link S3Client} used by
 * {@link WebhookPayloadAuditWriter} to persist every signature-verified
 * webhook payload to the Object-Lock-protected audit-log bucket
 * (PAY-08).
 *
 * <p>Same {@code @Replaces} + URL-connection pattern as
 * {@link com.functorful.stripewebhook.email.SesV2ClientFactory},
 * {@link com.functorful.stripewebhook.secret.SecretsManagerClientFactory}
 * and {@link com.functorful.stripewebhook.idempotency.DynamoDbClientFactory}
 * — keeps the native-image build small and the cold-start fast (no
 * Netty / Apache HTTP transitively pulled in).
 *
 * <p>The {@code @Replaces(S3Client.class)} annotation is load-bearing
 * for the same reason it is on the SES and DynamoDB factories:
 * {@code io.micronaut.aws:micronaut-aws-sdk-v2} would otherwise auto-
 * configure its own S3 client wired with Netty/Apache HTTP and the bean
 * resolution at the {@link WebhookPayloadAuditWriter} injection point
 * would hit a {@code NonUniqueBeanException}.
 *
 * <p>Region is resolved via the SDK v2 default provider chain — the
 * Lambda runtime sets {@code AWS_REGION} automatically (and the SDK's
 * default chain consults that env var), so the client lands in the same
 * single-AZ region as the Lambda. The audit-log bucket lives in the same
 * region; no explicit pin is needed (mirrors
 * {@link com.functorful.stripewebhook.email.SesV2ClientFactory} and
 * {@link com.functorful.stripewebhook.idempotency.DynamoDbClientFactory}).
 *
 * <p>Disabled in tests via {@code @Requires(notEnv = "test")} so unit
 * tests can supply mock {@link S3Client} instances.
 */
@Factory
@Requires(notEnv = "test")
public class S3ClientFactory {

    @Singleton
    @Replaces(S3Client.class)
    public S3Client s3Client() {
        return S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
