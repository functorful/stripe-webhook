package com.functorful.stripewebhook.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyStoreTest {

    private static final String TABLE_NAME = "WebhookEvent-test-suffix-NONE";
    private static final String EVENT_ID = "evt_test_1";
    private static final String SOURCE = "stripe";
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @Mock
    DynamoDbClient dynamoDbClient;

    WebhookIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new WebhookIdempotencyStore(dynamoDbClient, TABLE_NAME);
    }

    @Test
    void firstDeliveryWritesItemAndReturnsFirstDelivery() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        WebhookIdempotencyStore.RecordResult result = store.recordFirstDelivery(EVENT_ID, SOURCE, NOW);

        assertThat(result).isEqualTo(WebhookIdempotencyStore.RecordResult.FIRST_DELIVERY);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient, times(1)).putItem(captor.capture());

        PutItemRequest sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo(TABLE_NAME);
        assertThat(sent.conditionExpression()).isEqualTo("attribute_not_exists(eventId)");
        assertThat(sent.item().get("eventId")).isEqualTo(AttributeValue.fromS(EVENT_ID));
        assertThat(sent.item().get("source")).isEqualTo(AttributeValue.fromS(SOURCE));
        assertThat(sent.item().get("processed")).isEqualTo(AttributeValue.fromBool(false));
        assertThat(sent.item().get("receivedAt")).isEqualTo(AttributeValue.fromS(NOW.toString()));
        // 30-day TTL — epoch seconds.
        long expectedTtl = NOW.plus(WebhookIdempotencyStore.RETENTION).getEpochSecond();
        assertThat(sent.item().get("ttl")).isEqualTo(AttributeValue.fromN(Long.toString(expectedTtl)));
    }

    @Test
    void replayReturnsReplayWithoutSecondPutItem() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder()
                        .message("The conditional request failed")
                        .build());

        WebhookIdempotencyStore.RecordResult result = store.recordFirstDelivery(EVENT_ID, SOURCE, NOW);

        assertThat(result).isEqualTo(WebhookIdempotencyStore.RecordResult.REPLAY);
        verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        // No retry attempt — replay must not generate a second write.
        verify(dynamoDbClient, never()).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
    }
}
