package com.functorful.stripewebhook.dispatch.handlers;

import com.functorful.stripewebhook.config.BuildMetadataProperties;
import com.functorful.stripewebhook.dynamodb.AuditLogStore;
import com.functorful.stripewebhook.dynamodb.InvestmentPaymentStore;
import com.functorful.stripewebhook.dynamodb.InvestmentReservationStore;
import com.functorful.stripewebhook.dynamodb.PaymentView;
import com.functorful.stripewebhook.dynamodb.ReservationView;
import com.functorful.stripewebhook.event.PaymentIntentError;
import com.functorful.stripewebhook.event.PaymentIntentObject;
import com.functorful.stripewebhook.event.StripeEvent;
import com.functorful.stripewebhook.idempotency.WebhookIdempotencyStore;
import com.functorful.stripewebhook.reservation.ReservationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link PaymentIntentFailedHandler} — focuses on the
 * branch logic (retry vs expired), the message-sanitization invariants
 * Tomás §10 M1 pinned, and the no-email + no-UserInvestment posture
 * specific to the failed path.
 *
 * <p>Post-ARCH-08: handler takes a typed {@link StripeEvent.PaymentIntentFailed}
 * (no JsonNode). Test fixtures build the typed payload with
 * {@link PaymentIntentObject} + {@link PaymentIntentError}; metadata is a
 * {@code Map<String, String>} matching the Stripe wire contract.
 */
class PaymentIntentFailedHandlerTest {

    private static final String EVENT_ID = "evt_failed_1";
    private static final String PAYMENT_INTENT_ID = "pi_test_1";
    private static final String USER_ID = "user-uuid-42";
    private static final String INVESTMENT_ID = "inv-uuid-7";
    private static final long INVESTMENT_VERSION = 1L;
    private static final String REQUESTED_AT = "2026-05-01T10:00:00.000Z";
    private static final long RESERVATION_VERSION = 1L;
    private static final long PARTICIPATIONS = 5L;
    private static final long AMOUNT_CENTS = 50_000L;
    private static final Instant EVENT_CREATED = Instant.parse("2026-05-03T10:30:00Z");

    private static final String LAMBDA_VERSION = "v0.0.5";
    private static final String GIT_SHA = "abc123";

    private InvestmentReservationStore reservationStore;
    private InvestmentPaymentStore paymentStore;
    private AuditLogStore auditLogStore;
    private WebhookIdempotencyStore idempotencyStore;
    private DynamoDbClient dynamoDbClient;
    private BuildMetadataProperties buildMetadata;
    private PaymentIntentFailedHandler handler;

    @BeforeEach
    void setUp() {
        reservationStore = mock(InvestmentReservationStore.class);
        paymentStore = mock(InvestmentPaymentStore.class);
        auditLogStore = mock(AuditLogStore.class);
        idempotencyStore = mock(WebhookIdempotencyStore.class);
        dynamoDbClient = mock(DynamoDbClient.class);

        when(paymentStore.buildStatusUpdate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(Update.builder().tableName("payment-tbl").build());
        when(reservationStore.buildStatusUpdate(any(), any(), any(), any()))
                .thenReturn(Update.builder().tableName("reservation-tbl").build());
        when(auditLogStore.buildPut(any()))
                .thenReturn(Put.builder().tableName("audit-tbl").build());

        buildMetadata = newBuildMetadata(LAMBDA_VERSION, GIT_SHA);

        handler = new PaymentIntentFailedHandler(
                reservationStore, paymentStore, auditLogStore, idempotencyStore,
                dynamoDbClient, buildMetadata,
                "AuditLog-test-table"
        );
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private static BuildMetadataProperties newBuildMetadata(String version, String gitSha) {
        BuildMetadataProperties props = new BuildMetadataProperties();
        props.setVersion(version);
        props.setGitSha(gitSha);
        return props;
    }

    // ------------------------------------------------------------
    // Case 2 (design §8): happy path — failed (reservation back to pending)
    // ------------------------------------------------------------
    @Test
    void happyPath_retryWindowOpen_reservationGoesBackToPending() {
        // expiresAt is well in the future relative to event.created.
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed",
                "2026-05-04T00:00:00Z");
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), eq(PAYMENT_INTENT_ID)))
                .thenReturn(Optional.of(payment));

        handler.handle(newEvent("card_declined", "Your card was declined."));

        verify(paymentStore).buildStatusUpdate(
                eq("pay-uuid-1"), eq(1L),
                eq("processed"), eq("failed"),
                eq(null),
                eq("Your card was declined."));
        verify(reservationStore).buildStatusUpdate(
                any(ReservationKey.class), eq("confirmed"), eq("pending"), eq(null));
        // Failed handler does NOT create UserInvestment and does NOT
        // send email.
        verify(auditLogStore).buildPut(any());
        verify(dynamoDbClient, times(1)).transactWriteItems((TransactWriteItemsRequest) any());
        verify(idempotencyStore).markProcessed(EVENT_ID);
    }

    // ------------------------------------------------------------
    // Case 3 (design §8): happy path — failed (reservation expired)
    // ------------------------------------------------------------
    @Test
    void happyPath_pastExpiresAt_reservationGoesToExpired() {
        // expiresAt strictly before event.created.
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed",
                "2026-05-03T10:00:00Z"); // 30 min before EVENT_CREATED
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any())).thenReturn(Optional.of(payment));

        handler.handle(newEvent("card_declined", "Your card was declined."));

        verify(reservationStore).buildStatusUpdate(
                any(ReservationKey.class), eq("confirmed"), eq("expired"), eq(null));
    }

    // ------------------------------------------------------------
    // Tomás §10 M1: message sanitization — cap at 500 + strip control chars
    // ------------------------------------------------------------
    @Test
    void sanitizeErrorMessage_capsAt500Chars() {
        String oversized = "X".repeat(750);
        String result = PaymentIntentFailedHandler.sanitizeErrorMessage(oversized);
        assertThat(result).hasSize(PaymentIntentFailedHandler.MAX_ERROR_MESSAGE_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "", "", "", ""})
    void sanitizeErrorMessage_stripsControlChars(String controlChar) {
        String input = "before" + controlChar + "after";
        String result = PaymentIntentFailedHandler.sanitizeErrorMessage(input);
        assertThat(result).isEqualTo("beforeafter");
    }

    @Test
    void sanitizeErrorMessage_emptyOrNull_returnsEmpty() {
        assertThat(PaymentIntentFailedHandler.sanitizeErrorMessage(null)).isEmpty();
        assertThat(PaymentIntentFailedHandler.sanitizeErrorMessage("")).isEmpty();
    }

    // ------------------------------------------------------------
    // Defensive: missing event.data.object.id (Tomás §10 M4 case 16).
    // Post-ARCH-08 the boundary parser requires PaymentIntentObject.id
    // so production traffic can't reach this branch — belt-and-braces.
    // ------------------------------------------------------------
    @Test
    void missingPaymentIntentId_skipsWithoutWrite() {
        StripeEvent.PaymentIntentFailed eventNoId = new StripeEvent.PaymentIntentFailed(
                EVENT_ID,
                EVENT_CREATED,
                new PaymentIntentObject("", null, Map.of(), null)
        );

        handler.handle(eventNoId);

        verify(reservationStore, never()).load(any());
        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
    }

    // ------------------------------------------------------------
    // isPastExpiry helper — null/empty expiresAt → not-yet-expired
    // (defensive against schema rows from before D-4 shipped per-method
    // expiry).
    // ------------------------------------------------------------
    @Test
    void isPastExpiry_nullExpiresAt_returnsFalse() {
        ReservationView noExpiry = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        assertThat(PaymentIntentFailedHandler.isPastExpiry(noExpiry, EVENT_CREATED)).isFalse();
    }

    @Test
    void isPastExpiry_emptyExpiresAt_returnsFalse() {
        ReservationView emptyExpiry = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", "");
        assertThat(PaymentIntentFailedHandler.isPastExpiry(emptyExpiry, EVENT_CREATED)).isFalse();
    }

    @Test
    void isPastExpiry_unparseableExpiresAt_returnsFalse() {
        ReservationView garbageExpiry = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", "not-a-datetime");
        assertThat(PaymentIntentFailedHandler.isPastExpiry(garbageExpiry, EVENT_CREATED)).isFalse();
    }

    // ------------------------------------------------------------
    // Tomás M-NEW-1 (infrastructure!14): degraded-mode short-circuit
    // on AUDIT_LOG_TABLE_NAME == "PENDING_AUDIT_LOG_BRIDGE". Without it
    // the 3-item TWI (failed handler doesn't write UserInvestment) would
    // still target a non-existent AuditLog table and roll back
    // atomically, leaving the failure unrecorded.
    // ------------------------------------------------------------
    @Test
    void degradedMode_doesNotTouchDdbAndDoesNotMarkProcessed() {
        PaymentIntentFailedHandler degradedHandler = new PaymentIntentFailedHandler(
                reservationStore, paymentStore, auditLogStore, idempotencyStore,
                dynamoDbClient, buildMetadata,
                "PENDING_AUDIT_LOG_BRIDGE"
        );

        degradedHandler.handle(newEvent("card_declined", "Your card was declined."));

        verify(reservationStore, never()).load(any());
        verify(paymentStore, never()).findByReservationAndIntent(any(), any());
        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
        verify(idempotencyStore, never()).markProcessed(any());
    }

    // --- helpers ---

    private static StripeEvent.PaymentIntentFailed newEvent(String errorCode, String errorMessage) {
        // ADR-0008 "Notes from review": metadata is Map<String, String>;
        // numeric fields go in via Long.toString(...).
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("reservationUserId", USER_ID);
        metadata.put("reservationInvestmentId", INVESTMENT_ID);
        metadata.put("reservationInvestmentVersion", Long.toString(INVESTMENT_VERSION));
        metadata.put("reservationRequestedAt", REQUESTED_AT);
        metadata.put("reservationVersion", Long.toString(RESERVATION_VERSION));

        PaymentIntentError lastPaymentError = new PaymentIntentError(errorCode, errorMessage);

        PaymentIntentObject payload = new PaymentIntentObject(
                PAYMENT_INTENT_ID,
                null,
                metadata,
                lastPaymentError
        );
        return new StripeEvent.PaymentIntentFailed(EVENT_ID, EVENT_CREATED, payload);
    }
}
