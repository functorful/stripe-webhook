package com.functorful.stripewebhook.event.wire;

import com.functorful.stripewebhook.event.PaymentIntentObject;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Wire-side envelope for a Stripe webhook event. The boundary parser
 * ({@code WebhookEventProcessor}) deserialises the raw HTTP body into
 * this record, then converts the result into the typed sealed
 * {@link com.functorful.stripewebhook.event.StripeEvent} hierarchy used
 * by the dispatcher and handlers.
 *
 * <p><strong>Scope: parser-internal only.</strong> This package
 * ({@code event.wire}) is reserved for wire-shape DTOs that exist solely
 * for Micronaut Serde to bind into. Nothing outside the parser should
 * depend on these types — handlers and the dispatcher work exclusively
 * on the {@link com.functorful.stripewebhook.event.StripeEvent} sealed
 * hierarchy. ADR-0008's "no untyped JsonNode past the boundary" property
 * extends to "no wire DTOs past the boundary either".
 *
 * <p><strong>Field bindings.</strong>
 * <ul>
 *   <li>{@link #id()} — Stripe event id ({@code evt_...}). Required; the
 *       parser rejects payloads missing it.</li>
 *   <li>{@link #type()} — Stripe event type string ({@code payment_intent.succeeded},
 *       etc.). Used by the parser to discriminate the
 *       {@code StripeEvent} variant. Nullable to tolerate malformed-but-
 *       HMAC-valid bodies; the parser routes a null type to
 *       {@code Ignored}.</li>
 *   <li>{@link #created()} — Stripe's wall-clock timestamp as epoch
 *       seconds. Long value; the parser converts to
 *       {@link java.time.Instant} for {@code occurredAt}.</li>
 *   <li>{@link #data()} — the {@code data} envelope; Stripe wraps the
 *       type-specific payload in this single-field outer object. The
 *       inner {@link Data#object()} is the {@link PaymentIntentObject}
 *       (or future variants).</li>
 * </ul>
 *
 * <p><strong>Permissive parsing.</strong> Stripe sends additional fields
 * we don't consume ({@code livemode}, {@code request}, {@code api_version},
 * etc.). Micronaut Serde's default ignores unknown properties, which is
 * the intended behaviour: the parser binds only what we need and tolerates
 * upstream additions without breaking.
 */
@Serdeable
public record StripeEventEnvelope(
        String id,
        @Nullable String type,
        long created,
        @Nullable Data data
) {

    /**
     * Inner wrapper Stripe puts around the type-specific payload. The
     * single field {@code object} carries the {@link PaymentIntentObject}
     * (and any future variants — {@code charge.*}, {@code customer.*},
     * etc., would extend the parser's type→variant switch with their
     * own payload binding).
     */
    @Serdeable
    public record Data(@Nullable PaymentIntentObject object) { }
}
