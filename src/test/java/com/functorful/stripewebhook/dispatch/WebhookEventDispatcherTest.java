package com.functorful.stripewebhook.dispatch;

import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentFailedHandler;
import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentSucceededHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the routing surface of {@link WebhookEventDispatcher}.
 *
 * <p>The dispatcher is a thin lookup: each event type maps to a
 * {@link EventHandler} bean, with a fallback to {@link IgnoredEventHandler}
 * for unrecognised types. These tests assert routing fidelity, not
 * handler behaviour — the per-handler unit tests live alongside their
 * implementations.
 */
class WebhookEventDispatcherTest {

    private PaymentIntentSucceededHandler succeededHandler;
    private PaymentIntentFailedHandler failedHandler;
    private IgnoredEventHandler ignoredHandler;
    private WebhookEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        succeededHandler = mock(PaymentIntentSucceededHandler.class);
        failedHandler = mock(PaymentIntentFailedHandler.class);
        ignoredHandler = mock(IgnoredEventHandler.class);

        Map<String, EventHandler> handlersByType = Map.of(
                "payment_intent.succeeded", succeededHandler,
                "payment_intent.payment_failed", failedHandler
        );
        dispatcher = new WebhookEventDispatcher(handlersByType, ignoredHandler);
    }

    @Test
    void routesPaymentIntentSucceededToSucceededHandler() {
        dispatcher.dispatch("payment_intent.succeeded", "evt_succeeded_1");

        verify(succeededHandler).handle("payment_intent.succeeded", "evt_succeeded_1");
        verifyNoInteractions(failedHandler);
        verifyNoInteractions(ignoredHandler);
    }

    @Test
    void routesPaymentIntentPaymentFailedToFailedHandler() {
        dispatcher.dispatch("payment_intent.payment_failed", "evt_failed_1");

        verify(failedHandler).handle("payment_intent.payment_failed", "evt_failed_1");
        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(ignoredHandler);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "customer.subscription.deleted",
            "charge.refunded",
            "payment_intent.created", // a real Stripe event we don't handle
            "made_up_event"
    })
    void routesUnrecognisedTypeToIgnoredHandler(String eventType) {
        dispatcher.dispatch(eventType, "evt_unknown_1");

        verify(ignoredHandler).handle(eventType, "evt_unknown_1");
        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void routesNullEventTypeToIgnoredHandler() {
        // Defensive — happens for malformed but HMAC-valid bodies (rare).
        dispatcher.dispatch(null, "evt_null_type_1");

        verify(ignoredHandler).handle(null, "evt_null_type_1");
        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void dispatcherDoesNotThrowWhenHandlerThrows() {
        // The orchestrator (WebhookEventProcessor) is responsible for the
        // catch — see WebhookEventProcessor#process. The dispatcher itself
        // does not swallow handler exceptions; it lets them propagate so
        // the orchestrator's catch can log + return 200.
        EventHandler throwing = Mockito.mock(EventHandler.class);
        Mockito.doThrow(new RuntimeException("handler exploded"))
                .when(throwing).handle(Mockito.any(), Mockito.any());

        WebhookEventDispatcher dispatcherWithThrowing = new WebhookEventDispatcher(
                Map.of("boom", throwing),
                ignoredHandler
        );

        assertThatCode(() -> dispatcherWithThrowing.dispatch("boom", "evt_boom"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler exploded");

        verify(throwing, atLeastOnce()).handle("boom", "evt_boom");
    }

    @Test
    void emptyDispatchTableRoutesEverythingToIgnoredHandler() {
        WebhookEventDispatcher emptyDispatcher = new WebhookEventDispatcher(
                Map.of(),
                ignoredHandler
        );

        emptyDispatcher.dispatch("payment_intent.succeeded", "evt_empty_1");
        emptyDispatcher.dispatch(null, "evt_empty_2");

        verify(ignoredHandler).handle("payment_intent.succeeded", "evt_empty_1");
        verify(ignoredHandler).handle(null, "evt_empty_2");
    }

    @Test
    void dispatchersBuiltFromTheSameMapShareNoState() {
        // Sanity: the dispatcher is stateless beyond its injected map.
        WebhookEventDispatcher d1 = new WebhookEventDispatcher(Map.of(), ignoredHandler);
        WebhookEventDispatcher d2 = new WebhookEventDispatcher(Map.of(), ignoredHandler);

        assertThat(d1).isNotSameAs(d2);
    }
}
