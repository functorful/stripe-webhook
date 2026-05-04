package com.functorful.stripewebhook.dynamodb;

/**
 * Projected, immutable read of an {@code InvestmentReservation} row —
 * exactly the fields the PAY-05 webhook handlers need to make the
 * status-transition decision and write the {@code TransactWriteItems}.
 *
 * <p>Tomás's veto territory (PAY-04 review item #4): handlers MUST NOT
 * pull fields they don't act on. Future schema additions (e.g., IBAN
 * under PAY-23) MUST NOT leak into this Lambda just because the table
 * grew. The {@code projectionExpression} on {@link InvestmentReservationStore}
 * enumerates exactly these fields at the wire level.
 */
public record ReservationView(
        String userId,
        String investmentId,
        long investmentVersion,
        long participations,
        String requestedAt,
        long version,
        String status,
        String expiresAt
) {
}
