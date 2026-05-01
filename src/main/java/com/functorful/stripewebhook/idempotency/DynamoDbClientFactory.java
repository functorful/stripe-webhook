package com.functorful.stripewebhook.idempotency;

import io.micronaut.context.annotation.Factory;
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
 */
@Factory
@Requires(notEnv = "test")
public class DynamoDbClientFactory {

    @Singleton
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
