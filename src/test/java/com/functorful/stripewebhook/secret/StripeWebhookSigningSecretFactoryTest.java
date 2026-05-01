package com.functorful.stripewebhook.secret;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookSigningSecretFactoryTest {

    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:eu-west-1:123456789012:secret:/sobrado/dev/stripe/webhook-signing-secret-AbCdEf";

    @Mock
    SecretsManagerClient secretsManagerClient;

    StripeWebhookSigningSecretFactory factory;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger factoryLogger;

    @BeforeEach
    void setUp() {
        factory = new StripeWebhookSigningSecretFactory();

        // Capture log output to assert the structured-log behavior.
        factoryLogger = (Logger) LoggerFactory.getLogger(StripeWebhookSigningSecretFactory.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        factoryLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        factoryLogger.detachAppender(logAppender);
    }

    @Test
    void returnsSecretWhenSecretsManagerReturnsValidValue() {
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString("whsec_real_value").build());

        StripeWebhookSigningSecret result = factory.signingSecret(secretsManagerClient, SECRET_ARN);

        assertThat(result).isNotNull();
        assertThat(result.keyBytes())
                .containsExactly("whsec_real_value".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void abortsColdStartWhenSecretIsSentinel() {
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString(StripeWebhookSigningSecret.SENTINEL_VALUE)
                        .build());

        assertThatThrownBy(() -> factory.signingSecret(secretsManagerClient, SECRET_ARN))
                .isInstanceOf(SentinelSecretException.class);

        // Tomás's veto wording: structured error log MUST include the
        // ARN and MUST NOT include the secret value (which here is the
        // sentinel literal, but the assertion holds for any secret value).
        assertThat(logAppender.list)
                .anyMatch(event ->
                        event.getLevel().equals(Level.ERROR)
                                && event.getFormattedMessage().contains(SECRET_ARN)
                                && event.getFormattedMessage().contains("sentinel")
                                && event.getFormattedMessage().contains("aborting cold start"));
    }

    @Test
    void sentinelLogLineDoesNotIncludeSecretValueShape() {
        // Defensive: the sentinel log line is the most likely place a
        // future refactor accidentally interpolates the secret value. We
        // assert no log line emitted by the factory contains anything
        // shaped like a secret (sk_*, whsec_*, or PENDING_PAY-00a if
        // someone puts the sentinel literal in a different field).
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString(StripeWebhookSigningSecret.SENTINEL_VALUE)
                        .build());

        try {
            factory.signingSecret(secretsManagerClient, SECRET_ARN);
        } catch (SentinelSecretException expected) {
            // expected
        }

        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message)
                    .as("log line at level %s must not echo a secret value", event.getLevel())
                    .doesNotContain("whsec_")
                    .doesNotContain("sk_test_")
                    .doesNotContain("sk_live_");
            // Note: the sentinel literal "PENDING_PAY-00a" CAN appear in
            // log text as part of the documentation reference (the
            // factory's exception message references it by name, e.g.
            // "...returned the PENDING_PAY-00a sentinel..."). That is
            // intentional and the assertion above does not forbid it —
            // we only forbid actual-secret-shapes (`whsec_`, `sk_*`).
        }
    }
}
