package com.functorful.stripewebhook.event;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.config.naming.SnakeCaseStrategy;

/**
 * Typed view of a Stripe PaymentIntent's {@code last_payment_error}
 * sub-object — the failure-detail block Stripe attaches to a
 * {@code payment_intent.payment_failed} event.
 *
 * <p><strong>Contract.</strong> Stripe's PaymentIntent error object is rich
 * (decline_code, doc_url, payment_method, etc.); we bind only the fields
 * we use today. Any extra fields on the wire are tolerated by Micronaut
 * Serde's default permissive shape — they're ignored, not rejected.
 *
 * <p><strong>Naming.</strong> Stripe sends snake_case on the wire
 * ({@code last_payment_error.code}); we surface camelCase Java names. The
 * class-level {@link SnakeCaseStrategy} reconciles both directions.
 *
 * <p>See ADR-0008 (`docs/adr/0008-micronaut-serde-only.md`) for the
 * project-wide rule that all Stripe payload binding lives in typed
 * {@link Serdeable} records, not {@code JsonNode} traversal.
 */
@Serdeable(naming = SnakeCaseStrategy.class)
public record PaymentIntentError(
        @Nullable String code,
        @Nullable String message
) { }
