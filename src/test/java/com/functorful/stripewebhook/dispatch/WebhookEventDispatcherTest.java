package com.functorful.stripewebhook.dispatch;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentFailedHandler;
import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentSucceededHandler;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
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
        StripeWebhookEvent event = newEvent("evt_succeeded_1", "payment_intent.succeeded");

        dispatcher.dispatch(event);

        verify(succeededHandler).handle(event);
        verifyNoInteractions(failedHandler);
        verifyNoInteractions(ignoredHandler);
    }

    @Test
    void routesPaymentIntentPaymentFailedToFailedHandler() {
        StripeWebhookEvent event = newEvent("evt_failed_1", "payment_intent.payment_failed");

        dispatcher.dispatch(event);

        verify(failedHandler).handle(event);
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
        StripeWebhookEvent event = newEvent("evt_unknown_1", eventType);

        dispatcher.dispatch(event);

        verify(ignoredHandler).handle(event);
        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void routesNullEventTypeToIgnoredHandler() {
        // Defensive — happens for malformed but HMAC-valid bodies (rare).
        StripeWebhookEvent event = newEvent("evt_null_type_1", null);

        dispatcher.dispatch(event);

        ArgumentCaptor<StripeWebhookEvent> captor = ArgumentCaptor.forClass(StripeWebhookEvent.class);
        verify(ignoredHandler).handle(captor.capture());
        assertThat(captor.getValue().eventType()).isNull();
        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void dispatcherDoesNotSwallowHandlerExceptions() {
        // The orchestrator (WebhookEventProcessor) is responsible for the
        // catch — see WebhookEventProcessor#process. The dispatcher itself
        // does not swallow handler exceptions; it lets them propagate so
        // the orchestrator's catch can log + return 200.
        EventHandler throwing = Mockito.mock(EventHandler.class);
        Mockito.doThrow(new RuntimeException("handler exploded"))
                .when(throwing).handle(Mockito.any());

        WebhookEventDispatcher dispatcherWithThrowing = new WebhookEventDispatcher(
                Map.of("boom", throwing),
                ignoredHandler
        );
        StripeWebhookEvent event = newEvent("evt_boom", "boom");

        assertThatCode(() -> dispatcherWithThrowing.dispatch(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler exploded");

        verify(throwing, atLeastOnce()).handle(event);
    }

    @Test
    void emptyDispatchTableRoutesEverythingToIgnoredHandler() {
        WebhookEventDispatcher emptyDispatcher = new WebhookEventDispatcher(
                Map.of(),
                ignoredHandler
        );
        StripeWebhookEvent succeeded = newEvent("evt_empty_1", "payment_intent.succeeded");
        StripeWebhookEvent nullType = newEvent("evt_empty_2", null);

        emptyDispatcher.dispatch(succeeded);
        emptyDispatcher.dispatch(nullType);

        verify(ignoredHandler).handle(succeeded);
        verify(ignoredHandler).handle(nullType);
    }

    @Test
    void dispatchersBuiltFromTheSameMapShareNoState() {
        // Sanity: the dispatcher is stateless beyond its injected map.
        WebhookEventDispatcher d1 = new WebhookEventDispatcher(Map.of(), ignoredHandler);
        WebhookEventDispatcher d2 = new WebhookEventDispatcher(Map.of(), ignoredHandler);

        assertThat(d1).isNotSameAs(d2);
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
