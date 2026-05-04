package com.functorful.stripewebhook.dispatch;

import com.functorful.stripewebhook.event.StripeWebhookEvent;
import io.micronaut.context.annotation.Bean;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Default handler for any event type that is not in the dispatch table.
 * Logs at INFO and returns. Stripe sends many event types we never
 * subscribed to if the endpoint is configured to "send all events", and
 * forward-compatible code must not 5xx on unrecognised types.
 *
 * <p>Also handles the rare but legal case of a {@code null} event type
 * — a body that passed HMAC + JSON parse but is missing the
 * {@code "type"} field.
 */
@Slf4j
@Singleton
@Bean
@Named("ignored")
public class IgnoredEventHandler implements EventHandler {

    @Override
    public void handle(StripeWebhookEvent event) {
        if (event.eventType() == null) {
            log.info("Webhook event has null type; skipping dispatch. eventId={}",
                    event.eventId());
        } else {
            log.info("Webhook event type unrecognised; ignoring. eventId={} eventType={}",
                    event.eventId(), event.eventType());
        }
    }
}
