package com.functorful.stripewebhook.dispatch.handlers;

import com.functorful.stripewebhook.dispatch.EventHandler;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import io.micronaut.context.annotation.Bean;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler for {@code payment_intent.succeeded} (PAY-05).
 *
 * <p><strong>Phase 2 status: SCAFFOLD WITH TYPED EVENT.</strong> The
 * dispatcher now passes a {@link StripeWebhookEvent} carrying the
 * parsed {@code event.data.object} JSON node, which is the input shape
 * the real handler body needs. The body itself (4-item
 * {@code TransactWriteItems} + AuditLog row + SES email — see
 * {@code docs/pay-05-handler-design.md} §4.1) lands in a follow-up MR
 * once:
 *
 * <ul>
 *   <li>The infrastructure repo's IAM scope expansion lands (DDB
 *       grants on {@code InvestmentReservation} / {@code UserInvestment}
 *       / {@code AuditLog} tables, {@code ses:SendEmail} on the
 *       from-address identity, 5 new env vars wired through the SSM
 *       bridge).</li>
 *   <li>The payment-lambda repo extends the Stripe PaymentIntent
 *       metadata block to include {@code paymentRowId} and
 *       {@code paymentRowVersion} so this handler can do a direct
 *       DDB {@code GetItem} instead of a reservation-FK Query +
 *       in-code filter on {@code stripePaymentIntentId}. (See
 *       {@code docs/pay-05-handler-design.md} §4.1 for the
 *       reservation-FK fallback path; the metadata-extension path is
 *       a small follow-up that simplifies the lookup.)</li>
 * </ul>
 *
 * <p>Today's body logs the event with the Stripe-Dashboard-correlable
 * fields extracted from {@code event.data.object} (the
 * {@code paymentIntentId} and the metadata block). This is a strict
 * superset of PAY-02's no-op log and gives ops visibility into what
 * the handler will eventually act on, without touching any business
 * state.
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.succeeded")
public class PaymentIntentSucceededHandler implements EventHandler {

    @Override
    @NewSpan
    public void handle(StripeWebhookEvent event) {
        // PAY-05 follow-up MR replaces this body with the real
        // TransactWriteItems + AuditLog + SES email implementation.
        // See docs/pay-05-handler-design.md §4.1.
        String paymentIntentId = event.dataObject().path("id").asText("");
        log.info(
                "payment_intent.succeeded received; PAY-05 follow-up MR "
                        + "implements the TransactWriteItems + AuditLog + SES email. "
                        + "eventId={} paymentIntentId={}",
                event.eventId(),
                paymentIntentId
        );
    }
}
