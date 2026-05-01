package com.functorful.stripewebhook.secret;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
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
 * <p>The {@code @Replaces(SecretsManagerClient.class)} annotation is
 * load-bearing: {@code io.micronaut.aws:micronaut-aws-sdk-v2} (a transitive
 * dep) auto-configures its own {@code SecretsManagerClient} bean wired with
 * Netty/Apache HTTP, which would otherwise cause a {@code NonUniqueBeanException}
 * at the {@code StripeWebhookSigningSecretFactory.signingSecret} injection
 * point. {@code @Replaces} tells Micronaut "use this one, not the
 * auto-configured one"; the URL-connection client wins.
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
    @Replaces(SecretsManagerClient.class)
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
