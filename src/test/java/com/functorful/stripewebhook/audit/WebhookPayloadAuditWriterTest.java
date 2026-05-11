package com.functorful.stripewebhook.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookPayloadAuditWriter} — the post-verify /
 * pre-parse S3 audit-log writer (PAY-08).
 *
 * <p>The writer is plain-Java; we construct it directly with a mock
 * {@link S3Client} rather than going through the Micronaut application
 * context.
 */
@ExtendWith(MockitoExtension.class)
class WebhookPayloadAuditWriterTest {

    private static final String BUCKET_NAME = "sobrado-test-webhook-payload-audit-log";
    private static final String REQUEST_ID = "abc-123-xyz";
    private static final String STRIPE_SIGNATURE_VALUE =
            "t=1714564800,v1=deadbeefcafebabe1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String RAW_BODY =
            "{\"id\":\"evt_test_1\",\"type\":\"payment_intent.succeeded\","
                    + "\"data\":{\"object\":{\"id\":\"pi_test\"}}}";
    // 2026-05-01 12:34:56 UTC — fixed clock for deterministic key + metadata.
    private static final Instant FIXED_NOW = Instant.parse("2026-05-01T12:34:56Z");
    // Object key shape: YYYY/MM/DD/HH/<epochMillis>-<requestId>.json
    private static final String EXPECTED_KEY_PATTERN =
            "^\\d{4}/\\d{2}/\\d{2}/\\d{2}/\\d+-[A-Za-z0-9-]+\\.json$";

    @Mock
    S3Client s3Client;

    WebhookPayloadAuditProperties properties;
    WebhookPayloadAuditWriter writer;

    @BeforeEach
    void setUp() {
        properties = new WebhookPayloadAuditProperties();
        properties.setBucketName(BUCKET_NAME);
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        writer = new WebhookPayloadAuditWriter(s3Client, properties, fixedClock);
    }

    @Test
    void happyPathWritesObjectWithExpectedBucketKeyContentTypeAndMetadata() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        writer.recordVerifiedPayload(RAW_BODY, STRIPE_SIGNATURE_VALUE, REQUEST_ID);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest sent = requestCaptor.getValue();
        assertThat(sent.bucket()).isEqualTo(BUCKET_NAME);
        assertThat(sent.key())
                .as("object key must match YYYY/MM/DD/HH/<epochMillis>-<requestId>.json")
                .matches(EXPECTED_KEY_PATTERN)
                .startsWith("2026/05/01/12/")
                .endsWith("-" + REQUEST_ID + ".json");
        assertThat(sent.contentType()).isEqualTo(WebhookPayloadAuditWriter.CONTENT_TYPE_JSON);

        Map<String, String> metadata = sent.metadata();
        assertThat(metadata)
                .containsEntry(WebhookPayloadAuditWriter.METADATA_STRIPE_SIGNATURE, STRIPE_SIGNATURE_VALUE)
                .containsEntry(WebhookPayloadAuditWriter.METADATA_RECEIVED_AT, FIXED_NOW.toString())
                .containsEntry(WebhookPayloadAuditWriter.METADATA_REQUEST_ID, REQUEST_ID);
    }

    @Test
    void s3ExceptionDoesNotPropagateFailSoftPolicy() {
        // Fail-soft policy: an S3 write failure (NoSuchBucket, AccessDenied,
        // KMS-failure on bucket-default SSE, transport timeout, etc.) MUST
        // NOT propagate — the loud signal is the bucket-side CloudWatch
        // alarm `webhook-payload-audit-put-failure`. Rethrowing would 5xx
        // the webhook and force Stripe into an indefinite retry loop while
        // the bucket is broken.
        S3Exception serviceError = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDenied")
                        .errorMessage("Access Denied")
                        .build())
                .message("Access Denied")
                .build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(serviceError);

        assertThatCode(() -> writer.recordVerifiedPayload(RAW_BODY, STRIPE_SIGNATURE_VALUE, REQUEST_ID))
                .as("S3Exception from putObject must not propagate (fail-soft, alarm signals)")
                .doesNotThrowAnyException();
    }

    @Test
    void nullStripeSignatureHeaderCoercedToEmptyStringWithoutNpe() {
        // Defensive: a request that somehow lacks the Stripe-Signature
        // header would already have been rejected by signature verification
        // upstream. Even so, the audit writer must be null-safe — putAll
        // into a HashMap NPEs on a null value, so coerce to empty string.
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertThatCode(() -> writer.recordVerifiedPayload(RAW_BODY, null, REQUEST_ID))
                .as("null Stripe-Signature header must not NPE")
                .doesNotThrowAnyException();

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(1)).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(requestCaptor.getValue().metadata())
                .as("null Stripe-Signature header is coerced to empty string in metadata")
                .containsEntry(WebhookPayloadAuditWriter.METADATA_STRIPE_SIGNATURE, "");
    }
}
