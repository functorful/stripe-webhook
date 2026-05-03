package com.functorful.stripewebhook.dispatch;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The ignored handler is the dispatch table's fallback. It must never
 * throw, must accept any event-type string (including {@code null}), and
 * must produce no observable side effect beyond a log line.
 */
class IgnoredEventHandlerTest {

    private final IgnoredEventHandler handler = new IgnoredEventHandler();

    @ParameterizedTest
    @ValueSource(strings = {
            "customer.subscription.deleted",
            "charge.refunded",
            "payment_intent.created",
            "made_up_event"
    })
    void neverThrowsForAnyEventType(String eventType) {
        StripeWebhookEvent event = newEvent("evt_ignored_1", eventType);
        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
    }

    @Test
    void neverThrowsForNullEventType() {
        StripeWebhookEvent event = newEvent("evt_null_type_1", null);
        assertThatCode(() -> handler.handle(event)).doesNotThrowAnyException();
    }

    private static StripeWebhookEvent newEvent(String eventId, String eventType) {
        return new StripeWebhookEvent(
                eventId,
                eventType,
                Instant.parse("2026-05-03T10:00:00Z"),
                JsonNodeFactory.instance.objectNode()
        );
    }
}
