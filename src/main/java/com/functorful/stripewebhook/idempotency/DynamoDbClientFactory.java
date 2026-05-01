package com.functorful.stripewebhook.idempotency;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Micronaut factory for the runtime {@link DynamoDbClient} used by
 * {@link WebhookIdempotencyStore}. Disabled in tests so a mock can be
 * registered.
 *
 * <p>URL-connection HTTP client (no Netty / Apache HTTP) keeps the
 * native-image footprint small and the cold-start fast — same pattern
 * used in the data-handler Lambda.
 *
 * <p>The {@code @Replaces(DynamoDbClient.class)} annotation is load-bearing:
 * {@code io.micronaut.aws:micronaut-aws-sdk-v2} (a transitive dep) auto-
 * configures its own {@code DynamoDbClient} bean wired with Netty/Apache
 * HTTP, which would otherwise cause a {@code NonUniqueBeanException} at any
 * {@code @Inject DynamoDbClient} site. {@code @Replaces} tells Micronaut
 * "use this one, not the auto-configured one"; the URL-connection client
 * wins.
 */
@Factory
@Requires(notEnv = "test")
public class DynamoDbClientFactory {

    @Singleton
    @Replaces(DynamoDbClient.class)
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
