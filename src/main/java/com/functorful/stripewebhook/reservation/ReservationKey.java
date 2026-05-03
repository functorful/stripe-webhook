package com.functorful.stripewebhook.reservation;

import com.fasterxml.jackson.databind.JsonNode;

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
     * Parse from the Stripe PaymentIntent's {@code metadata} block.
     * The keys are the ones PaymentLambda's {@code stripeMetadata}
     * helper writes:
     *
     * <ul>
     *   <li>{@code reservationUserId}</li>
     *   <li>{@code reservationInvestmentId}</li>
     *   <li>{@code reservationInvestmentVersion}</li>
     *   <li>{@code reservationRequestedAt}</li>
     *   <li>{@code reservationVersion}</li>
     * </ul>
     *
     * @return parsed {@link ReservationKey}; never {@code null}.
     * @throws IllegalArgumentException if any required field is missing
     *     or has the wrong type. The exception message contains the
     *     field name only — never the value — so callers can safely
     *     surface it (or log it) without leaking metadata.
     */
    public static ReservationKey fromStripeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isMissingNode() || metadata.isNull()) {
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

    private static String requireString(JsonNode metadata, String field) {
        JsonNode node = metadata.get(field);
        if (node == null || !node.isTextual() || node.asText().isEmpty()) {
            throw new IllegalArgumentException("metadata." + field);
        }
        return node.asText();
    }

    private static long requireLong(JsonNode metadata, String field) {
        JsonNode node = metadata.get(field);
        if (node == null) {
            throw new IllegalArgumentException("metadata." + field);
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("metadata." + field);
            }
        }
        throw new IllegalArgumentException("metadata." + field);
    }
}
