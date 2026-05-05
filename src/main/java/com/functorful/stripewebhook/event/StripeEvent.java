package com.functorful.stripewebhook.event;

import java.time.Instant;

/**
 * Sealed root of the typed Stripe-event hierarchy delivered to the
 * dispatcher. Each variant binds the Stripe payload shape we actually
 * consume — the dispatcher pattern-matches on the variant and routes to
 * the matching handler.
 *
 * <p>The sealed type is the gadget-defence pillar of ADR-0008
 * (`docs/adr/0008-micronaut-serde-only.md`):
 * <ul>
 *   <li><strong>No untyped {@code JsonNode}</strong> reaches the dispatcher
 *       or any handler — the boundary parser
 *       ({@code WebhookEventProcessor}) resolves the wire envelope into
 *       exactly one of these variants once, at the trust boundary.</li>
 *   <li><strong>Compile-time exhaustiveness</strong> — pattern-matching
 *       against the sealed hierarchy with no {@code default} branch means
 *       any new variant added here forces the dispatcher to add the
 *       matching case (the build fails otherwise). This is the structural
 *       fix for Tomás's "no fall-through to handler-via-default-branch"
 *       review priority.</li>
 *   <li><strong>{@link Ignored} is a first-class variant</strong> — events
 *       Stripe sends that we don't subscribe to (or don't recognise) land
 *       here explicitly, never as {@code null} from a map-lookup miss.
 *       The dispatcher case-matches on {@code Ignored}; the
 *       {@code IgnoredEventHandler} acks (200) the event so Stripe
 *       doesn't retry.</li>
 * </ul>
 *
 * <p><strong>Variants.</strong>
 * <ul>
 *   <li>{@link PaymentIntentSucceeded} — {@code payment_intent.succeeded}
 *       wire event; carries the {@link PaymentIntentObject} payload.</li>
 *   <li>{@link PaymentIntentFailed} — {@code payment_intent.payment_failed}
 *       wire event; carries the {@link PaymentIntentObject} payload (the
 *       {@code last_payment_error} sub-field is populated here).</li>
 *   <li>{@link Ignored} — every other event type Stripe sends. Carries
 *       the original {@code type} string for debug-trace logs only;
 *       handlers MUST NOT branch on the string — that path is the
 *       fall-through-via-default smell ADR-0008 forbids.</li>
 * </ul>
 *
 * <p><strong>Adding a new event type.</strong> Three changes, all
 * compile-time enforced:
 * <ol>
 *   <li>Add a new {@code permits} clause + record below.</li>
 *   <li>Extend {@code WebhookEventProcessor.parse} to map the wire type
 *       string to the new record (same single switch as today's
 *       PaymentIntent variants).</li>
 *   <li>Extend {@code WebhookEventDispatcher.dispatch}'s switch with the
 *       new case. The build fails until you do — exhaustiveness is the
 *       guard.</li>
 * </ol>
 *
 * <p>Common metadata exposed by every variant:
 * {@link #eventId()} and {@link #occurredAt()}. {@code eventId} is the
 * Stripe-issued event-stream identifier, used by the upstream idempotency
 * store to deduplicate retries; {@code occurredAt} is Stripe's wall-clock
 * (preferred over the receiver's clock for AuditLog timestamps).
 */
public sealed interface StripeEvent
        permits StripeEvent.PaymentIntentSucceeded,
                StripeEvent.PaymentIntentFailed,
                StripeEvent.Ignored {

    /** Stripe's event-stream identifier ({@code evt_...}). Required, never null. */
    String eventId();

    /**
     * Stripe's authoritative wall-clock for this event ({@code event.created}
     * on the wire, epoch seconds — the parser converts to {@link Instant}).
     */
    Instant occurredAt();

    /**
     * Variant for {@code payment_intent.succeeded} wire events. Carries the
     * full {@link PaymentIntentObject} payload — the success handler reads
     * {@code id}, {@code metadata}, and {@code receipt_email}.
     */
    record PaymentIntentSucceeded(
            String eventId,
            Instant occurredAt,
            PaymentIntentObject payload
    ) implements StripeEvent { }

    /**
     * Variant for {@code payment_intent.payment_failed} wire events. Carries
     * the full {@link PaymentIntentObject} payload — the failed handler
     * additionally reads {@code last_payment_error.code} and
     * {@code last_payment_error.message}.
     */
    record PaymentIntentFailed(
            String eventId,
            Instant occurredAt,
            PaymentIntentObject payload
    ) implements StripeEvent { }

    /**
     * Variant for every other event type. Stripe sends the full
     * webhook stream regardless of which events the receiver subscribes
     * to; events we don't match must still be acked (200) so Stripe
     * doesn't retry.
     *
     * <p><strong>{@code unrecognisedType} is for debug logs ONLY.</strong>
     * The dispatcher MUST NOT branch on this string — branching on it
     * defeats the gadget-defence sealed-hierarchy property (handlers
     * could end up routing on attacker-controlled wire data).
     */
    record Ignored(
            String eventId,
            Instant occurredAt,
            String unrecognisedType
    ) implements StripeEvent { }
}
