package com.functorful.stripewebhook.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;

class WebhookEventDispatcherTest {

    private final WebhookEventDispatcher dispatcher = new WebhookEventDispatcher();

    @ParameterizedTest
    @ValueSource(strings = {"payment_intent.succeeded", "payment_intent.payment_failed"})
    void knownEventTypeIsAcceptedAsNoOp(String eventType) {
        // PAY-02: known types log + return without side effect.
        // PAY-05 will swap this for real handlers.
        assertThatCode(() -> dispatcher.dispatch(eventType, "evt_known_1"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "customer.subscription.deleted",
            "charge.refunded",
            "made_up_event"
    })
    void unknownEventTypeIsAcceptedAsNoOp(String eventType) {
        assertThatCode(() -> dispatcher.dispatch(eventType, "evt_unknown_1"))
                .doesNotThrowAnyException();
    }

    @Test
    void nullEventTypeIsAcceptedAsNoOp() {
        // Defensive — happens for malformed but HMAC-valid bodies (rare).
        assertThatCode(() -> dispatcher.dispatch(null, "evt_null_type_1"))
                .doesNotThrowAnyException();
    }
}
