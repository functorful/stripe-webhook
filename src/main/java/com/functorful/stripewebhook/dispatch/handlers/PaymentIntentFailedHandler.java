package com.functorful.stripewebhook.dispatch.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.functorful.stripewebhook.dispatch.EventHandler;
import com.functorful.stripewebhook.dynamodb.AuditLogStore;
import com.functorful.stripewebhook.dynamodb.InvestmentPaymentStore;
import com.functorful.stripewebhook.dynamodb.InvestmentReservationStore;
import com.functorful.stripewebhook.dynamodb.PaymentView;
import com.functorful.stripewebhook.dynamodb.ReservationView;
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
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handler for {@code payment_intent.payment_failed}. Implements
 * {@code docs/pay-05-handler-design.md} §4.2:
 *
 * <ol>
 *   <li>Extract paymentIntentId + sanitized lastPaymentError.code/message
 *       from the event (Tomás §10 M1).</li>
 *   <li>Load reservation; guard {@code status == "confirmed"}.</li>
 *   <li>Load payment by reservation FK + paymentIntentId; guard
 *       {@code status == "processed"}.</li>
 *   <li>Single {@code TransactWriteItems} (atomic): payment
 *       {@code processed -> failed}, reservation
 *       {@code confirmed -> pending} (allow retry) OR
 *       {@code confirmed -> expired} (per-method window elapsed),
 *       put AuditLog. NO UserInvestment.</li>
 *   <li>NO email. Investor sees the failure in-app via the
 *       PaymentBloc subscription (PAY-09's responsibility).</li>
 * </ol>
 *
 * <p><strong>Error-message sanitization (Tomás §10 M1):</strong> the
 * raw {@code lastPaymentError.message} is Stripe-supplied free text
 * with no input contract. We cap at 500 chars, strip control chars,
 * and persist to DDB. Logging at INFO uses Stripe's
 * {@code lastPaymentError.code} (categorised — {@code card_declined},
 * {@code insufficient_funds}, etc.); the raw message stays out of
 * CloudWatch.
 */
@Slf4j
@Singleton
@Bean
@Named("payment_intent.payment_failed")
public class PaymentIntentFailedHandler implements EventHandler {

    static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private static final String RESERVATION_EXPECTED = "confirmed";
    private static final String RESERVATION_TARGET_RETRY = "pending";
    private static final String RESERVATION_TARGET_EXPIRED = "expired";
    private static final String PAYMENT_EXPECTED = "processed";
    private static final String PAYMENT_TARGET = "failed";

    private static final String AUDIT_RESOURCE_PREFIX = "InvestmentReservation:";
    private static final String AUDIT_ACTION_FAILED = "payment.failed";

    /** See {@link PaymentIntentSucceededHandler#AUDIT_LOG_BRIDGE_PENDING_SENTINEL}. */
    static final String AUDIT_LOG_BRIDGE_PENDING_SENTINEL = "PENDING_AUDIT_LOG_BRIDGE";

    /** See {@link PaymentIntentSucceededHandler}'s {@code DEGRADED_MODE_CANARY}. */
    private static final String DEGRADED_MODE_CANARY = "DEGRADED MODE";

    private final InvestmentReservationStore reservationStore;
    private final InvestmentPaymentStore paymentStore;
    private final AuditLogStore auditLogStore;
    private final WebhookIdempotencyStore idempotencyStore;
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String lambdaVersion;
    private final String gitSha;
    private final boolean degradedMode;

    public PaymentIntentFailedHandler(
            InvestmentReservationStore reservationStore,
            InvestmentPaymentStore paymentStore,
            AuditLogStore auditLogStore,
            WebhookIdempotencyStore idempotencyStore,
            DynamoDbClient dynamoDbClient,
            @Value("${dd.version:unknown}") String lambdaVersion,
            @Value("${git.sha:unknown}") String gitSha,
            @Value("${audit-log.table-name}") String auditLogTableName
    ) {
        this(reservationStore, paymentStore, auditLogStore, idempotencyStore,
                dynamoDbClient, new ObjectMapper(), lambdaVersion, gitSha, auditLogTableName);
    }

    /** Test seam. */
    PaymentIntentFailedHandler(
            InvestmentReservationStore reservationStore,
            InvestmentPaymentStore paymentStore,
            AuditLogStore auditLogStore,
            WebhookIdempotencyStore idempotencyStore,
            DynamoDbClient dynamoDbClient,
            ObjectMapper objectMapper,
            String lambdaVersion,
            String gitSha,
            String auditLogTableName
    ) {
        this.reservationStore = reservationStore;
        this.paymentStore = paymentStore;
        this.auditLogStore = auditLogStore;
        this.idempotencyStore = idempotencyStore;
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
        this.lambdaVersion = lambdaVersion;
        this.gitSha = gitSha;
        this.degradedMode = AUDIT_LOG_BRIDGE_PENDING_SENTINEL.equals(auditLogTableName);
        if (this.degradedMode) {
            log.error("PAY-05 failed handler initialised in {} — AuditLog SSM bridge not ready "
                            + "(audit-log.table-name=={}). All Stripe webhook events that would "
                            + "otherwise dispatch will short-circuit BEFORE any DDB writes; "
                            + "WebhookEvent rows stay processed=false so the M3 alarm fires.",
                    DEGRADED_MODE_CANARY, AUDIT_LOG_BRIDGE_PENDING_SENTINEL);
        }
    }

    @Override
    @NewSpan
    public void handle(StripeWebhookEvent event) {
        // Tomás M-NEW-1 (infrastructure!14): degraded-mode short-circuit
        // when the AuditLog SSM bridge is not yet ready. See
        // PaymentIntentSucceededHandler.handle() for the full rationale.
        if (degradedMode) {
            log.error("PAY-05 handler in {} — AuditLog SSM bridge not ready. Skipping ALL writes; "
                            + "webhook event recorded for replay after var.audit_log_ssm_bridge_ready "
                            + "flips and the Lambda image rolls. eventId={} eventType={}",
                    DEGRADED_MODE_CANARY, event.eventId(), event.eventType());
            return;
        }

        Instant receivedAt = Instant.now();

        JsonNode dataObject = event.dataObject();
        String paymentIntentId = textOrEmpty(dataObject, "id");
        if (paymentIntentId.isEmpty()) {
            log.warn("payment_intent.payment_failed event missing data.object.id; skipping. eventId={}",
                    event.eventId());
            return;
        }

        // Tomás §10 M1: sanitize before persist; log only the code, not the message.
        JsonNode lastPaymentErrorNode = dataObject.path("last_payment_error");
        String lastPaymentErrorCode = textOrEmpty(lastPaymentErrorNode, "code");
        String rawMessage = textOrEmpty(lastPaymentErrorNode, "message");
        String sanitizedMessage = sanitizeErrorMessage(rawMessage);

        ReservationKey reservationKey;
        try {
            reservationKey = ReservationKey.fromStripeMetadata(dataObject.get("metadata"));
        } catch (IllegalArgumentException e) {
            log.warn("payment_intent.payment_failed event has incomplete metadata; skipping. "
                            + "eventId={} missingField={}",
                    event.eventId(), e.getMessage());
            return;
        }

        Optional<ReservationView> reservationOpt = reservationStore.load(reservationKey);
        if (reservationOpt.isEmpty()) {
            log.warn("InvestmentReservation not found for failed webhook; skipping. eventId={}",
                    event.eventId());
            return;
        }
        ReservationView reservation = reservationOpt.get();
        if (!RESERVATION_EXPECTED.equals(reservation.status())) {
            log.warn("Reservation status guard tripped on failed webhook; skipping. "
                            + "eventId={} actual={} expected={}",
                    event.eventId(), reservation.status(), RESERVATION_EXPECTED);
            return;
        }

        Optional<PaymentView> paymentOpt = paymentStore.findByReservationAndIntent(
                reservationKey, paymentIntentId);
        if (paymentOpt.isEmpty()) {
            log.warn("InvestmentPayment not found for failed webhook; skipping. "
                    + "eventId={} paymentIntentIdHash={}", event.eventId(), paymentIntentId.hashCode());
            return;
        }
        PaymentView payment = paymentOpt.get();
        if (!PAYMENT_EXPECTED.equals(payment.status())) {
            log.warn("Payment status guard tripped on failed webhook; skipping. "
                            + "eventId={} actual={} expected={}",
                    event.eventId(), payment.status(), PAYMENT_EXPECTED);
            return;
        }

        // Branch reservation target on per-method window.
        String reservationTarget = isPastExpiry(reservation, event.created())
                ? RESERVATION_TARGET_EXPIRED
                : RESERVATION_TARGET_RETRY;

        Update updatePayment = paymentStore.buildStatusUpdate(
                payment.id(), payment.version(),
                PAYMENT_EXPECTED, PAYMENT_TARGET,
                /* paidAt */ null,
                sanitizedMessage.isEmpty() ? null : sanitizedMessage);
        Update updateReservation = reservationStore.buildStatusUpdate(
                reservationKey, RESERVATION_EXPECTED, reservationTarget,
                /* confirmedAt */ null);

        ObjectNode details = buildAuditDetails(
                event, paymentIntentId, payment, reservationKey,
                receivedAt,
                lastPaymentErrorCode.isEmpty() ? null : lastPaymentErrorCode,
                sanitizedMessage.isEmpty() ? null : sanitizedMessage,
                PAYMENT_EXPECTED + " -> " + PAYMENT_TARGET,
                RESERVATION_EXPECTED + " -> " + reservationTarget);
        Put putAuditLog = auditLogStore.buildPut(new AuditLogStore.Entry(
                payment.userId(),
                event.created(),
                AUDIT_RESOURCE_PREFIX + reservationFingerprint(reservationKey),
                AUDIT_ACTION_FAILED,
                details));

        TransactWriteItemsRequest twiRequest = TransactWriteItemsRequest.builder()
                .transactItems(List.of(
                        TransactWriteItem.builder().update(updatePayment).build(),
                        TransactWriteItem.builder().update(updateReservation).build(),
                        TransactWriteItem.builder().put(putAuditLog).build()))
                .build();

        try {
            dynamoDbClient.transactWriteItems(twiRequest);
        } catch (TransactionCanceledException tcx) {
            log.warn("TransactWriteItems cancelled on failed webhook (likely concurrent guard "
                            + "violation). Reasons={}. eventId={}",
                    tcx.cancellationReasons(), event.eventId());
            return;
        }

        // Mark the WebhookEvent row as processed (best-effort; M3 alarm
        // catches lingering processed=false rows).
        idempotencyStore.markProcessed(event.eventId());

        // Tomás §10 M1: log only the code, not the raw message.
        log.info("payment_intent.payment_failed processed. eventId={} paymentIntentIdHash={} "
                        + "transition=payment[{} -> {}]+reservation[{} -> {}] errorCode={}",
                event.eventId(), paymentIntentId.hashCode(),
                PAYMENT_EXPECTED, PAYMENT_TARGET,
                RESERVATION_EXPECTED, reservationTarget,
                lastPaymentErrorCode.isEmpty() ? "(none)" : lastPaymentErrorCode);
    }

    /**
     * Sanitize Stripe-supplied error message before persisting:
     * cap at {@link #MAX_ERROR_MESSAGE_LENGTH}; strip control chars.
     * Returns empty string if input is null/empty (caller treats this
     * as "no error message provided" and persists null on the DDB row).
     */
    static String sanitizeErrorMessage(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = CONTROL_CHARS.matcher(raw).replaceAll("");
        return stripped.length() > MAX_ERROR_MESSAGE_LENGTH
                ? stripped.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : stripped;
    }

    /**
     * Decide reservation transition target based on the per-method
     * expiry window.
     *
     * @return {@code true} if the reservation has passed its
     *         {@code expiresAt} (so the failure transitions reservation
     *         to {@code expired}); {@code false} if still within the
     *         retry window (transitions to {@code pending}).
     */
    static boolean isPastExpiry(ReservationView reservation, Instant eventCreated) {
        if (reservation.expiresAt() == null || reservation.expiresAt().isEmpty()) {
            // No expiresAt set on the reservation row: treat as not-yet-
            // expired (allow retry). Belt-and-braces against schema rows
            // written before D-4's per-method expiry shipped.
            return false;
        }
        try {
            Instant expiresAt = Instant.parse(reservation.expiresAt());
            return eventCreated.isAfter(expiresAt);
        } catch (RuntimeException e) {
            // Unparseable expiresAt — treat as not-yet-expired (safer
            // for the user; ops can investigate the bad row).
            return false;
        }
    }

    private ObjectNode buildAuditDetails(
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
        ObjectNode details = objectMapper.createObjectNode();
        details.put("provider", "stripe");
        details.put("actor", "stripe-webhook-lambda");
        details.put("lambdaVersion", lambdaVersion);
        details.put("gitSha", gitSha);
        details.put("eventId", event.eventId());
        details.put("stripePaymentIntentId", paymentIntentId);
        details.put("paymentRowId", payment.id());
        details.put("paymentRowVersion", payment.version());
        details.put("receivedAt", receivedAt.toString());
        ObjectNode keyNode = details.putObject("reservationKey");
        keyNode.put("userId", reservationKey.userId());
        keyNode.put("investmentId", reservationKey.investmentId());
        keyNode.put("investmentVersion", reservationKey.investmentVersion());
        keyNode.put("requestedAt", reservationKey.requestedAt());
        keyNode.put("version", reservationKey.version());
        details.put("amountCents", payment.amountCents());
        details.put("currency", payment.currency());
        ObjectNode transition = details.putObject("transition");
        transition.put("payment", paymentTransition);
        transition.put("reservation", reservationTransition);
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
