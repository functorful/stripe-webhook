package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.functorful.stripewebhook.dispatch.WebhookEventDispatcher;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore.RecordResult;
import com.functorful.stripewebhook.secret.StripeWebhookSigningSecret;
import com.functorful.stripewebhook.signature.InvalidSignatureException;
import com.functorful.stripewebhook.signature.StripeSignatureVerifier;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

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
 */
@Slf4j
@Singleton
public class WebhookEventProcessor {

    private static final String STRIPE_SIGNATURE_HEADER = "stripe-signature";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StripeSignatureVerifier signatureVerifier;
    private final StripeWebhookSigningSecret signingSecret;
    private final WebhookIdempotencyStore idempotencyStore;
    private final WebhookEventDispatcher dispatcher;
    private final Clock clock;

    public WebhookEventProcessor(
            StripeSignatureVerifier signatureVerifier,
            StripeWebhookSigningSecret signingSecret,
            WebhookIdempotencyStore idempotencyStore,
            WebhookEventDispatcher dispatcher,
            Clock clock
    ) {
        this.signatureVerifier = signatureVerifier;
        this.signingSecret = signingSecret;
        this.idempotencyStore = idempotencyStore;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /**
     * Verify → dedup → dispatch. See {@link FunctionRequestHandler} for
     * the failure mapping.
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

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawBody);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Stripe webhook body is not valid JSON. errorClass={}", e.getClass().getSimpleName());
            return badRequest();
        }

        String eventId = textOrNull(root, "id");
        String eventType = textOrNull(root, "type");

        if (eventId == null) {
            log.warn("Stripe webhook body missing `id` field; rejecting.");
            return badRequest();
        }

        Instant now = clock.instant();
        RecordResult result = idempotencyStore.recordFirstDelivery(eventId, "stripe", now);
        if (result == RecordResult.REPLAY) {
            return ok();
        }

        StripeWebhookEvent event = buildEvent(eventId, eventType, root, now);
        try {
            dispatcher.dispatch(event);
        } catch (RuntimeException e) {
            // Idempotency row is already written; replay would short-
            // circuit. Don't 5xx Stripe (it would retry); don't leak
            // server-side error to the wire.
            log.error("Webhook event dispatch failed; row already recorded. eventId={} errorClass={}",
                    eventId, e.getClass().getSimpleName(), e);
            return ok();
        }

        return ok();
    }

    /**
     * Build the typed event passed to handlers. Pulls
     * {@code data.object} as the type-specific payload (handlers walk
     * it themselves), and {@code event.created} (epoch second) as an
     * {@link Instant} — falls back to {@code now} if Stripe omitted it
     * (the webhook stream always includes it; this is defensive).
     */
    private static StripeWebhookEvent buildEvent(
            String eventId,
            String eventType,
            JsonNode root,
            Instant now
    ) {
        JsonNode dataObject = root.path("data").path("object");
        if (dataObject.isMissingNode() || dataObject.isNull()) {
            dataObject = MissingNode.getInstance();
        }
        Instant created = root.has("created") && root.get("created").isNumber()
                ? Instant.ofEpochSecond(root.get("created").asLong())
                : now;
        return new StripeWebhookEvent(eventId, eventType, created, dataObject);
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

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || !root.has(field)) {
            return null;
        }
        JsonNode node = root.get(field);
        return node.isTextual() ? node.asText() : null;
    }
}
