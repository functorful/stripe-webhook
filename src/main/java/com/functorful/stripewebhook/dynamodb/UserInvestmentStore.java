package com.functorful.stripewebhook.dynamodb;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the idempotent {@link Put} payload that creates the
 * {@code UserInvestment} row on {@code payment_intent.succeeded}.
 *
 * <p>Key shape (from {@code application/amplify/data/resource.ts}):
 * <ul>
 *   <li>partition key: {@code userId}</li>
 *   <li>sort key attribute: {@code "investmentId#investmentVersion"}
 *       (literal {@code #} in the attribute name — same Amplify-generated
 *       convention as InvestmentReservation).</li>
 * </ul>
 *
 * <p><strong>Idempotency primitive:</strong> the put carries
 * {@code conditionExpression = "attribute_not_exists(userId)"}. If the
 * row already exists (the unlikely-but-possible "prior handler partially
 * wrote then crashed" case), the surrounding {@code TransactWriteItems}
 * fails atomically. The next webhook replay short-circuits at the
 * idempotency store; manual reconciliation reads the row, confirms the
 * UserInvestment is intact, and bumps {@code WebhookEvent.processed=true}
 * out-of-band.
 */
@Slf4j
@Singleton
public class UserInvestmentStore {

    static final String SORT_KEY_ATTRIBUTE_NAME = "investmentId#investmentVersion";
    static final String SORT_KEY_SEPARATOR = "#";

    private final String tableName;

    public UserInvestmentStore(@Value("${user-investment.table-name}") String tableName) {
        this.tableName = tableName;
    }

    /** Visible for testing. */
    public String tableName() {
        return tableName;
    }

    /**
     * Build (do NOT execute) the {@link Put} that creates the
     * UserInvestment row idempotently. Caller adds it to the
     * surrounding {@code TransactWriteItems} request.
     *
     * @param userId            owner of the investment
     * @param investmentId      target investment id
     * @param investmentVersion target investment version (composite identifier)
     * @param participations    number of participations purchased
     * @param now               wall-clock for {@code createdAt}/{@code updatedAt}
     */
    public Put buildIdempotentPut(
            String userId,
            String investmentId,
            long investmentVersion,
            long participations,
            Instant now
    ) {
        String sortKeyValue = investmentId + SORT_KEY_SEPARATOR + investmentVersion;
        String createdAt = now.toString();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("userId", AttributeValue.fromS(userId));
        item.put(SORT_KEY_ATTRIBUTE_NAME, AttributeValue.fromS(sortKeyValue));
        item.put("investmentId", AttributeValue.fromS(investmentId));
        item.put("investmentVersion", AttributeValue.fromN(Long.toString(investmentVersion)));
        item.put("participations", AttributeValue.fromN(Long.toString(participations)));
        item.put("createdAt", AttributeValue.fromS(createdAt));
        item.put("updatedAt", AttributeValue.fromS(createdAt));
        item.put("__typename", AttributeValue.fromS("UserInvestment"));

        // attribute_not_exists check on the partition key (userId): if a
        // prior partial-write created the row, the transaction fails and
        // an ops sweep handles it. See class-level javadoc for the
        // recovery path.
        return Put.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(userId)")
                .build();
    }
}
