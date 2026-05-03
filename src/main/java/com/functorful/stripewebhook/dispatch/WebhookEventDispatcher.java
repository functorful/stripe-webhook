package com.functorful.stripewebhook.dispatch;

import com.functorful.stripewebhook.event.StripeWebhookEvent;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Routes verified, deduplicated webhook events to a per-type
 * {@link EventHandler} bean.
 *
 * <p>Routing model: a {@code Map<String, EventHandler>} keyed by Stripe
 * event type is constructed at injection time from all
 * {@code @Named("event.type")} {@link EventHandler} beans in the
 * application context. Dispatch is a single map lookup; misses fall
 * through to {@link IgnoredEventHandler}.
 *
 * <p>This shape replaces PAY-02's hard-coded {@code KNOWN_EVENT_TYPES}
 * set. New handlers are added by creating a {@code @Singleton @Named}
 * bean implementing {@link EventHandler} — no change to this class is
 * required.
 *
 * <p>See {@code docs/pay-05-handler-design.md} for the full PAY-05
 * dispatch table and per-handler contracts.
 */
@Slf4j
@Singleton
public class WebhookEventDispatcher {

    private final Map<String, EventHandler> handlersByType;
    private final IgnoredEventHandler ignoredHandler;

    /**
     * Micronaut wires every {@code @Named} {@link EventHandler} bean
     * into the {@code handlersByType} map keyed by the qualifier name.
     * The {@link IgnoredEventHandler} bean (also @Named, but as
     * {@code "ignored"}) is injected separately and reused for both
     * unrecognised event types and {@code null} types.
     *
     * <p>The framework rejects two beans claiming the same
     * {@code @Named} qualifier at startup, so the dispatch table is
     * structurally guaranteed to be conflict-free.
     */
    public WebhookEventDispatcher(
            Map<String, EventHandler> handlersByType,
            IgnoredEventHandler ignoredHandler
    ) {
        this.handlersByType = handlersByType;
        this.ignoredHandler = ignoredHandler;
    }

    /**
     * Dispatch the given event to the handler registered for its type.
     *
     * @param event the verified, parsed event — routes by
     *              {@link StripeWebhookEvent#eventType()}; falls
     *              through to {@link IgnoredEventHandler} when the
     *              type is {@code null} or unrecognised.
     */
    @NewSpan
    public void dispatch(StripeWebhookEvent event) {
        EventHandler handler = event.eventType() == null
                ? ignoredHandler
                : handlersByType.getOrDefault(event.eventType(), ignoredHandler);
        handler.handle(event);
    }
}
