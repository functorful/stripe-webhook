package com.functorful.stripewebhook.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Verified, JSON-parsed view of a Stripe webhook event delivered to the
 * Lambda. Built by {@link com.functorful.stripewebhook.WebhookEventProcessor}
 * AFTER signature verification + JSON parse + idempotency check, then
 * passed to the matching {@link com.functorful.stripewebhook.dispatch.EventHandler}.
 *
 * <p>The {@code dataObject} field is the raw {@code event.data.object}
 * JSON node — Stripe's payload here is type-specific (a PaymentIntent
 * for {@code payment_intent.*}, a Charge for {@code charge.*}, etc.) so
 * we don't bind it to a typed shape at the dispatcher boundary. Each
 * handler binds its own typed view from this node.
 *
 * <p>Why a record (no Lombok): GraalVM native-image reflection footprint
 * stays minimal; record classes have predictable serialization and value
 * equality without bytecode generation.
 *
 * <p>Why separate from {@link
 * com.functorful.stripewebhook.dispatch.EventHandler}: the dispatcher
 * needs the {@code eventType} for routing; the handlers need
 * {@code dataObject} for business logic. One value carries both with
 * room to grow ({@code created}, {@code livemode}, request id) without
 * churning the {@link com.functorful.stripewebhook.dispatch.EventHandler}
 * signature.
 */
public record StripeWebhookEvent(

        /** Stripe's event id, e.g. {@code evt_1Pq...} — also the WebhookEvent dedup key. */
        String eventId,

        /** Stripe event type, e.g. {@code payment_intent.succeeded}. May be {@code null}
         * for malformed-but-HMAC-valid bodies. */
        String eventType,

        /** {@code event.created} as an instant. Authoritative wall-clock at Stripe;
         * preferred over the receiver's clock for AuditLog timestamps. */
        Instant created,

        /** {@code event.data.object} — the type-specific payload. Never {@code null};
         * may be {@code MissingNode} if absent. Handlers extract their typed view
         * from this node. */
        JsonNode dataObject
) {

    public StripeWebhookEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(dataObject, "dataObject");
        // eventType is intentionally nullable — see field javadoc.
    }
}
