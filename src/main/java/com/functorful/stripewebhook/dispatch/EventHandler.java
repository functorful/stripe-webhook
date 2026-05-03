package com.functorful.stripewebhook.dispatch;

/**
 * Sealed interface for per-event-type handlers dispatched by
 * {@link WebhookEventDispatcher}.
 *
 * <p>The dispatcher injects a {@code Map<String, EventHandler>} keyed by
 * Stripe event type ({@code payment_intent.succeeded},
 * {@code payment_intent.payment_failed}, ...) and routes each event to
 * the matching bean. Unrecognised event types fall through to
 * {@link IgnoredEventHandler}.
 *
 * <p>Adding a new handler:
 * <ol>
 *   <li>Implement this interface as a {@code @Singleton @Named("event.type")}
 *       bean.</li>
 *   <li>Update the dispatch table in
 *       {@code docs/pay-05-handler-design.md}.</li>
 *   <li>Add unit coverage in {@link WebhookEventDispatcherTest}.</li>
 * </ol>
 *
 * <p>Idempotency: handlers do NOT need to be idempotent against repeated
 * calls with the same {@code eventId} — replays short-circuit at the
 * {@link com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore
 * idempotency store} before this interface is reached. Handlers DO need
 * to be idempotent against the business state they touch (see PAY-05
 * design doc §6).
 *
 * <p>Failure semantics: a handler that throws causes the dispatcher to
 * log the exception and the surrounding orchestrator to return 200 to
 * Stripe (the {@code WebhookEvent} idempotency row is already recorded;
 * we don't want Stripe retrying a poison pill). The row is left with
 * {@code processed = false} so an ops sweep can re-drive it manually.
 */
public interface EventHandler {

    /**
     * Handle a single Stripe webhook event whose signature has already
     * been verified and whose first-delivery has already been recorded
     * in the idempotency store.
     *
     * @param eventType the Stripe event type (e.g.
     *                  {@code payment_intent.succeeded}).
     * @param eventId   the Stripe event id ({@code evt_...}). Useful for
     *                  log correlation.
     */
    void handle(String eventType, String eventId);
}
