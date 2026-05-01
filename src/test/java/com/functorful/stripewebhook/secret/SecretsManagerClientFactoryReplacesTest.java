package com.functorful.stripewebhook.secret;

import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the {@code @Replaces} fix on
 * {@link SecretsManagerClientFactory} and
 * {@link com.functorful.stripewebhook.idempotency.DynamoDbClientFactory}.
 *
 * <p>The bug ({@code NonUniqueBeanException} at cold start) only surfaces in
 * production envs because:
 * <ul>
 *   <li>{@code micronaut-aws-sdk-v2} auto-configures its own
 *       {@code SecretsManagerClient} and {@code DynamoDbClient} beans wired
 *       with Netty / Apache HTTP.</li>
 *   <li>Our factories register additional {@code @Singleton} beans of the
 *       same types wired with the URL-connection HTTP client.</li>
 *   <li>Tests pass with the test env because {@code @Requires(notEnv = "test")}
 *       excludes our factories under {@code test}, leaving only the auto-
 *       configured beans — no ambiguity. Production cold start has both, and
 *       the injection point throws {@code NonUniqueBeanException}.</li>
 * </ul>
 *
 * <p>This test boots a real {@link ApplicationContext} in the {@code function}
 * + {@code lambda} envs (matching the cold-start log's "Established active
 * environments: [ec2, cloud, function, lambda]") and resolves the two SDK
 * client beans by type. {@code context.getBean(...)} throws
 * {@code NonUniqueBeanException} when two beans match — so the assertions
 * below fail loudly the moment {@code @Replaces} is dropped or weakened.
 *
 * <p>The HTTP-client-choice property of {@code @Replaces} (URL-connection
 * vs Netty) is an orthogonal native-image-footprint concern and not in
 * scope here; the production-blocking regression is the uniqueness one.
 */
class SecretsManagerClientFactoryReplacesTest {

    private ApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void secretsManagerClientResolvesUniquelyUnderProductionEnvs() {
        startContextInProductionEnvs();

        // The production-blocking regression: getBean(SecretsManagerClient.class)
        // throws NonUniqueBeanException without @Replaces. We assert that does
        // NOT throw, i.e. exactly one bean wins.
        assertThatCode(() -> context.getBean(SecretsManagerClient.class))
                .as("@Replaces ensures exactly one SecretsManagerClient bean wins under prod envs")
                .doesNotThrowAnyException();

        SecretsManagerClient client = context.getBean(SecretsManagerClient.class);
        assertThat(client).isNotNull();
    }

    @Test
    void dynamoDbClientResolvesUniquelyUnderProductionEnvs() {
        startContextInProductionEnvs();

        // Same regression on the DDB client factory — symmetric fix on
        // DynamoDbClientFactory. {@link WebhookIdempotencyStore} is the
        // injection point that would otherwise throw at cold start.
        assertThatCode(() -> context.getBean(DynamoDbClient.class))
                .as("@Replaces ensures exactly one DynamoDbClient bean wins under prod envs")
                .doesNotThrowAnyException();

        DynamoDbClient client = context.getBean(DynamoDbClient.class);
        assertThat(client).isNotNull();
        // WebhookIdempotencyStore depends on DynamoDbClient — if uniqueness
        // were broken the bean for the store itself would also fail.
        // Resolving it is a transitive smoke test for the whole chain.
        assertThatCode(() -> context.getBean(WebhookIdempotencyStore.class))
                .as("WebhookIdempotencyStore wires up cleanly with the unique DynamoDbClient bean")
                .doesNotThrowAnyException();
    }

    private void startContextInProductionEnvs() {
        // The SDK v2 client builders resolve region via the default provider
        // chain (env vars / profile / EC2 metadata). The Lambda runtime sets
        // AWS_REGION automatically; CI doesn't. Set the SDK system property
        // here so the factory builders succeed without AWS env vars present.
        System.setProperty("aws.region", "eu-west-1");

        // Boot the context exactly as the Lambda cold start would, with the
        // env vars the Tofu IaC injects. The values are placeholder ARNs;
        // factory beans are constructed lazily, and we only resolve the AWS
        // client beans here — the StripeWebhookSigningSecretFactory (which
        // would call Secrets Manager at construction) is not resolved by
        // this test, so no AWS calls happen.
        context = ApplicationContext.builder()
                .environments("function", "lambda")
                .properties(Map.of(
                        "stripe.webhook.signing-secret-arn",
                        "arn:aws:secretsmanager:eu-west-1:000000000000:secret:test/placeholder",
                        "webhook-events.table-name", "WebhookEvent-placeholder"
                ))
                .start();
    }
}
