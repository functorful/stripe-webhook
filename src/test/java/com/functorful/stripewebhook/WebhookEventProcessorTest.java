package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.functorful.stripewebhook.dispatch.WebhookEventDispatcher;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import org.mockito.ArgumentCaptor;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore.RecordResult;
import com.functorful.stripewebhook.secret.StripeWebhookSigningSecret;
import com.functorful.stripewebhook.signature.StripeSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (in-JVM) tests for the {@link WebhookEventProcessor}
 * orchestration. The processor is plain-Java; we construct it directly
 * with mocks rather than going through the Micronaut application context.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventProcessorTest {

    private static final String SECRET_VALUE = "whsec_test_abc123";
    private static final String SECRET_ARN = "arn:aws:secretsmanager:eu-west-1:0:secret:test";
    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final long FIXED_NOW_EPOCH = FIXED_NOW.getEpochSecond();
    private static final String EVENT_ID = "evt_test_1";
    private static final String EVENT_TYPE = "payment_intent.succeeded";
    private static final String VALID_BODY =
            "{\"id\":\"" + EVENT_ID + "\",\"type\":\"" + EVENT_TYPE + "\",\"data\":{}}";

    @Mock
    WebhookIdempotencyStore idempotencyStore;
    @Mock
    WebhookEventDispatcher dispatcher;

    StripeSignatureVerifier verifier;
    StripeWebhookSigningSecret signingSecret;
    Clock fixedClock;
    WebhookEventProcessor processor;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        verifier = new StripeSignatureVerifier(fixedClock);
        signingSecret = new StripeWebhookSigningSecret(SECRET_VALUE, SECRET_ARN);
        processor = new WebhookEventProcessor(verifier, signingSecret, idempotencyStore, dispatcher, fixedClock);
    }

    @Test
    void validSignatureAndFirstDeliveryReturns200AndWritesIdempotencyRecord() {
        when(idempotencyStore.recordFirstDelivery(eq(EVENT_ID), eq("stripe"), any(Instant.class)))
                .thenReturn(RecordResult.FIRST_DELIVERY);

        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                buildSignatureHeader(FIXED_NOW_EPOCH, VALID_BODY, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"received\":true");
        verify(idempotencyStore, times(1)).recordFirstDelivery(eq(EVENT_ID), eq("stripe"), eq(FIXED_NOW));
        ArgumentCaptor<StripeWebhookEvent> captor = ArgumentCaptor.forClass(StripeWebhookEvent.class);
        verify(dispatcher, times(1)).dispatch(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo(EVENT_ID);
        assertThat(captor.getValue().eventType()).isEqualTo(EVENT_TYPE);
    }

    @Test
    void replayReturns200WithoutDispatch() {
        when(idempotencyStore.recordFirstDelivery(eq(EVENT_ID), eq("stripe"), any(Instant.class)))
                .thenReturn(RecordResult.REPLAY);

        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                buildSignatureHeader(FIXED_NOW_EPOCH, VALID_BODY, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void invalidSignatureLogPathDoesNotEchoRequestBody() {
        // Tomás's PAY-05 pre-flight ask: confirm the signature-rejection
        // log path does NOT echo the request body. An attacker probing
        // with crafted JSON could otherwise harvest log content.
        //
        // The processor logs only InvalidSignatureException.getMessage()
        // and StripeSignatureVerifier#verify is the only producer of that
        // message — we assert it stays parameterised and never includes
        // the rawBody string. Since the message is a constructor-time
        // value, the contract is structurally safe; this test is the
        // belt-and-braces regression guard against future log-statement
        // edits in the catch block.
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WebhookEventProcessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String secretBody = "{\"id\":\"" + EVENT_ID + "\","
                    + "\"type\":\"" + EVENT_TYPE + "\","
                    + "\"data\":{\"object\":{\"secret_canary\":\"DO_NOT_LOG_ME_42\"}}}";
            APIGatewayV2HTTPEvent event = httpEventWithBody(secretBody,
                    "t=" + FIXED_NOW_EPOCH + ",v1=00000000000000000000000000000000");

            processor.process(event);

            for (ch.qos.logback.classic.spi.ILoggingEvent log : appender.list) {
                String formatted = log.getFormattedMessage();
                assertThat(formatted)
                        .as("log line %s must not echo request body", formatted)
                        .doesNotContain("DO_NOT_LOG_ME_42")
                        .doesNotContain("secret_canary");
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void invalidSignatureReturns400WithGenericMessageAndDoesNotTouchDdb() {
        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                "t=" + FIXED_NOW_EPOCH + ",v1=00000000000000000000000000000000");

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
        // Tomás's veto: generic message, no detail leakage.
        assertThat(response.getBody()).isEqualTo("{\"error\":\"invalid request\"}");
        verify(idempotencyStore, never()).recordFirstDelivery(any(), any(), any());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void missingSignatureHeaderReturns400() {
        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY, null);

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
        verify(idempotencyStore, never()).recordFirstDelivery(any(), any(), any());
    }

    @Test
    void staleTimestampReturns400() {
        long staleTs = FIXED_NOW_EPOCH - StripeSignatureVerifier.TIMESTAMP_TOLERANCE_SECONDS - 1;
        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                buildSignatureHeader(staleTs, VALID_BODY, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
        verify(idempotencyStore, never()).recordFirstDelivery(any(), any(), any());
    }

    @Test
    void futureTimestampReturns400() {
        long futureTs = FIXED_NOW_EPOCH + StripeSignatureVerifier.TIMESTAMP_TOLERANCE_SECONDS + 1;
        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                buildSignatureHeader(futureTs, VALID_BODY, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
    }

    @Test
    void malformedJsonBodyReturns400AfterValidSignature() {
        // HMAC-valid but not-a-json body — this is a misconfigured client,
        // not an attacker (HMAC already verified).
        String body = "not-json-at-all";
        APIGatewayV2HTTPEvent event = httpEventWithBody(body,
                buildSignatureHeader(FIXED_NOW_EPOCH, body, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
        verify(idempotencyStore, never()).recordFirstDelivery(any(), any(), any());
    }

    @Test
    void bodyWithoutEventIdReturns400() {
        String body = "{\"type\":\"payment_intent.succeeded\"}";
        APIGatewayV2HTTPEvent event = httpEventWithBody(body,
                buildSignatureHeader(FIXED_NOW_EPOCH, body, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
        verify(idempotencyStore, never()).recordFirstDelivery(any(), any(), any());
    }

    @Test
    void unknownEventTypeStillReturns200AndRecordsAndDispatches() {
        // Forward-compatible: receiving an event we don't recognise is
        // common (Stripe sends many types). PAY-02 acknowledges all.
        String body = "{\"id\":\"" + EVENT_ID + "\",\"type\":\"customer.subscription.deleted\"}";
        when(idempotencyStore.recordFirstDelivery(eq(EVENT_ID), eq("stripe"), any(Instant.class)))
                .thenReturn(RecordResult.FIRST_DELIVERY);

        APIGatewayV2HTTPEvent event = httpEventWithBody(body,
                buildSignatureHeader(FIXED_NOW_EPOCH, body, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
        ArgumentCaptor<StripeWebhookEvent> captor = ArgumentCaptor.forClass(StripeWebhookEvent.class);
        verify(dispatcher, times(1)).dispatch(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("customer.subscription.deleted");
        assertThat(captor.getValue().eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void caseInsensitiveSignatureHeaderLookup() {
        // API Gateway v2 lowercases by spec, but defend against test
        // fixtures and proxies that pass mixed case.
        when(idempotencyStore.recordFirstDelivery(eq(EVENT_ID), eq("stripe"), any(Instant.class)))
                .thenReturn(RecordResult.FIRST_DELIVERY);

        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setBody(VALID_BODY);
        event.setHeaders(Map.of("Stripe-Signature",
                buildSignatureHeader(FIXED_NOW_EPOCH, VALID_BODY, SECRET_VALUE)));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test
    void nullBodyTreatedAsEmptyAndRejectedAsMalformed() {
        // No body but signature header present — verify against empty
        // string and then fail JSON parsing.
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setBody(null);
        event.setHeaders(Map.of("stripe-signature",
                buildSignatureHeader(FIXED_NOW_EPOCH, "", SECRET_VALUE)));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(400);
    }

    @Test
    void dispatchRuntimeExceptionStillReturns200() {
        // Idempotency row is already written, so future replay short-
        // circuits. We must not 5xx Stripe (it would retry) and must not
        // leak a server-side error to the wire.
        when(idempotencyStore.recordFirstDelivery(eq(EVENT_ID), eq("stripe"), any(Instant.class)))
                .thenReturn(RecordResult.FIRST_DELIVERY);
        org.mockito.Mockito.doThrow(new RuntimeException("PAY-05 not implemented"))
                .when(dispatcher).dispatch(any());

        APIGatewayV2HTTPEvent event = httpEventWithBody(VALID_BODY,
                buildSignatureHeader(FIXED_NOW_EPOCH, VALID_BODY, SECRET_VALUE));

        APIGatewayV2HTTPResponse response = processor.process(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    // --- helpers ---

    private static APIGatewayV2HTTPEvent httpEventWithBody(String body, String signatureHeader) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setBody(body);
        if (signatureHeader != null) {
            event.setHeaders(Map.of("stripe-signature", signatureHeader));
        }
        return event;
    }

    private static String buildSignatureHeader(long timestamp, String body, String signingSecret) {
        return "t=" + timestamp + ",v1=" + hmacHex(signingSecret, timestamp + "." + body);
    }

    private static String hmacHex(String key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
