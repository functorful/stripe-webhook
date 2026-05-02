package com.functorful.stripewebhook.dispatch;

import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Empty-handler skeleton dispatcher. PAY-02 establishes the verify →
 * dedup → dispatch plumbing and returns 200 fast for both known and
 * unknown event types; PAY-05 fills in the actual
 * {@code payment_intent.succeeded} / {@code .payment_failed} handlers.
 *
 * <p>Unknown event types are common in webhook streams (Stripe sends
 * many event types we never subscribed to if the endpoint is configured
 * to "send all events", and forward-compatible code must not 5xx on
 * unrecognised types). They log + return success.
 */
@Slf4j
@Singleton
public class WebhookEventDispatcher {

    /**
     * Event types PAY-05 will eventually handle. Listed here so the
     * dispatcher logs them differently from genuinely unknown types
     * (helps ops identify "we received it but didn't process it because
     * PAY-05 isn't merged yet" vs. "we got an event we don't recognise
     * at all").
     */
    private static final java.util.Set<String> KNOWN_EVENT_TYPES = java.util.Set.of(
            "payment_intent.succeeded",
            "payment_intent.payment_failed"
    );

    /**
     * Dispatch the given event for processing. PAY-02 returns success
     * for all paths; PAY-05 will route known types to handlers.
     *
     * @param eventType the {@code type} field of the Stripe webhook event
     *                  (e.g. {@code payment_intent.succeeded}). May be
     *                  {@code null} if the body is malformed; treated as
     *                  unknown.
     * @param eventId   the {@code id} of the event, included in the log
     *                  line for traceability.
     */
    @NewSpan
    public void dispatch(String eventType, String eventId) {
        if (eventType == null) {
            log.warn("Webhook event has null type; skipping dispatch. eventId={}", eventId);
            return;
        }
        if (KNOWN_EVENT_TYPES.contains(eventType)) {
            log.info("Webhook event type known but not yet handled (PAY-05 follow-up). "
                    + "eventId={} eventType={}", eventId, eventType);
            return;
        }
        log.info("Webhook event type unrecognised; ignoring. eventId={} eventType={}",
                eventId, eventType);
    }
}
