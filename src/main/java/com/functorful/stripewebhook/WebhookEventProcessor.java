package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.functorful.stripewebhook.dispatch.WebhookEventDispatcher;
import com.functorful.stripewebhook.event.StripeEvent;
import com.functorful.stripewebhook.event.wire.StripeEventEnvelope;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore.RecordResult;
import com.functorful.stripewebhook.secret.StripeWebhookSigningSecret;
import com.functorful.stripewebhook.signature.InvalidSignatureException;
import com.functorful.stripewebhook.signature.StripeSignatureVerifier;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Pure orchestration logic for the StripeWebhookLambda — separated from
 * {@link FunctionRequestHandler} so it can be unit-tested without bringing
 * up a Micronaut application context. The handler is a thin wrapper that
 * lets Micronaut wire this bean and delegates {@link #process} to it.
 *
 * <p>See {@link FunctionRequestHandler} for the high-level flow
 * description.
 *
 * <p><strong>ADR-0008 boundary parser.</strong> This class is the single
 * trust-boundary parser for inbound Stripe webhook bodies. It performs
 * three layered checks, each fail-closed:
 *
 * <ol>
 *   <li><strong>HMAC signature verification</strong> — invalid →
 *       {@code 400 Bad Request}, no parse attempt. Defends against
 *       attacker-controlled payloads even reaching the JSON parser.</li>
 *   <li><strong>JSON shape via Micronaut Serde {@link ObjectMapper}</strong>
 *       — parses the raw body into the typed {@link StripeEventEnvelope}.
 *       A malformed-JSON or missing-required-field body raises a
 *       {@code SerdeException} (or {@link IOException}); both collapse to
 *       {@code 400 Bad Request}. The dispatcher only ever sees a
 *       well-formed typed envelope.</li>
 *   <li><strong>Type-discrimination switch</strong> —
 *       {@link #toStripeEvent(StripeEventEnvelope, Instant)} maps the wire
 *       {@code type} string to the matching {@link StripeEvent} variant.
 *       Unrecognised types map to {@link StripeEvent.Ignored} (a
 *       first-class variant — never a {@code null} from a map miss). The
 *       dispatcher's switch then routes {@code Ignored} to the
 *       ack-and-log-only branch.</li>
 * </ol>
 *
 * <p><strong>No untyped {@code JsonNode}</strong> reaches the dispatcher
 * or any handler. The handlers receive a typed
 * {@link com.functorful.stripewebhook.event.PaymentIntentObject} payload
 * directly; per-handler re-parsing of {@code data.object} is structurally
 * impossible.
 */
@Slf4j
@Singleton
public class WebhookEventProcessor {

    private static final String STRIPE_SIGNATURE_HEADER = "stripe-signature";
    private static final String EVENT_TYPE_PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";
    private static final String EVENT_TYPE_PAYMENT_INTENT_FAILED = "payment_intent.payment_failed";

    private final StripeSignatureVerifier signatureVerifier;
    private final StripeWebhookSigningSecret signingSecret;
    private final WebhookIdempotencyStore idempotencyStore;
    private final WebhookEventDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookEventProcessor(
            StripeSignatureVerifier signatureVerifier,
            StripeWebhookSigningSecret signingSecret,
            WebhookIdempotencyStore idempotencyStore,
            WebhookEventDispatcher dispatcher,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.signatureVerifier = signatureVerifier;
        this.signingSecret = signingSecret;
        this.idempotencyStore = idempotencyStore;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Verify → parse → dedup → dispatch. See {@link FunctionRequestHandler}
     * for the failure mapping.
     */
    @NewSpan
    public APIGatewayV2HTTPResponse process(APIGatewayV2HTTPEvent input) {
        String rawBody = input.getBody() == null ? "" : input.getBody();

        try {
            String signatureHeader = readHeaderCaseInsensitive(input, STRIPE_SIGNATURE_HEADER);
            // Tomás veto: do NOT echo rawBody in the failure log path.
            // The exception message is parameterised and contains only
            // the failure category, never body content.
            signatureVerifier.verify(signatureHeader, rawBody, signingSecret);
        } catch (InvalidSignatureException e) {
            log.warn("Stripe webhook signature verification failed. reason={}", e.getMessage());
            return badRequest();
        }

        StripeEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(rawBody, StripeEventEnvelope.class);
        } catch (IOException | RuntimeException e) {
            // Both io.micronaut.serde.exceptions.SerdeException and any
            // wrapped IOException collapse here. Class name only — no
            // message body in the log line (Stripe-supplied bytes that
            // failed to parse may contain attacker-controlled fragments).
            log.warn("Stripe webhook body failed Serde parse. errorClass={}",
                    e.getClass().getSimpleName());
            return badRequest();
        }

        if (envelope.id() == null || envelope.id().isEmpty()) {
            log.warn("Stripe webhook envelope missing `id` field; rejecting.");
            return badRequest();
        }

        Instant now = clock.instant();
        RecordResult result = idempotencyStore.recordFirstDelivery(envelope.id(), "stripe", now);
        if (result == RecordResult.REPLAY) {
            return ok();
        }

        StripeEvent event = toStripeEvent(envelope, now);
        try {
            dispatcher.dispatch(event);
        } catch (RuntimeException e) {
            // Idempotency row is already written; replay would short-
            // circuit. Don't 5xx Stripe (it would retry); don't leak
            // server-side error to the wire.
            log.error("Webhook event dispatch failed; row already recorded. eventId={} errorClass={}",
                    envelope.id(), e.getClass().getSimpleName(), e);
            return ok();
        }

        return ok();
    }

    /**
     * Type-discrimination switch from wire envelope to typed
     * {@link StripeEvent}. Unrecognised types (Stripe sends events for
     * every subscribed type, including ones we don't consume) map to
     * {@link StripeEvent.Ignored} — explicitly, as a first-class variant.
     *
     * <p><strong>Adding a new event type</strong> = one new case here +
     * one new {@link StripeEvent} variant + one new dispatcher case (the
     * dispatcher's switch is exhaustive; the build fails until the case
     * is added).
     *
     * <p><strong>One-way mapping invariant.</strong> A {@link StripeEvent}
     * returned from this method MUST NOT be re-promoted to a typed variant
     * downstream — the variant returned is the final routing decision.
     * Specifically: if this method returns {@link StripeEvent.Ignored}
     * (because of unknown type, null type, or missing {@code data.object}),
     * downstream code (dispatcher, handlers, ops tooling) must treat it as
     * Ignored. Any "if Ignored.unrecognisedType equals X then upgrade to Y"
     * shape downstream defeats the gadget-defence sealed-hierarchy
     * property; the dispatcher would end up routing on attacker-influenced
     * wire data via a string side-channel.
     */
    static StripeEvent toStripeEvent(StripeEventEnvelope envelope, Instant fallbackOccurredAt) {
        Instant occurredAt = envelope.created() > 0
                ? Instant.ofEpochSecond(envelope.created())
                : fallbackOccurredAt;
        String type = envelope.type();

        if (type == null) {
            return new StripeEvent.Ignored(envelope.id(), occurredAt, null);
        }

        return switch (type) {
            case EVENT_TYPE_PAYMENT_INTENT_SUCCEEDED -> {
                if (envelope.data() == null || envelope.data().object() == null) {
                    // Wire shape claims a typed event but data.object is
                    // missing — the typed handler can't proceed without
                    // a payload. Demote to Ignored + log so the
                    // dispatcher acks Stripe (it would otherwise retry
                    // forever) and we have a forensics trace.
                    log.warn("Wire envelope claims {} but data.object is missing; demoting to Ignored. eventId={}",
                            EVENT_TYPE_PAYMENT_INTENT_SUCCEEDED, envelope.id());
                    yield new StripeEvent.Ignored(envelope.id(), occurredAt, type);
                }
                yield new StripeEvent.PaymentIntentSucceeded(
                        envelope.id(), occurredAt, envelope.data().object());
            }
            case EVENT_TYPE_PAYMENT_INTENT_FAILED -> {
                if (envelope.data() == null || envelope.data().object() == null) {
                    log.warn("Wire envelope claims {} but data.object is missing; demoting to Ignored. eventId={}",
                            EVENT_TYPE_PAYMENT_INTENT_FAILED, envelope.id());
                    yield new StripeEvent.Ignored(envelope.id(), occurredAt, type);
                }
                yield new StripeEvent.PaymentIntentFailed(
                        envelope.id(), occurredAt, envelope.data().object());
            }
            default -> new StripeEvent.Ignored(envelope.id(), occurredAt, type);
        };
    }

    private static APIGatewayV2HTTPResponse ok() {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(200);
        response.setHeaders(Map.of("Content-Type", "application/json"));
        response.setBody("{\"received\":true}");
        return response;
    }

    private static APIGatewayV2HTTPResponse badRequest() {
        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(400);
        response.setHeaders(Map.of("Content-Type", "application/json"));
        response.setBody("{\"error\":\"invalid request\"}");
        return response;
    }

    private static String readHeaderCaseInsensitive(APIGatewayV2HTTPEvent input, String name) {
        if (input.getHeaders() == null) {
            return null;
        }
        for (Map.Entry<String, String> e : input.getHeaders().entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
