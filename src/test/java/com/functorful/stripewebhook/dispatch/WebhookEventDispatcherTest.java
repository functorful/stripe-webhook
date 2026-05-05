package com.functorful.stripewebhook.dispatch;

import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentFailedHandler;
import com.functorful.stripewebhook.dispatch.handlers.PaymentIntentSucceededHandler;
import com.functorful.stripewebhook.event.PaymentIntentObject;
import com.functorful.stripewebhook.event.StripeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit coverage for the routing surface of {@link WebhookEventDispatcher}.
 *
 * <p>Post-ARCH-08 the dispatcher's contract is a switch expression on the
 * {@link StripeEvent} sealed hierarchy. These tests assert routing
 * fidelity by feeding each variant and verifying exactly one handler is
 * called — no per-handler behaviour assertions; those live in the
 * per-handler unit tests alongside their implementations.
 */
class WebhookEventDispatcherTest {

    private static final Instant FIXED = Instant.parse("2026-05-03T10:00:00Z");

    private PaymentIntentSucceededHandler succeededHandler;
    private PaymentIntentFailedHandler failedHandler;
    private WebhookEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        succeededHandler = mock(PaymentIntentSucceededHandler.class);
        failedHandler = mock(PaymentIntentFailedHandler.class);
        dispatcher = new WebhookEventDispatcher(succeededHandler, failedHandler);
    }

    @Test
    void routesPaymentIntentSucceededVariantToSucceededHandler() {
        StripeEvent.PaymentIntentSucceeded event = new StripeEvent.PaymentIntentSucceeded(
                "evt_succeeded_1", FIXED, samplePayload());

        dispatcher.dispatch(event);

        verify(succeededHandler).handle(event);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void routesPaymentIntentFailedVariantToFailedHandler() {
        StripeEvent.PaymentIntentFailed event = new StripeEvent.PaymentIntentFailed(
                "evt_failed_1", FIXED, samplePayload());

        dispatcher.dispatch(event);

        verify(failedHandler).handle(event);
        verifyNoInteractions(succeededHandler);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "customer.subscription.deleted",
            "charge.refunded",
            "payment_intent.created",  // a real Stripe event we don't handle
            "made_up_event"
    })
    void routesIgnoredVariantWithoutTouchingTypedHandlers(String unrecognisedType) {
        StripeEvent.Ignored event = new StripeEvent.Ignored(
                "evt_unknown_1", FIXED, unrecognisedType);

        dispatcher.dispatch(event);

        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void routesIgnoredVariantWithNullTypeWithoutTouchingTypedHandlers() {
        // Defensive — happens for malformed but HMAC-valid bodies (rare).
        // Boundary parser sets unrecognisedType=null in this path.
        StripeEvent.Ignored event = new StripeEvent.Ignored(
                "evt_null_type_1", FIXED, null);

        dispatcher.dispatch(event);

        verifyNoInteractions(succeededHandler);
        verifyNoInteractions(failedHandler);
    }

    @Test
    void dispatcherDoesNotSwallowHandlerExceptions() {
        // The orchestrator (WebhookEventProcessor) is responsible for the
        // catch — see WebhookEventProcessor#process. The dispatcher itself
        // does not swallow handler exceptions; it lets them propagate so
        // the orchestrator's catch can log + return 200.
        doThrow(new RuntimeException("handler exploded"))
                .when(succeededHandler).handle(org.mockito.Mockito.any());

        StripeEvent.PaymentIntentSucceeded event = new StripeEvent.PaymentIntentSucceeded(
                "evt_boom", FIXED, samplePayload());

        assertThatCode(() -> dispatcher.dispatch(event))
                .as("handler exception must propagate; dispatcher does not swallow")
                .isInstanceOf(RuntimeException.class)
                .hasMessage("handler exploded");

        verify(succeededHandler, atLeastOnce()).handle(event);
    }

    private static PaymentIntentObject samplePayload() {
        return new PaymentIntentObject("pi_test", null, Map.of(), null);
    }
}
