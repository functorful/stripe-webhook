package com.functorful.stripewebhook.dynamodb;

import com.functorful.stripewebhook.reservation.ReservationKey;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB read + transactional-update builder for {@code InvestmentReservation}
 * rows. Mirrors {@code backend/payment-lambda/InvestmentReservationStore} (same
 * key shape, same Amplify-generated sort-key attribute name with literal
 * {@code #} characters, same Tomás-veto projection discipline).
 *
 * <p>Key shape (from {@code application/amplify/data/resource.ts}):
 * <ul>
 *   <li>partition key: {@code userId}</li>
 *   <li>sort key attribute name: {@code "investmentId#investmentVersion#requestedAt#version"}</li>
 *   <li>sort key value: {@code investmentId#investmentVersion#requestedAt#version} (separator-joined)</li>
 * </ul>
 *
 * <p>This class does NOT execute the update — it builds the
 * {@link Update} payload for inclusion in a {@code TransactWriteItems}
 * call. The handler composes one TWI request that atomically updates
 * payment + reservation, puts UserInvestment, and puts AuditLog.
 *
 * <p><strong>Status transition guard (Tomás §10 R4):</strong> the
 * {@code conditionExpression} pins the expected current status. A
 * {@code TransactionCanceledException} with a guard-failure cancellation
 * reason is the benign path (log + skip + 200); any other failure
 * propagates to the orchestrator's outer catch.
 */
@Slf4j
@Singleton
public class InvestmentReservationStore {

    static final String SORT_KEY_ATTRIBUTE_NAME =
            "investmentId#investmentVersion#requestedAt#version";

    static final String SORT_KEY_SEPARATOR = "#";

    /** Projection: only what the handler needs. */
    static final String PROJECTION_EXPRESSION =
            "userId, investmentId, investmentVersion, participations, requestedAt, version, #s, expiresAt";

    private static final Map<String, String> EXPRESSION_ATTRIBUTE_NAMES = Map.of(
            "#s", "status"
    );

    private final software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamoDbClient;
    private final String tableName;

    public InvestmentReservationStore(
            software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamoDbClient,
            @Value("${investment-reservation.table-name}") String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /** Visible for testing. */
    public String tableName() {
        return tableName;
    }

    /**
     * Strong-consistent read of the projected reservation view.
     * Returns {@link Optional#empty()} if the row does not exist.
     */
    public Optional<ReservationView> load(ReservationKey key) {
        Map<String, AttributeValue> requestKey = composeKey(key);

        log.info("Loading InvestmentReservation. table={} userId={} investmentId={} version={}",
                tableName, key.userId(), key.investmentId(), key.version());

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(requestKey)
                .projectionExpression(PROJECTION_EXPRESSION)
                .expressionAttributeNames(EXPRESSION_ATTRIBUTE_NAMES)
                .consistentRead(true)
                .build();

        GetItemResponse response = dynamoDbClient.getItem(request);
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toView(response.item(), key));
    }

    /**
     * Build (do NOT execute) the {@link Update} that transitions the
     * reservation to the target status, conditional on the current
     * status matching {@code expectedCurrentStatus}.
     *
     * @param key                   composite reservation PK.
     * @param expectedCurrentStatus the status the row MUST currently be
     *                              in for the transition to apply
     *                              (per ARCH-04 transition matrix).
     * @param targetStatus          the new status.
     * @param confirmedAt           value to write to {@code confirmedAt}
     *                              when transitioning to {@code executed}.
     *                              Pass {@code null} for transitions that
     *                              should not touch this field.
     */
    public Update buildStatusUpdate(
            ReservationKey key,
            String expectedCurrentStatus,
            String targetStatus,
            Instant confirmedAt
    ) {
        Map<String, AttributeValue> requestKey = composeKey(key);

        Map<String, String> nameAliases = new LinkedHashMap<>();
        nameAliases.put("#s", "status");
        if (confirmedAt != null) {
            nameAliases.put("#c", "confirmedAt");
        }
        nameAliases.put("#u", "updatedAt");

        Map<String, AttributeValue> values = new LinkedHashMap<>();
        values.put(":new", AttributeValue.fromS(targetStatus));
        values.put(":expected", AttributeValue.fromS(expectedCurrentStatus));
        if (confirmedAt != null) {
            values.put(":c", AttributeValue.fromS(confirmedAt.toString()));
        }
        values.put(":u", AttributeValue.fromS(Instant.now().toString()));

        String updateExpression = confirmedAt != null
                ? "SET #s = :new, #c = :c, #u = :u"
                : "SET #s = :new, #u = :u";

        return Update.builder()
                .tableName(tableName)
                .key(requestKey)
                .updateExpression(updateExpression)
                .conditionExpression("#s = :expected")
                .expressionAttributeNames(nameAliases)
                .expressionAttributeValues(values)
                .build();
    }

    private Map<String, AttributeValue> composeKey(ReservationKey key) {
        String sortKeyValue = key.investmentId()
                + SORT_KEY_SEPARATOR + key.investmentVersion()
                + SORT_KEY_SEPARATOR + key.requestedAt()
                + SORT_KEY_SEPARATOR + key.version();

        Map<String, AttributeValue> requestKey = new LinkedHashMap<>();
        requestKey.put("userId", AttributeValue.fromS(key.userId()));
        requestKey.put(SORT_KEY_ATTRIBUTE_NAME, AttributeValue.fromS(sortKeyValue));
        return requestKey;
    }

    private static ReservationView toView(Map<String, AttributeValue> item, ReservationKey key) {
        return new ReservationView(
                requireString(item, "userId"),
                requireString(item, "investmentId"),
                requireLong(item, "investmentVersion"),
                requireLong(item, "participations"),
                stringOr(item, "requestedAt", key.requestedAt()),
                longOr(item, "version", key.version()),
                requireString(item, "status"),
                stringOr(item, "expiresAt", null)
        );
    }

    private static String requireString(Map<String, AttributeValue> item, String field) {
        AttributeValue av = item.get(field);
        if (av == null || av.s() == null || av.s().isEmpty()) {
            throw new IllegalStateException("reservation projection missing field: " + field);
        }
        return av.s();
    }

    private static long requireLong(Map<String, AttributeValue> item, String field) {
        AttributeValue av = item.get(field);
        if (av == null || av.n() == null) {
            throw new IllegalStateException("reservation projection missing field: " + field);
        }
        return Long.parseLong(av.n());
    }

    private static String stringOr(Map<String, AttributeValue> item, String field, String fallback) {
        AttributeValue av = item.get(field);
        return (av == null || av.s() == null) ? fallback : av.s();
    }

    private static long longOr(Map<String, AttributeValue> item, String field, long fallback) {
        AttributeValue av = item.get(field);
        return (av == null || av.n() == null) ? fallback : Long.parseLong(av.n());
    }
}
