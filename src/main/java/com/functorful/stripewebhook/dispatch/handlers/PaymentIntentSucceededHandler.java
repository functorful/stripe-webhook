package com.functorful.stripewebhook.dispatch.handlers;

import com.functorful.stripewebhook.dispatch.EventHandler;
import io.micronaut.context.annotation.Bean;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler for {@code payment_intent.succeeded} (PAY-05).
 *
 * <p><strong>Phase-1 status: SCAFFOLD.</strong> This MR wires the
 * dispatcher routing in place; the actual business logic (4-item
 * {@code TransactWriteItems}, AuditLog row, SES email — see
 * {@code docs/pay-05-handler-design.md} §4.1) lands in a follow-up MR
 * once the IAM scope expansion (infrastructure repo) and table-name SSM
 * bridge are in place.
 *
 * <p>Today: log + return. Stripe gets 200 OK. Same observable behaviour
 * as PAY-02's no-op dispatcher, but with the routing path now exercising
 * the real bean so the follow-up MR's diff is purely the handler body.
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.succeeded")
public class PaymentIntentSucceededHandler implements EventHandler {

    @Override
    @NewSpan
    public void handle(String eventType, String eventId) {
        // PAY-05 follow-up MR replaces this body. See docs/pay-05-handler-design.md §4.1.
        log.info("payment_intent.succeeded received; PAY-05 follow-up MR implements the "
                        + "TransactWriteItems + AuditLog + SES email. eventId={}",
                eventId);
    }
}
