package com.functorful.stripewebhook.dynamodb;

/**
 * Projected, immutable read of an {@code InvestmentPayment} row —
 * the fields PAY-05's webhook handlers need to status-guard and update
 * the row. Same Tomás-veto discipline as {@link ReservationView}: don't
 * project anything we don't act on.
 */
public record PaymentView(
        String id,
        long version,
        String userId,
        long amountCents,
        String currency,
        String stripePaymentIntentId,
        String status
) {
}
