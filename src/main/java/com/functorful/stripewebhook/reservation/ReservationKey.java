package com.functorful.stripewebhook.reservation;

import java.util.Map;
import java.util.Objects;

/**
 * Composite identifier of an {@code InvestmentReservation} row, mirroring
 * the Amplify schema's identifier tuple (see
 * {@code application/amplify/data/resource.ts:114}):
 *
 * <pre>
 *   .identifier(['userId', 'investmentId', 'investmentVersion', 'requestedAt', 'version'])
 * </pre>
 *
 * <p>The PaymentLambda (PAY-04) puts these five fields under the Stripe
 * PaymentIntent {@code metadata} block when it creates the intent (see
 * {@code backend/payment-lambda CreatePaymentIntentProcessor.stripeMetadata}).
 * This webhook receives them back on every {@code payment_intent.*}
 * delivery and uses them as the composite PK to look up the matching
 * reservation + payment rows.
 *
 * <p>This record mirrors {@code backend/payment-lambda}'s
 * {@code ReservationKey} but lives in the webhook's package namespace
 * to keep cross-repo dependencies zero. The wire shape (the metadata
 * keys PaymentLambda puts in Stripe) is the contract; both records
 * conform to it.
 */
public record ReservationKey(
        String userId,
        String investmentId,
        long investmentVersion,
        String requestedAt,
        long version
) {

    public ReservationKey {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(investmentId, "investmentId");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /**
     * Parse from the Stripe PaymentIntent's {@code metadata} block. The
     * keys are the ones PaymentLambda's {@code stripeMetadata} helper
     * writes:
     *
     * <ul>
     *   <li>{@code reservationUserId}</li>
     *   <li>{@code reservationInvestmentId}</li>
     *   <li>{@code reservationInvestmentVersion}</li>
     *   <li>{@code reservationRequestedAt}</li>
     *   <li>{@code reservationVersion}</li>
     * </ul>
     *
     * <p>Stripe's metadata API contract is strings-only — every value on
     * the wire is a String — so this signature accepts only
     * {@code Map<String, String>}. PaymentLambda writes numeric fields
     * via {@code Long.toString(...)}; the parse here calls
     * {@code Long.parseLong(...)} on those entries.
     *
     * <p>Pre-ADR-0008 versions of this method also accepted a
     * {@code JsonNode} and tolerated numeric-shape values (a leniency
     * needed only by tests using {@code ObjectNode.put(String, long)} —
     * never by production traffic). That overload is removed; tests pass
     * {@code Long.toString(...)} now.
     *
     * @return parsed {@link ReservationKey}; never {@code null}.
     * @throws IllegalArgumentException if any required field is missing,
     *     empty, or non-numeric for the long-typed fields. The exception
     *     message contains the field name only — never the value — so
     *     callers can safely surface it (or log it) without leaking
     *     metadata.
     */
    public static ReservationKey fromStripeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalArgumentException("metadata: missing");
        }
        return new ReservationKey(
                requireString(metadata, "reservationUserId"),
                requireString(metadata, "reservationInvestmentId"),
                requireLong(metadata, "reservationInvestmentVersion"),
                requireString(metadata, "reservationRequestedAt"),
                requireLong(metadata, "reservationVersion")
        );
    }

    private static String requireString(Map<String, String> metadata, String field) {
        String value = metadata.get(field);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("metadata." + field);
        }
        return value;
    }

    private static long requireLong(Map<String, String> metadata, String field) {
        String value = metadata.get(field);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("metadata." + field);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("metadata." + field);
        }
    }
}
