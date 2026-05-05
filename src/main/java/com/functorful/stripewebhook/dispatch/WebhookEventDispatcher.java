package com.functorful.stripewebhook.dispatch;

import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentFailedHandler;
import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentSucceededHandler;
import com.functorful.stripewebhook.event.StripeEvent;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Routes verified, deduplicated webhook events to the matching typed
 * handler. Replaces the PAY-02 / PAY-05 string-keyed
 * {@code Map<String, EventHandler>} dispatch table with a
 * compile-time-exhaustive pattern match on the {@link StripeEvent}
 * sealed hierarchy.
 *
 * <p><strong>ADR-0008 sealed-completeness contract.</strong> The switch
 * expression below has NO {@code default} branch — when a new variant is
 * added to {@link StripeEvent}, this class fails to compile until the
 * matching {@code case} is added. This is the structural fix for the
 * "no fall-through to handler-via-default-branch" review priority Tomás
 * locked when ARCH-08 was queued.
 *
 * <p><strong>{@link StripeEvent.Ignored} is a first-class case.</strong>
 * Events Stripe sends that we don't subscribe to (or whose {@code type}
 * field is null on a malformed-but-HMAC-valid body) land here as the
 * explicit {@code Ignored} variant, never as a {@code null}-from-map-lookup
 * miss.
 *
 * <p><strong>Adding a new event type.</strong>
 * <ol>
 *   <li>Add a {@code permits} entry + record to {@link StripeEvent}.</li>
 *   <li>Map the wire-event-type string to the new variant in
 *       {@code WebhookEventProcessor.toStripeEvent}.</li>
 *   <li>Add a {@code case} to the switch below — the build fails until
 *       you do.</li>
 * </ol>
 *
 * <p>See {@code docs/pay-05-handler-design.md} for the per-handler
 * contract details.
 */
@Slf4j
@Singleton
public class WebhookEventDispatcher {

    private final PaymentIntentSucceededHandler succeededHandler;
    private final PaymentIntentFailedHandler failedHandler;

    public WebhookEventDispatcher(
            PaymentIntentSucceededHandler succeededHandler,
            PaymentIntentFailedHandler failedHandler
    ) {
        this.succeededHandler = succeededHandler;
        this.failedHandler = failedHandler;
    }

    /**
     * Dispatch the given event to the matching handler. The pattern
     * match is exhaustive; the compiler enforces a case for every
     * {@link StripeEvent} variant.
     */
    @NewSpan
    public void dispatch(StripeEvent event) {
        switch (event) {
            case StripeEvent.PaymentIntentSucceeded e -> succeededHandler.handle(e);
            case StripeEvent.PaymentIntentFailed e -> failedHandler.handle(e);
            case StripeEvent.Ignored e -> handleIgnored(e);
        }
    }

    /**
     * Inlined replacement for the old {@code IgnoredEventHandler} bean.
     * Acks the event with a debug log line and returns; Stripe gets 200
     * via the orchestrator so it doesn't retry.
     *
     * <p>The {@code unrecognisedType} field is for debug visibility only —
     * the dispatcher MUST NOT branch on it (defeats the gadget-defence
     * sealed-hierarchy property — handlers could end up routing on
     * attacker-controlled wire data).
     */
    private static void handleIgnored(StripeEvent.Ignored event) {
        if (event.unrecognisedType() == null) {
            log.info("Webhook event has null type; acked + ignored. eventId={}",
                    event.eventId());
        } else {
            log.info("Webhook event type unrecognised; acked + ignored. eventId={} eventType={}",
                    event.eventId(), event.unrecognisedType());
        }
    }
}
