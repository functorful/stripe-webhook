package com.functorful.stripewebhook.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link Put} payload for the {@code AuditLog} row that
 * accompanies every PAY-05 success/failure webhook.
 *
 * <p>Schema (from {@code application/amplify/data/resource.ts}):
 * <ul>
 *   <li>{@code userId} (string, required, GSI PK on
 *       {@code auditLogsByUserId})</li>
 *   <li>{@code timestamp} (datetime, required, GSI SK)</li>
 *   <li>{@code eventType} ({@code AuditEventType} enum)</li>
 *   <li>{@code resource} (string)</li>
 *   <li>{@code action} (string)</li>
 *   <li>{@code details} (json, optional)</li>
 * </ul>
 *
 * <p>Tomás's design review §10 M2 + R3 require {@code receivedAt}
 * (Lambda wall-clock) and {@code lambdaVersion} / {@code gitSha} in
 * {@code details} for forensic correlation. See
 * {@code docs/pay-05-handler-design.md} §5 for the full payload shape.
 *
 * <p>{@code AuditEventType} reuses {@code DATA_ACCESS} per the
 * design-doc decision (zero schema migration, semantic distinction in
 * the searchable {@code action} field). The schema's id field is
 * Amplify-generated UUID; we generate it client-side here for the put
 * key.
 */
@Slf4j
@Singleton
public class AuditLogStore {

    static final String EVENT_TYPE_DATA_ACCESS = "DATA_ACCESS";

    private final String tableName;
    private final ObjectMapper objectMapper;

    public AuditLogStore(@Value("${audit-log.table-name}") String tableName) {
        this(tableName, new ObjectMapper());
    }

    /** Test seam — injects a shared ObjectMapper for deterministic JSON ordering. */
    AuditLogStore(String tableName, ObjectMapper objectMapper) {
        this.tableName = tableName;
        this.objectMapper = objectMapper;
    }

    /** Visible for testing. */
    public String tableName() {
        return tableName;
    }

    /**
     * Build (do NOT execute) the {@link Put} that records an audit row
     * for a single PAY-05 webhook handler outcome.
     *
     * @param entry the audit content; see {@link Entry} for fields.
     * @return the Put to add to the surrounding TransactWriteItems.
     */
    public Put buildPut(Entry entry) {
        String id = UUID.randomUUID().toString();
        String timestamp = entry.timestamp().toString();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("id", AttributeValue.fromS(id));
        item.put("userId", AttributeValue.fromS(entry.userId()));
        item.put("timestamp", AttributeValue.fromS(timestamp));
        item.put("eventType", AttributeValue.fromS(EVENT_TYPE_DATA_ACCESS));
        item.put("resource", AttributeValue.fromS(entry.resource()));
        item.put("action", AttributeValue.fromS(entry.action()));
        item.put("details", AttributeValue.fromS(serializeDetails(entry.details())));
        // Amplify-managed metadata fields.
        item.put("createdAt", AttributeValue.fromS(timestamp));
        item.put("updatedAt", AttributeValue.fromS(timestamp));
        item.put("__typename", AttributeValue.fromS("AuditLog"));

        // attribute_not_exists check on id (UUID collision is
        // astronomically unlikely; the conditional check is cheap
        // insurance and signals "this is a fresh row, not an update").
        return Put.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(id)")
                .build();
    }

    private String serializeDetails(ObjectNode details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            // Should never happen for an in-memory ObjectNode; if it
            // does, fall back to an empty object so the audit row is
            // still written rather than failing the whole transaction.
            log.error("Failed to serialize AuditLog details; writing empty object. errorClass={}",
                    e.getClass().getSimpleName(), e);
            return "{}";
        }
    }

    /**
     * Audit entry payload. {@link #details} is built by the handler
     * and includes the per-PAY-05-§5 fields: provider, actor,
     * lambdaVersion, gitSha, eventId, stripePaymentIntentId,
     * paymentRowId, paymentRowVersion, receivedAt, reservationKey,
     * amountCents, currency, transition, plus
     * lastPaymentErrorCode / lastPaymentError on failures.
     */
    public record Entry(
            String userId,
            Instant timestamp,
            String resource,
            String action,
            ObjectNode details
    ) {}
}
