package com.functorful.stripewebhook.email;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Micronaut factory for the {@link SesV2Client} used by
 * {@link SesEmailService} to send the Phase-1 payment confirmation
 * email.
 *
 * <p>Same {@code @Replaces} + URL-connection pattern as
 * {@link com.functorful.stripewebhook.secret.SecretsManagerClientFactory}
 * and {@link com.functorful.stripewebhook.idempotency.DynamoDbClientFactory}
 * — keeps the native-image build small and the cold-start fast (no
 * Netty / Apache HTTP transitively pulled in).
 *
 * <p>The {@code @Replaces(SesV2Client.class)} annotation is load-bearing
 * for the same reason it is on the SecretsManager and DynamoDB factories:
 * {@code io.micronaut.aws:micronaut-aws-sdk-v2} would otherwise auto-
 * configure its own SES client wired with Netty/Apache HTTP and the bean
 * resolution at the {@link SesEmailService} injection point would hit a
 * {@code NonUniqueBeanException}.
 *
 * <p>Disabled in tests via {@code @Requires(notEnv = "test")} so unit
 * tests can supply mock {@link SesV2Client} instances.
 */
@Factory
@Requires(notEnv = "test")
public class SesV2ClientFactory {

    @Singleton
    @Replaces(SesV2Client.class)
    public SesV2Client sesV2Client() {
        return SesV2Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
