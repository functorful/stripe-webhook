package com.functorful.stripewebhook.audit;

import io.micronaut.tracing.annotation.NewSpan;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes every signature-verified webhook payload to the Object-Lock
 * GOVERNANCE+7y S3 audit-log bucket (PAY-08).
 *
 * <p><strong>Insertion point (do not move).</strong> The
 * {@link com.functorful.stripewebhook.WebhookEventProcessor} calls
 * {@link #recordVerifiedPayload(String, String, String)} immediately
 * after HMAC signature verification succeeds and BEFORE the JSON
 * envelope is parsed. Three layered properties anchor this insertion
 * point:
 * <ol>
 *   <li><strong>Trusted bytes only.</strong> Pre-signature-verify writes
 *       would let an unauthenticated attacker plant entries.</li>
 *   <li><strong>Forensics-complete.</strong> Even payloads that
 *       subsequently fail Serde parse get audited — if our parser
 *       regresses, raw bytes are recoverable.</li>
 *   <li><strong>No event-id dependency.</strong> The object key shape is
 *       parser-independent (the parser may fail).</li>
 * </ol>
 *
 * <p><strong>Object key shape:</strong>
 * {@code YYYY/MM/DD/HH/<epochMillis>-<requestId>.json} (UTC components of
 * the receiver clock). Hour-level prefix yields cheap forensic range scans
 * over an hour and avoids hot-prefix throttling at our QPS.
 *
 * <p><strong>Object metadata:</strong>
 * <ul>
 *   <li>{@code stripe-signature} — verbatim Stripe-Signature header
 *       (already verified). Recoverable signing-timestamp + signature for
 *       offline re-verification.</li>
 *   <li>{@code received-at} — ISO-8601 receiver clock at audit time.</li>
 *   <li>{@code request-id} — API Gateway HTTP request id, for correlation
 *       with the API Gateway access log.</li>
 * </ul>
 *
 * <p><strong>SSE:</strong> intentionally NOT set in the request — the
 * bucket-level default ({@code aws:kms} with the application CMK) is
 * source-of-truth (IAM-2: don't restate AWS defaults in code). The IaC
 * file {@code aws-webhook-payload-audit-s3.tofu} owns the encryption
 * contract; this writer is content-agnostic.
 *
 * <p><strong>Fail-soft policy.</strong> An S3 write failure is logged at
 * ERROR but DOES NOT propagate. Rethrowing would 5xx the webhook and
 * force Stripe into its retry loop while the bucket is broken (which
 * would still not unstick the audit gap). The loud signal comes from
 * the bucket-side CloudWatch alarm {@code webhook-payload-audit-put-failure}
 * provisioned in IaC.
 *
 * <p><strong>OBS-3 axis 1.</strong> The {@link NewSpan} annotation
 * surfaces this operation as a child span of the processor's
 * {@code process} span in the trace — the post-verify / pre-parse
 * insertion point is observable at deploy time.
 *
 * <p><strong>ADR-0008 boundary.</strong> The writer accepts raw bytes
 * only — it never receives a typed {@code StripeEvent}. The audit layer
 * runs pre-parse and must remain parser-independent.
 */
@Slf4j
@Singleton
public class WebhookPayloadAuditWriter {

    static final String CONTENT_TYPE_JSON = "application/json";
    static final String METADATA_STRIPE_SIGNATURE = "stripe-signature";
    static final String METADATA_RECEIVED_AT = "received-at";
    static final String METADATA_REQUEST_ID = "request-id";

    private final S3Client s3Client;
    private final WebhookPayloadAuditProperties properties;
    private final Clock clock;

    public WebhookPayloadAuditWriter(
            S3Client s3Client,
            WebhookPayloadAuditProperties properties,
            Clock clock
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Persist a signature-verified payload to the audit-log bucket.
     * Fail-soft: any S3 error is logged at ERROR but not rethrown.
     *
     * @param rawBody                verified HTTP body bytes (caller has
     *                               already validated the Stripe HMAC)
     * @param stripeSignatureHeader  verbatim Stripe-Signature header
     *                               (nullable; coerced to empty string)
     * @param requestId              API Gateway HTTP request id
     *                               (nullable; coerced to empty string)
     */
    @NewSpan
    public void recordVerifiedPayload(
            String rawBody,
            String stripeSignatureHeader,
            String requestId
    ) {
        Instant now = clock.instant();
        ZonedDateTime utcNow = ZonedDateTime.ofInstant(now, ZoneOffset.UTC);
        String objectKey = String.format(
                "%04d/%02d/%02d/%02d/%d-%s.json",
                utcNow.getYear(),
                utcNow.getMonthValue(),
                utcNow.getDayOfMonth(),
                utcNow.getHour(),
                now.toEpochMilli(),
                requestId == null ? "" : requestId
        );

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_STRIPE_SIGNATURE,
                stripeSignatureHeader == null ? "" : stripeSignatureHeader);
        metadata.put(METADATA_RECEIVED_AT, now.toString());
        metadata.put(METADATA_REQUEST_ID, requestId == null ? "" : requestId);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucketName())
                .key(objectKey)
                .contentType(CONTENT_TYPE_JSON)
                .metadata(metadata)
                // SSE intentionally omitted: bucket-default aws:kms with
                // application CMK is source-of-truth (IAM-2).
                .build();

        try {
            s3Client.putObject(request, RequestBody.fromString(rawBody, StandardCharsets.UTF_8));
            // Tomás-veto-discipline (identical to the signature-failure
            // log path): NEVER log body content. Object key only.
            log.debug("Wrote webhook payload audit-log object. key={}", objectKey);
        } catch (S3Exception e) {
            // Service-side failure (NoSuchBucket, AccessDenied, KMS
            // failure on the bucket-default SSE, etc.). Fail-soft: the
            // bucket-side CloudWatch alarm
            // (webhook-payload-audit-put-failure) is the loud signal.
            // Rethrowing would 5xx the webhook and force Stripe to
            // retry indefinitely while the bucket is broken.
            log.error("Webhook payload audit-log write failed. key={} errorClass={}",
                    objectKey, e.getClass().getSimpleName(), e);
        } catch (SdkException e) {
            // Client / transport failure (DNS, TLS, timeout, network).
            // Same fail-soft posture as the service-side case above —
            // the alarm signals; we do not 5xx Stripe.
            log.error("Webhook payload audit-log write failed. key={} errorClass={}",
                    objectKey, e.getClass().getSimpleName(), e);
        }
    }
}
