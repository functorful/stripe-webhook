package com.functorful.stripewebhook.dispatch.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.functorful.stripewebhook.dispatch.EventHandler;
import com.functorful.stripewebhook.dynamodb.AuditLogStore;
import com.functorful.stripewebhook.dynamodb.InvestmentPaymentStore;
import com.functorful.stripewebhook.dynamodb.InvestmentReservationStore;
import com.functorful.stripewebhook.dynamodb.PaymentView;
import com.functorful.stripewebhook.dynamodb.ReservationView;
import com.functorful.stripewebhook.dynamodb.UserInvestmentStore;
import com.functorful.stripewebhook.email.SesEmailService;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import com.functorful.stripewebhook.reservation.ReservationKey;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Value;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for {@code payment_intent.succeeded}. Implements the
 * {@code docs/pay-05-handler-design.md} §4.1 sequence:
 *
 * <ol>
 *   <li>Extract the reservation key from the Stripe PaymentIntent metadata.</li>
 *   <li>Load reservation; guard {@code status == "confirmed"}.</li>
 *   <li>Load payment by reservation FK + paymentIntentId; guard {@code status == "processed"}.</li>
 *   <li>Single {@code TransactWriteItems} (atomic): payment {@code processed -> success},
 *       reservation {@code confirmed -> executed}, idempotent put UserInvestment, put AuditLog.</li>
 *   <li>SES confirmation email (best-effort).</li>
 * </ol>
 *
 * <p><strong>Status-guard posture:</strong> guard violations log + return
 * 200 (do NOT throw — the WebhookEvent row is already recorded; we don't
 * want Stripe retrying a poison pill, and we don't want a 5xx leaking to
 * the wire). Real bugs (transient DDB, IAM regressions) propagate via a
 * non-{@link TransactionCanceledException}; the orchestrator's outer catch
 * logs at ERROR and returns 200 (Tomás §10 R4).
 *
 * <p><strong>Idempotency:</strong> guaranteed at the dispatcher boundary
 * by {@code WebhookEvent.eventId} (PAY-02). Within the handler, the
 * {@code attribute_not_exists(userId)} on the UserInvestment put gives a
 * second-line defense for the partial-write recovery path (§6).
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.succeeded")
public class PaymentIntentSucceededHandler implements EventHandler {

    /** Status-transition matrix expectations (per ARCH-04 / resource.ts). */
    private static final String RESERVATION_EXPECTED = "confirmed";
    private static final String RESERVATION_TARGET = "executed";
    private static final String PAYMENT_EXPECTED = "processed";
    private static final String PAYMENT_TARGET = "success";

    private static final String AUDIT_RESOURCE_PREFIX = "InvestmentReservation:";
    private static final String AUDIT_ACTION_SUCCEEDED = "payment.succeeded";

    /**
     * Sentinel value Tofu writes into {@code AUDIT_LOG_TABLE_NAME} when
     * {@code var.audit_log_ssm_bridge_ready = false} — i.e. when the
     * application repo's Amplify {@code tablesToExport} hasn't yet
     * exported the AuditLog table name to SSM. Tomás's M-NEW-1 review
     * (infrastructure!14) requires a degraded-mode short-circuit on this
     * sentinel: without it, a 4-item TWI runs against a non-existent
     * table, all 4 writes roll back atomically, Stripe gets 200, and
     * Sobrado has zero record of the payment.
     *
     * <p>String identity must match the Tofu literal in
     * {@code aws-stripe-webhook-lambda.tofu} exactly.
     */
    static final String AUDIT_LOG_BRIDGE_PENDING_SENTINEL = "PENDING_AUDIT_LOG_BRIDGE";

    /**
     * Canary string for the M-NEW-1 alarm extension. The infra log
     * metric filter alternation matches both {@code "M3 alarm will
     * surface this"} (markProcessed-failed canary) and
     * {@code "DEGRADED MODE"} (this handler's degraded short-circuit).
     * String identity must stay coupled to the alarm-pattern literal in
     * {@code aws-webhook-events-monitoring.tofu}.
     */
    private static final String DEGRADED_MODE_CANARY = "DEGRADED MODE";

    private final InvestmentReservationStore reservationStore;
    private final InvestmentPaymentStore paymentStore;
    private final UserInvestmentStore userInvestmentStore;
    private final AuditLogStore auditLogStore;
    private final SesEmailService sesEmailService;
    private final WebhookIdempotencyStore idempotencyStore;
    private final DynamoDbClient dynamoDbClient;
    private final String lambdaVersion;
    private final String gitSha;
    private final boolean degradedMode;

    public PaymentIntentSucceededHandler(
            InvestmentReservationStore reservationStore,
            InvestmentPaymentStore paymentStore,
            UserInvestmentStore userInvestmentStore,
            AuditLogStore auditLogStore,
            SesEmailService sesEmailService,
            WebhookIdempotencyStore idempotencyStore,
            DynamoDbClient dynamoDbClient,
            @Value("${dd.version:unknown}") String lambdaVersion,
            @Value("${git.sha:unknown}") String gitSha,
            @Value("${audit-log.table-name}") String auditLogTableName
    ) {
        this.reservationStore = reservationStore;
        this.paymentStore = paymentStore;
        this.userInvestmentStore = userInvestmentStore;
        this.auditLogStore = auditLogStore;
        this.sesEmailService = sesEmailService;
        this.idempotencyStore = idempotencyStore;
        this.dynamoDbClient = dynamoDbClient;
        this.lambdaVersion = lambdaVersion;
        this.gitSha = gitSha;
        this.degradedMode = AUDIT_LOG_BRIDGE_PENDING_SENTINEL.equals(auditLogTableName);
        if (this.degradedMode) {
            log.error("PAY-05 succeeded handler initialised in {} — AuditLog SSM bridge not ready "
                            + "(audit-log.table-name=={}). All Stripe webhook events that would "
                            + "otherwise dispatch will short-circuit BEFORE any DDB writes; "
                            + "WebhookEvent rows stay processed=false so the M3 alarm fires.",
                    DEGRADED_MODE_CANARY, AUDIT_LOG_BRIDGE_PENDING_SENTINEL);
        }
    }

    @Override
    @NewSpan
    public void handle(StripeWebhookEvent event) {
        // Tomás M-NEW-1 (infrastructure!14): when the AuditLog SSM bridge
        // is not yet ready, AUDIT_LOG_TABLE_NAME collapses to the
        // sentinel. Short-circuit BEFORE the TWI so we never run a
        // 4-item transaction against a non-existent table — that path
        // would silently drop the payment on the floor (entire TWI
        // rolls back atomically, Stripe gets 200, no record in DDB).
        if (degradedMode) {
            log.error("PAY-05 handler in {} — AuditLog SSM bridge not ready. Skipping ALL writes; "
                            + "webhook event recorded for replay after var.audit_log_ssm_bridge_ready "
                            + "flips and the Lambda image rolls. eventId={} eventType={}",
                    DEGRADED_MODE_CANARY, event.eventId(), event.eventType());
            // Do NOT call markProcessed. Row stays processed=false so the
            // M3 alarm fires (the partial-coverage log-metric-filter
            // pattern includes "DEGRADED MODE" as a canary string).
            return;
        }

        Instant receivedAt = Instant.now();

        // 1. Extract paymentIntentId + reservation key from event payload.
        JsonNode dataObject = event.dataObject();
        String paymentIntentId = textOrEmpty(dataObject, "id");
        if (paymentIntentId.isEmpty()) {
            // Tomás §10 M4 case 16: defensive — log + return.
            log.warn("payment_intent.succeeded event missing data.object.id; skipping. eventId={}",
                    event.eventId());
            return;
        }

        ReservationKey reservationKey;
        try {
            reservationKey = ReservationKey.fromStripeMetadata(dataObject.get("metadata"));
        } catch (IllegalArgumentException e) {
            log.warn("payment_intent.succeeded event has incomplete metadata; skipping. "
                            + "eventId={} missingField={}",
                    event.eventId(), e.getMessage());
            return;
        }

        // 2. Load reservation; guard.
        Optional<ReservationView> reservationOpt = reservationStore.load(reservationKey);
        if (reservationOpt.isEmpty()) {
            log.warn("InvestmentReservation not found for succeeded webhook; skipping. "
                            + "eventId={} reservationUserId={} reservationInvestmentId={}",
                    event.eventId(), reservationKey.userId(), reservationKey.investmentId());
            return;
        }
        ReservationView reservation = reservationOpt.get();
        if (!RESERVATION_EXPECTED.equals(reservation.status())) {
            log.warn("Reservation status guard tripped on succeeded webhook; skipping. "
                            + "eventId={} actual={} expected={}",
                    event.eventId(), reservation.status(), RESERVATION_EXPECTED);
            return;
        }

        // 3. Load payment by reservation FK + paymentIntentId; guard.
        Optional<PaymentView> paymentOpt = paymentStore.findByReservationAndIntent(
                reservationKey, paymentIntentId);
        if (paymentOpt.isEmpty()) {
            log.warn("InvestmentPayment not found for succeeded webhook; skipping. "
                    + "eventId={} paymentIntentIdHash={}", event.eventId(), paymentIntentId.hashCode());
            return;
        }
        PaymentView payment = paymentOpt.get();
        if (!PAYMENT_EXPECTED.equals(payment.status())) {
            log.warn("Payment status guard tripped on succeeded webhook; skipping. "
                            + "eventId={} actual={} expected={}",
                    event.eventId(), payment.status(), PAYMENT_EXPECTED);
            return;
        }

        // 4. TransactWriteItems — atomic 4-write set.
        Instant now = Instant.now();
        Update updatePayment = paymentStore.buildStatusUpdate(
                payment.id(), payment.version(),
                PAYMENT_EXPECTED, PAYMENT_TARGET,
                event.created(), null);
        Update updateReservation = reservationStore.buildStatusUpdate(
                reservationKey, RESERVATION_EXPECTED, RESERVATION_TARGET, now);
        Put putUserInvestment = userInvestmentStore.buildIdempotentPut(
                reservationKey.userId(),
                reservationKey.investmentId(),
                reservationKey.investmentVersion(),
                reservation.participations(),
                now);
        Map<String, Object> details = buildAuditDetails(
                event, paymentIntentId, payment, reservationKey,
                receivedAt,
                /* lastPaymentErrorCode */ null,
                /* lastPaymentError */ null,
                /* paymentTransition */ PAYMENT_EXPECTED + " -> " + PAYMENT_TARGET,
                /* reservationTransition */ RESERVATION_EXPECTED + " -> " + RESERVATION_TARGET);
        Put putAuditLog = auditLogStore.buildPut(new AuditLogStore.Entry(
                payment.userId(),
                event.created(),
                AUDIT_RESOURCE_PREFIX + reservationFingerprint(reservationKey),
                AUDIT_ACTION_SUCCEEDED,
                details));

        TransactWriteItemsRequest twiRequest = TransactWriteItemsRequest.builder()
                .transactItems(List.of(
                        TransactWriteItem.builder().update(updatePayment).build(),
                        TransactWriteItem.builder().update(updateReservation).build(),
                        TransactWriteItem.builder().put(putUserInvestment).build(),
                        TransactWriteItem.builder().put(putAuditLog).build()))
                .build();

        try {
            dynamoDbClient.transactWriteItems(twiRequest);
        } catch (TransactionCanceledException tcx) {
            // Tomás §10 R4: this catch is specific. A guard violation
            // discovered concurrently (e.g., two replays racing) shows
            // up here as ConditionalCheckFailed — log + return; do NOT
            // propagate.
            log.warn("TransactWriteItems cancelled on succeeded webhook (likely concurrent guard "
                            + "violation or partial-write recovery target). Inspecting reasons. "
                            + "eventId={} reasons={}",
                    event.eventId(),
                    tcx.cancellationReasons());
            return;
        }

        // 5. SES confirmation email — best-effort.
        String receiptEmail = textOrEmpty(dataObject, "receipt_email");
        if (!receiptEmail.isEmpty()) {
            sesEmailService.sendPaymentConfirmation(
                    receiptEmail,
                    reservation.participations(),
                    payment.amountCents(),
                    paymentIntentId);
        } else {
            // PAY-21 follow-up: PaymentLambda's metadata block may
            // additionally carry the verified email; for now, when
            // receipt_email isn't populated by Stripe, we skip the
            // email send. The investor still sees confirmation in-app
            // via the existing DDB-stream → AppSync subscription.
            log.info("payment_intent.succeeded delivered without receipt_email; "
                            + "skipping email send. eventId={}",
                    event.eventId());
        }

        // 6. Mark the WebhookEvent row as processed (best-effort; M3
        //    alarm catches lingering processed=false rows).
        idempotencyStore.markProcessed(event.eventId());

        log.info("payment_intent.succeeded processed end-to-end. eventId={} paymentIntentIdHash={} "
                        + "transition=payment[{} -> {}]+reservation[{} -> {}]",
                event.eventId(), paymentIntentId.hashCode(),
                PAYMENT_EXPECTED, PAYMENT_TARGET,
                RESERVATION_EXPECTED, RESERVATION_TARGET);
    }

    /**
     * Build the AuditLog `details` payload as a {@link LinkedHashMap}
     * so insertion order is preserved (stable JSON serialisation makes
     * log diffs across runs trivially comparable). Nested objects
     * ({@code reservationKey}, {@code transition}) are themselves
     * {@code LinkedHashMap<String, Object>}; Micronaut Serde walks the
     * map shape and emits the corresponding nested JSON.
     */
    private Map<String, Object> buildAuditDetails(
            StripeWebhookEvent event,
            String paymentIntentId,
            PaymentView payment,
            ReservationKey reservationKey,
            Instant receivedAt,
            String lastPaymentErrorCode,
            String lastPaymentError,
            String paymentTransition,
            String reservationTransition
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", "stripe");
        details.put("actor", "stripe-webhook-lambda");
        details.put("lambdaVersion", lambdaVersion);
        details.put("gitSha", gitSha);
        details.put("eventId", event.eventId());
        details.put("stripePaymentIntentId", paymentIntentId);
        details.put("paymentRowId", payment.id());
        details.put("paymentRowVersion", payment.version());
        details.put("receivedAt", receivedAt.toString());
        Map<String, Object> keyNode = new LinkedHashMap<>();
        keyNode.put("userId", reservationKey.userId());
        keyNode.put("investmentId", reservationKey.investmentId());
        keyNode.put("investmentVersion", reservationKey.investmentVersion());
        keyNode.put("requestedAt", reservationKey.requestedAt());
        keyNode.put("version", reservationKey.version());
        details.put("reservationKey", keyNode);
        details.put("amountCents", payment.amountCents());
        details.put("currency", payment.currency());
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("payment", paymentTransition);
        transition.put("reservation", reservationTransition);
        details.put("transition", transition);
        if (lastPaymentErrorCode != null) {
            details.put("lastPaymentErrorCode", lastPaymentErrorCode);
        }
        if (lastPaymentError != null) {
            details.put("lastPaymentError", lastPaymentError);
        }
        return details;
    }

    private static String reservationFingerprint(ReservationKey key) {
        return key.userId() + "|" + key.investmentId() + "#" + key.investmentVersion()
                + "@" + key.requestedAt() + "/v" + key.version();
    }

    private static String textOrEmpty(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        return node == null || !node.isTextual() ? "" : node.asText();
    }
}
