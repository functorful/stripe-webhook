package com.functorful.stripewebhook.idempotency;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Records inbound Stripe webhook events to the {@code WebhookEvent}
 * DynamoDB table for at-least-once-delivery deduplication (Tomás's R1 /
 * T2). The schema lives in {@code application/amplify/data/resource.ts}:
 *
 * <pre>
 *   eventId    string  PK
 *   source     enum    "stripe" | "revolut" | "loqr"
 *   processed  bool    default false
 *   receivedAt datetime
 *   ttl        int?    DDB TTL attribute (epoch seconds, 30-day default)
 * </pre>
 *
 * <p>The dedup primitive is a conditional {@code PutItem} with
 * {@code attribute_not_exists(eventId)}. On success, the event is new
 * and the caller proceeds with dispatch. On
 * {@link ConditionalCheckFailedException}, the event is a replay and the
 * caller short-circuits to a 200 fast-return without further work.
 */
@Slf4j
@Singleton
public class WebhookIdempotencyStore {

    /** 30-day TTL on idempotency rows. Matches PAY-01 schema comment. */
    public static final Duration RETENTION = Duration.ofDays(30);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public WebhookIdempotencyStore(
            DynamoDbClient dynamoDbClient,
            @Value("${webhook-events.table-name}") String tableName
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /**
     * Attempt to record the given event id as new. Returns
     * {@link RecordResult#FIRST_DELIVERY} on the first delivery,
     * {@link RecordResult#REPLAY} if the event id was already recorded.
     *
     * @param eventId the provider's event id (Stripe's {@code evt_...}).
     * @param source  the originating provider (always {@code "stripe"}
     *                from this Lambda; the column is enum-typed in the
     *                schema for forward compatibility with Revolut + Loqr).
     * @param now     the wall-clock instant at which the event was received.
     */
    public RecordResult recordFirstDelivery(String eventId, String source, Instant now) {
        long ttlEpochSeconds = now.plus(RETENTION).getEpochSecond();

        Map<String, AttributeValue> item = Map.of(
                "eventId", AttributeValue.fromS(eventId),
                "source", AttributeValue.fromS(source),
                "processed", AttributeValue.fromBool(false),
                "receivedAt", AttributeValue.fromS(now.toString()),
                "ttl", AttributeValue.fromN(Long.toString(ttlEpochSeconds))
        );

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(eventId)")
                .build();

        try {
            dynamoDbClient.putItem(request);
            return RecordResult.FIRST_DELIVERY;
        } catch (ConditionalCheckFailedException replay) {
            log.info("Webhook event replay detected; skipping. eventId={}", eventId);
            return RecordResult.REPLAY;
        }
    }

    public enum RecordResult {
        FIRST_DELIVERY,
        REPLAY
    }
}
