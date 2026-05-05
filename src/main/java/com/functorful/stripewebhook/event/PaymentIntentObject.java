package com.functorful.stripewebhook.event;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

import java.util.Collections;
import java.util.Map;

/**
 * Typed view of a Stripe PaymentIntent — i.e. the {@code event.data.object}
 * payload for any {@code payment_intent.*} event type.
 *
 * <p><strong>Field set.</strong> Bound to the fields the
 * {@code stripe-webhook} Lambda actually reads:
 * <ul>
 *   <li>{@link #id()} — Stripe's PaymentIntent identifier ({@code pi_...}).
 *       Required; the handlers use this as the join key against
 *       {@code InvestmentPayment.stripePaymentIntentId}.</li>
 *   <li>{@link #receiptEmail()} — optional. Surfaced by Stripe when the
 *       payer provides an email; the success handler uses it for the
 *       confirmation-email send.</li>
 *   <li>{@link #metadata()} — optional. Stripe's metadata block — strings-only
 *       per Stripe API contract. PaymentLambda's {@code stripeMetadata}
 *       helper writes the {@code reservation*} keys here on intent
 *       creation; the webhook reads them back to reconstruct the
 *       {@code ReservationKey} composite identifier.</li>
 *   <li>{@link #lastPaymentError()} — optional. Populated by Stripe on
 *       {@code payment_intent.payment_failed}. The failed handler reads
 *       {@code code} (categorised) for log lines and {@code message}
 *       (free text) for sanitised audit-log persistence.</li>
 * </ul>
 *
 * <p><strong>Snake-case wire shape.</strong> Stripe sends
 * {@code receipt_email}, {@code last_payment_error}; the
 * {@link SnakeCaseStrategy} maps wire ↔ Java automatically. Extra fields
 * (e.g. {@code amount}, {@code currency}, {@code status}) are tolerated by
 * Micronaut Serde's default permissive shape and dropped during
 * deserialisation.
 *
 * <p><strong>Null-safety.</strong> {@link #id()} is required; the parser
 * (see {@code WebhookEventProcessor}) rejects payloads missing it. The
 * three optional fields are typed as {@link Nullable}; consumers must
 * defend (helpers below cover the common cases).
 *
 * <p>See ADR-0008 (`docs/adr/0008-micronaut-serde-only.md`).
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record PaymentIntentObject(
        String id,
        @Nullable String receiptEmail,
        @Nullable Map<String, String> metadata,
        @Nullable PaymentIntentError lastPaymentError
) {

    /**
     * Returns {@link #receiptEmail()} or the empty string if absent.
     * Mirrors the {@code textOrEmpty} helper the JsonNode-era handlers
     * used; preserved as a convenience for the success handler's
     * {@code if (!receiptEmail.isEmpty())} branch.
     */
    public String receiptEmailOrEmpty() {
        return receiptEmail == null ? "" : receiptEmail;
    }

    /**
     * Returns {@link #metadata()} or an empty immutable map if absent.
     * Used by {@link com.functorful.stripewebhook.reservation.ReservationKey#fromStripeMetadata(java.util.Map)}.
     */
    public Map<String, String> metadataOrEmpty() {
        return metadata == null ? Collections.emptyMap() : metadata;
    }

    /**
     * Returns the Stripe-supplied error code (e.g. {@code card_declined})
     * or empty string. Stable enumerated value — safe to log; Tomás §10
     * M1 review explicitly endorses logging the code (not the message).
     */
    public String lastPaymentErrorCodeOrEmpty() {
        return lastPaymentError == null || lastPaymentError.code() == null
                ? ""
                : lastPaymentError.code();
    }

    /**
     * Returns the Stripe-supplied error message (raw, free text). Caller
     * MUST sanitise before persist or log per Tomás §10 M1; this method
     * does not sanitise.
     */
    public String lastPaymentErrorMessageOrEmpty() {
        return lastPaymentError == null || lastPaymentError.message() == null
                ? ""
                : lastPaymentError.message();
    }
}
