package com.functorful.stripewebhook.idempotency;

import io.micronaut.context.annotation.Value;
import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

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
    @NewSpan
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

    /**
     * Mark a previously-recorded event row as fully processed (PAY-05).
     * Called by the handlers after a successful business-state write
     * (the {@code TransactWriteItems} + best-effort SES email).
     *
     * <p>Best-effort: this update is NOT critical for correctness —
     * Stripe-side replays still short-circuit on
     * {@code attribute_not_exists(eventId)} via
     * {@link #recordFirstDelivery}, so leaving {@code processed=false}
     * does not double-credit anything. The flag is purely an
     * operator-readable signal that the row reached completion. The
     * Phase-2b CloudWatch alarm (Tomás §10 M3) fires on
     * {@code processed=false count > 0 for >5min}, which is the
     * actionable signal — a stale {@code processed=false} surfaces
     * either a Lambda crash mid-write, a partial-write recovery
     * target, or a downstream-service failure.
     *
     * <p>Catches all exceptions and logs at WARN: a failure here must
     * NOT propagate — the payment is already confirmed in DDB at this
     * point, so the orchestrator must still return 200 to Stripe. The
     * M3 alarm is the safety net for any row that lingers as a
     * result.
     *
     * @param eventId the Stripe event id (the WebhookEvent partition key).
     */
    @NewSpan
    public void markProcessed(String eventId) {
        try {
            UpdateItemRequest request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("eventId", AttributeValue.fromS(eventId)))
                    .updateExpression("SET #p = :true")
                    .expressionAttributeNames(Map.of("#p", "processed"))
                    .expressionAttributeValues(Map.of(":true", AttributeValue.fromBool(true)))
                    .conditionExpression("attribute_exists(eventId)")
                    .build();
            dynamoDbClient.updateItem(request);
        } catch (RuntimeException e) {
            // Tomás §10 M3 safety net catches lingering processed=false
            // rows, so a failure here is not silent — it surfaces
            // operationally. Logging at WARN (not ERROR) because the
            // business state is already correct.
            log.warn("Failed to mark WebhookEvent processed; M3 alarm will surface this if "
                            + "the row stays false for >5min. eventId={} errorClass={}",
                    eventId, e.getClass().getSimpleName());
        }
    }

    public enum RecordResult {
        FIRST_DELIVERY,
        REPLAY
    }
}
