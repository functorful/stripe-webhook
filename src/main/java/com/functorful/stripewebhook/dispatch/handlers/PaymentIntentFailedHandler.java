package com.functorful.stripewebhook.dispatch.handlers;

import com.functorful.stripewebhook.dispatch.EventHandler;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import io.micronaut.context.annotation.Bean;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler for {@code payment_intent.payment_failed} (PAY-05).
 *
 * <p><strong>Phase 2 status: SCAFFOLD WITH TYPED EVENT.</strong> Same
 * shape as {@link PaymentIntentSucceededHandler} — this MR delivers
 * the typed event signature so the follow-up MR's diff is the body
 * only. See {@code docs/pay-05-handler-design.md} §4.2 for the real
 * write sequence.
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.payment_failed")
public class PaymentIntentFailedHandler implements EventHandler {

    @Override
    @NewSpan
    public void handle(StripeWebhookEvent event) {
        // PAY-05 follow-up MR replaces this body with the real
        // TransactWriteItems + AuditLog implementation.
        // See docs/pay-05-handler-design.md §4.2.
        String paymentIntentId = event.dataObject().path("id").asText("");
        String lastError = event.dataObject().path("last_payment_error")
                .path("message").asText("");
        log.info(
                "payment_intent.payment_failed received; PAY-05 follow-up MR "
                        + "implements the TransactWriteItems + AuditLog. "
                        + "eventId={} paymentIntentId={} lastErrorMessageHash={}",
                event.eventId(),
                paymentIntentId,
                lastError.isEmpty() ? "(none)" : lastError.hashCode()
        );
    }
}
