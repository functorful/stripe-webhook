package com.functorful.stripewebhook.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThatCode(() -> handler.handle(eventType, "evt_ignored_1"))
                .doesNotThrowAnyException();
    }

    @Test
    void neverThrowsForNullEventType() {
        assertThatCode(() -> handler.handle(null, "evt_null_1"))
                .doesNotThrowAnyException();
    }

    @Test
    void neverThrowsForNullEventId() {
        // Defensive — eventId comes from the body's id field, which the
        // processor validates non-null before reaching dispatch. Belt and
        // braces in case the orchestrator order changes.
        assertThatCode(() -> handler.handle("payment_intent.created", null))
                .doesNotThrowAnyException();
    }
}
