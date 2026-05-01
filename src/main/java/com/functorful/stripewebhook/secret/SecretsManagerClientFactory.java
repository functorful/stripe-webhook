package com.functorful.stripewebhook.secret;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Micronaut factory for the {@link SecretsManagerClient} used at cold
 * start to fetch the Stripe webhook signing secret.
 *
 * <p>Uses the URL-connection HTTP client (no Netty / Apache HTTP)
 * — same dependency-trimming pattern as the data-handler Lambda, which
 * keeps the native-image build small and the cold-start fast.
 *
 * <p>Disabled in tests via the {@code @Requires(notEnv = "test")} guard so
 * tests can supply a mock {@link SecretsManagerClient} via Micronaut's
 * {@code @MockBean} or by registering a replacement bean in the test
 * application context.
 */
@Factory
@Requires(notEnv = "test")
public class SecretsManagerClientFactory {

    @Singleton
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
