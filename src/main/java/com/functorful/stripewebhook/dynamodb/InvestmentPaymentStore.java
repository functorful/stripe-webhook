package com.functorful.stripewebhook.dynamodb;

import com.functorful.stripewebhook.reservation.ReservationKey;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB read + transactional-update builder for {@code InvestmentPayment}
 * rows. The webhook handler resolves the right payment row via the
 * Amplify-auto-generated GSI on the reservation FK, then filters the
 * (typically 1–3) results in code by {@code stripePaymentIntentId}.
 *
 * <p><strong>Lookup strategy (Tomás-aware):</strong> the design doc §4.1
 * §10 R-section discusses an optional optimisation — extend the
 * PaymentLambda's Stripe metadata block to carry {@code paymentRowId}
 * and {@code paymentRowVersion}, then this store can do a direct
 * {@code GetItem}. Until that PaymentLambda follow-up lands, we use
 * the FK Query + filter path. Both shapes are correct; the FK Query
 * is just a couple of RCUs more per call.
 *
 * <p><strong>GSI name override:</strong> Amplify Gen 2 generates GSIs
 * for {@code belongsTo} relations using the
 * {@code gsi-<ParentModel>.<relationshipField>} convention. The index
 * name is bound through {@link InvestmentPaymentProperties} (default
 * {@code "gsi-InvestmentReservation.payments"}) so operators can adjust
 * without a code deploy if the Amplify-generated name diverges in a
 * future codegen change. A smoke-test failure on this lookup is
 * recoverable by re-pointing the env var; see infrastructure MR !51 +
 * HYG-19 axis-4 anchor for the prior cycle in which this lookup name
 * was empirically wrong against deployed reality.
 *
 * <p>Schema fields surfaced (all read-only on lookup):
 * id, version, userId, amountCents, currency, stripePaymentIntentId,
 * status. See {@link PaymentView}.
 */
@Slf4j
@Singleton
public class InvestmentPaymentStore {

    /** Reservation FK attribute names (Amplify hasMany binding). */
    private static final String[] FK_FIELDS = {
            "userId",
            "investmentId",
            "investmentVersion",
            "reservationRequestedAt",
            "reservationVersion"
    };

    /** Projection — Tomás-veto-discipline: only what the handler acts on. */
    private static final String PROJECTION_EXPRESSION =
            "id, version, userId, amountCents, currency, stripePaymentIntentId, #s";

    private static final Map<String, String> EXPRESSION_ATTRIBUTE_NAMES = Map.of(
            "#s", "status"
    );

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final String byReservationIndexName;

    public InvestmentPaymentStore(
            DynamoDbClient dynamoDbClient,
            InvestmentPaymentProperties properties
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = properties.getTableName();
        this.byReservationIndexName = properties.getByReservationIndexName();
    }

    /** Visible for testing. */
    public String tableName() {
        return tableName;
    }

    /** Visible for testing. */
    public String byReservationIndexName() {
        return byReservationIndexName;
    }

    /**
     * Find the {@code InvestmentPayment} row for the given reservation
     * + Stripe payment intent.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Query the {@code byReservationIndexName} GSI by the
     *       composite reservation FK (5 fields).</li>
     *   <li>Filter results in code by {@code stripePaymentIntentId}
     *       — the GSI does not key on it, but the result set is small
     *       (typically 1, max ~3 across retries).</li>
     * </ol>
     *
     * @return matching {@link PaymentView}, or {@link Optional#empty()}
     *         if no payment was found for this (reservation, intent) pair.
     */
    public Optional<PaymentView> findByReservationAndIntent(
            ReservationKey reservationKey,
            String stripePaymentIntentId
    ) {
        // Build the GSI key condition on the 5 reservation FK fields.
        // Amplify's belongsTo GSI uses the relation tuple as a composite
        // key; the partition key is `userId` and the sort key is the
        // remaining four fields concatenated with `#`. This mirrors the
        // sort-key shape on InvestmentReservation itself.
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":uid", AttributeValue.fromS(reservationKey.userId()));
        values.put(":sk", AttributeValue.fromS(
                reservationKey.investmentId() + "#"
                        + reservationKey.investmentVersion() + "#"
                        + reservationKey.requestedAt() + "#"
                        + reservationKey.version()
        ));

        Map<String, String> nameAliases = new LinkedHashMap<>();
        nameAliases.putAll(EXPRESSION_ATTRIBUTE_NAMES);
        nameAliases.put("#uid", "userId");
        nameAliases.put("#sk", "investmentId#investmentVersion#reservationRequestedAt#reservationVersion");

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(byReservationIndexName)
                .keyConditionExpression("#uid = :uid AND #sk = :sk")
                .projectionExpression(PROJECTION_EXPRESSION)
                .expressionAttributeNames(nameAliases)
                .expressionAttributeValues(values)
                .consistentRead(false) // GSI reads cannot be strong-consistent
                .build();

        log.info("Query InvestmentPayment by reservation FK. table={} index={} userId={} investmentId={}",
                tableName, byReservationIndexName, reservationKey.userId(), reservationKey.investmentId());

        QueryResponse response = dynamoDbClient.query(request);
        return response.items().stream()
                .filter(item -> {
                    AttributeValue piid = item.get("stripePaymentIntentId");
                    return piid != null && piid.s() != null
                            && piid.s().equals(stripePaymentIntentId);
                })
                .map(InvestmentPaymentStore::toView)
                .findFirst();
    }

    /**
     * Build (do NOT execute) the {@link Update} that transitions a
     * payment row to the target status, conditional on the current
     * status matching {@code expectedCurrentStatus}.
     */
    public Update buildStatusUpdate(
            String paymentId,
            long paymentVersion,
            String expectedCurrentStatus,
            String targetStatus,
            Instant paidAt,
            String errorMessage
    ) {
        Map<String, AttributeValue> requestKey = new LinkedHashMap<>();
        requestKey.put("id", AttributeValue.fromS(paymentId));
        requestKey.put("version", AttributeValue.fromN(Long.toString(paymentVersion)));

        Map<String, String> nameAliases = new LinkedHashMap<>();
        nameAliases.put("#s", "status");
        nameAliases.put("#u", "updatedAt");

        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":new", AttributeValue.fromS(targetStatus));
        values.put(":expected", AttributeValue.fromS(expectedCurrentStatus));
        values.put(":u", AttributeValue.fromS(Instant.now().toString()));

        StringBuilder updateExpression = new StringBuilder("SET #s = :new, #u = :u");
        if (paidAt != null) {
            nameAliases.put("#p", "paidAt");
            values.put(":p", AttributeValue.fromS(paidAt.toString()));
            updateExpression.append(", #p = :p");
        }
        if (errorMessage != null) {
            nameAliases.put("#e", "errorMessage");
            values.put(":e", AttributeValue.fromS(errorMessage));
            updateExpression.append(", #e = :e");
        }

        return Update.builder()
                .tableName(tableName)
                .key(requestKey)
                .updateExpression(updateExpression.toString())
                .conditionExpression("#s = :expected")
                .expressionAttributeNames(nameAliases)
                .expressionAttributeValues(values)
                .build();
    }

    private static PaymentView toView(Map<String, AttributeValue> item) {
        return new PaymentView(
                requireString(item, "id"),
                requireLong(item, "version"),
                requireString(item, "userId"),
                requireLong(item, "amountCents"),
                requireString(item, "currency"),
                requireString(item, "stripePaymentIntentId"),
                requireString(item, "status")
        );
    }

    private static String requireString(Map<String, AttributeValue> item, String field) {
        AttributeValue av = item.get(field);
        if (av == null || av.s() == null || av.s().isEmpty()) {
            throw new IllegalStateException("payment projection missing field: " + field);
        }
        return av.s();
    }

    private static long requireLong(Map<String, AttributeValue> item, String field) {
        AttributeValue av = item.get(field);
        if (av == null || av.n() == null) {
            throw new IllegalStateException("payment projection missing field: " + field);
        }
        return Long.parseLong(av.n());
    }
}
