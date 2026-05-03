package com.functorful.stripewebhook.dispatch.handlers;

import com.functorful.stripewebhook.dispatch.EventHandler;
import io.micronaut.context.annotation.Bean;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler for {@code payment_intent.payment_failed} (PAY-05).
 *
 * <p><strong>Phase-1 status: SCAFFOLD.</strong> Same as
 * {@link PaymentIntentSucceededHandler} — this MR wires routing; the
 * follow-up MR replaces the body with the real
 * {@code TransactWriteItems} + AuditLog logic
 * (see {@code docs/pay-05-handler-design.md} §4.2).
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.payment_failed")
public class PaymentIntentFailedHandler implements EventHandler {

    @Override
    @NewSpan
    public void handle(String eventType, String eventId) {
        // PAY-05 follow-up MR replaces this body. See docs/pay-05-handler-design.md §4.2.
        log.info("payment_intent.payment_failed received; PAY-05 follow-up MR implements "
                        + "the TransactWriteItems + AuditLog. eventId={}",
                eventId);
    }
}
