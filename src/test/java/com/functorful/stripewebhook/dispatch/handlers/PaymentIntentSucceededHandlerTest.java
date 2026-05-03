package com.functorful.stripewebhook.dispatch.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.functorful.stripewebhook.dynamodb.AuditLogStore;
import com.functorful.stripewebhook.dynamodb.InvestmentPaymentStore;
import com.functorful.stripewebhook.dynamodb.InvestmentReservationStore;
import com.functorful.stripewebhook.dynamodb.PaymentView;
import com.functorful.stripewebhook.dynamodb.ReservationView;
import com.functorful.stripewebhook.dynamodb.UserInvestmentStore;
import com.functorful.stripewebhook.email.SesEmailService;
import com.functorful.stripewebhook.event.StripeWebhookEvent;
import com.functorful.stripewebhook.reservation.ReservationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.List;
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
 * Unit coverage for {@link PaymentIntentSucceededHandler}. The handler is
 * the heart of PAY-05 §4.1 — verifies the success-path
 * {@code TransactWriteItems} is built with the four expected operations
 * and the guard-violation paths log + return without writing.
 */
class PaymentIntentSucceededHandlerTest {

    private static final String EVENT_ID = "evt_succeeded_1";
    private static final String PAYMENT_INTENT_ID = "pi_test_1";
    private static final String USER_ID = "user-uuid-42";
    private static final String INVESTMENT_ID = "inv-uuid-7";
    private static final long INVESTMENT_VERSION = 1L;
    private static final String REQUESTED_AT = "2026-05-01T10:00:00.000Z";
    private static final long RESERVATION_VERSION = 1L;
    private static final long PARTICIPATIONS = 5L;
    private static final long AMOUNT_CENTS = 50_000L;
    private static final Instant EVENT_CREATED = Instant.parse("2026-05-03T10:30:00Z");

    private InvestmentReservationStore reservationStore;
    private InvestmentPaymentStore paymentStore;
    private UserInvestmentStore userInvestmentStore;
    private AuditLogStore auditLogStore;
    private SesEmailService sesEmailService;
    private DynamoDbClient dynamoDbClient;
    private PaymentIntentSucceededHandler handler;

    @BeforeEach
    void setUp() {
        reservationStore = mock(InvestmentReservationStore.class);
        paymentStore = mock(InvestmentPaymentStore.class);
        userInvestmentStore = mock(UserInvestmentStore.class);
        auditLogStore = mock(AuditLogStore.class);
        sesEmailService = mock(SesEmailService.class);
        dynamoDbClient = mock(DynamoDbClient.class);

        // Stub the store-builder methods to return non-null DDB payloads
        // so the handler can compose the TWI request. We assert the
        // request shape, not the inner attribute values (those are the
        // store classes' own unit-test surface).
        when(paymentStore.buildStatusUpdate(any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(Update.builder().tableName("payment-tbl").build());
        when(reservationStore.buildStatusUpdate(any(), any(), any(), any()))
                .thenReturn(Update.builder().tableName("reservation-tbl").build());
        when(userInvestmentStore.buildIdempotentPut(any(), any(), anyLong(), anyLong(), any()))
                .thenReturn(Put.builder().tableName("user-inv-tbl").build());
        when(auditLogStore.buildPut(any()))
                .thenReturn(Put.builder().tableName("audit-tbl").build());

        handler = new PaymentIntentSucceededHandler(
                reservationStore, paymentStore, userInvestmentStore,
                auditLogStore, sesEmailService, dynamoDbClient,
                new ObjectMapper(),
                "v0.0.5", "abc123"
        );
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    // ------------------------------------------------------------
    // Case 1 (design §8): happy path — succeeded
    // ------------------------------------------------------------
    @Test
    void happyPath_buildsAtomicFourWriteTransactionAndSendsEmail() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), eq(PAYMENT_INTENT_ID)))
                .thenReturn(Optional.of(payment));

        StripeWebhookEvent event = newEvent("investor@example.com");

        handler.handle(event);

        // 1. Atomic TWI with 4 transact items.
        ArgumentCaptor<TransactWriteItemsRequest> twiCaptor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDbClient).transactWriteItems(twiCaptor.capture());
        TransactWriteItemsRequest twi = twiCaptor.getValue();
        assertThat(twi.transactItems()).hasSize(4);

        // 2. Each store builder was called with the documented inputs.
        verify(paymentStore).buildStatusUpdate(
                eq("pay-uuid-1"), eq(1L),
                eq("processed"), eq("success"),
                eq(EVENT_CREATED), eq(null));
        verify(reservationStore).buildStatusUpdate(
                any(ReservationKey.class), eq("confirmed"), eq("executed"),
                any(Instant.class));
        verify(userInvestmentStore).buildIdempotentPut(
                eq(USER_ID), eq(INVESTMENT_ID), eq(INVESTMENT_VERSION),
                eq(PARTICIPATIONS), any(Instant.class));
        verify(auditLogStore).buildPut(any());

        // 3. SES sent with the receipt_email from the event.
        verify(sesEmailService).sendPaymentConfirmation(
                eq("investor@example.com"),
                eq(PARTICIPATIONS),
                eq(AMOUNT_CENTS),
                eq(PAYMENT_INTENT_ID));
    }

    // ------------------------------------------------------------
    // Case 4 (design §8): payment already success → guard, no write
    // ------------------------------------------------------------
    @Test
    void guardViolation_paymentAlreadySuccess_skipsWrite() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        PaymentView paymentAlreadySucceeded = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "success");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any()))
                .thenReturn(Optional.of(paymentAlreadySucceeded));

        handler.handle(newEvent("investor@example.com"));

        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
        verify(sesEmailService, never()).sendPaymentConfirmation(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------
    // Case 6 (design §8): reservation already executed → guard
    // ------------------------------------------------------------
    @Test
    void guardViolation_reservationAlreadyExecuted_skipsWrite() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "executed", null);

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));

        handler.handle(newEvent("investor@example.com"));

        verify(paymentStore, never()).findByReservationAndIntent(any(), any());
        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
    }

    // ------------------------------------------------------------
    // Case 8 (design §8): missing InvestmentPayment → log + 200, no write
    // ------------------------------------------------------------
    @Test
    void missingPayment_logsAndSkipsWrite() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any())).thenReturn(Optional.empty());

        handler.handle(newEvent("investor@example.com"));

        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
        verify(sesEmailService, never()).sendPaymentConfirmation(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------
    // Case 9 (design §8): missing InvestmentReservation → log + 200, no write
    // ------------------------------------------------------------
    @Test
    void missingReservation_logsAndSkipsWrite() {
        when(reservationStore.load(any())).thenReturn(Optional.empty());

        handler.handle(newEvent("investor@example.com"));

        verify(paymentStore, never()).findByReservationAndIntent(any(), any());
        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
    }

    // ------------------------------------------------------------
    // Case 10 (design §8): TransactionCanceledException (e.g., concurrent
    // guard violation, partial-write recovery target) → log + 200
    // ------------------------------------------------------------
    @Test
    void transactionCancelled_doesNotPropagate() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any())).thenReturn(Optional.of(payment));

        TransactionCanceledException tcx = TransactionCanceledException.builder()
                .message("ConditionalCheckFailed on item 0")
                .cancellationReasons(List.of(
                        CancellationReason.builder().code("ConditionalCheckFailed").build()))
                .build();
        when(dynamoDbClient.transactWriteItems((TransactWriteItemsRequest) any())).thenThrow(tcx);

        // Must not throw — handler swallows and logs.
        handler.handle(newEvent("investor@example.com"));

        verify(dynamoDbClient, times(1)).transactWriteItems((TransactWriteItemsRequest) any());
        verify(sesEmailService, never()).sendPaymentConfirmation(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------
    // Case 11 (design §8): SES failure does not roll back the DDB writes
    // (best-effort email semantics).
    // ------------------------------------------------------------
    @Test
    void sesFailure_doesNotRollBack() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any())).thenReturn(Optional.of(payment));
        when(sesEmailService.sendPaymentConfirmation(any(), anyLong(), anyLong(), any()))
                .thenReturn(false);

        handler.handle(newEvent("investor@example.com"));

        // DDB writes happened; SES called and returned false; no exception.
        verify(dynamoDbClient).transactWriteItems((TransactWriteItemsRequest) any());
        verify(sesEmailService).sendPaymentConfirmation(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------
    // Case 16 (design §8, Tomás §10 M4): event missing data.object.id
    // ------------------------------------------------------------
    @Test
    void missingPaymentIntentId_logsAndSkipsWithoutWrite() {
        StripeWebhookEvent eventNoId = new StripeWebhookEvent(
                EVENT_ID,
                "payment_intent.succeeded",
                EVENT_CREATED,
                new ObjectMapper().createObjectNode() // empty data.object
        );

        handler.handle(eventNoId);

        verify(reservationStore, never()).load(any());
        verify(dynamoDbClient, never()).transactWriteItems((TransactWriteItemsRequest) any());
    }

    // ------------------------------------------------------------
    // Bonus: missing receipt_email → DDB writes still happen, no SES call
    // (PAY-21 follow-up will add metadata-driven email)
    // ------------------------------------------------------------
    @Test
    void missingReceiptEmail_writesDdbButSkipsSes() {
        ReservationView reservation = new ReservationView(
                USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS,
                REQUESTED_AT, RESERVATION_VERSION, "confirmed", null);
        PaymentView payment = new PaymentView(
                "pay-uuid-1", 1L, USER_ID, AMOUNT_CENTS, "EUR",
                PAYMENT_INTENT_ID, "processed");

        when(reservationStore.load(any())).thenReturn(Optional.of(reservation));
        when(paymentStore.findByReservationAndIntent(any(), any())).thenReturn(Optional.of(payment));

        // No receipt_email on the event.
        handler.handle(newEvent(null));

        verify(dynamoDbClient).transactWriteItems((TransactWriteItemsRequest) any());
        verify(sesEmailService, never()).sendPaymentConfirmation(any(), anyLong(), anyLong(), any());
    }

    // --- helpers ---

    private static StripeWebhookEvent newEvent(String receiptEmail) {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode dataObject = mapper.createObjectNode();
        dataObject.put("id", PAYMENT_INTENT_ID);
        if (receiptEmail != null) {
            dataObject.put("receipt_email", receiptEmail);
        }
        com.fasterxml.jackson.databind.node.ObjectNode metadata = dataObject.putObject("metadata");
        metadata.put("reservationUserId", USER_ID);
        metadata.put("reservationInvestmentId", INVESTMENT_ID);
        metadata.put("reservationInvestmentVersion", INVESTMENT_VERSION);
        metadata.put("reservationRequestedAt", REQUESTED_AT);
        metadata.put("reservationVersion", RESERVATION_VERSION);

        JsonNode dataObjectNode = dataObject;
        return new StripeWebhookEvent(EVENT_ID, "payment_intent.succeeded",
                EVENT_CREATED, dataObjectNode);
    }
}
