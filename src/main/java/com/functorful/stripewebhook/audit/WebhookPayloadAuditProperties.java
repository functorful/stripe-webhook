package com.functorful.stripewebhook.audit;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;

/**
 * Configuration binding for {@link WebhookPayloadAuditWriter}.
 *
 * <p>Encapsulates the single setting the audit writer needs:
 * <ul>
 *   <li>{@code webhook-payload-audit.bucket-name} (mandatory, no default) —
 *       the Object-Lock-protected S3 bucket name, sourced from the
 *       {@code WEBHOOK_PAYLOAD_AUDIT_BUCKET} env var (wired in
 *       {@code aws-stripe-webhook-lambda.tofu}).</li>
 * </ul>
 *
 * <p><strong>Fail-closed at cold start (Tomás-discipline).</strong> The
 * audit-log is a compliance / forensics requirement — running the Lambda
 * without a target bucket would silently lose every verified payload, so
 * the {@link #validate()} {@code @PostConstruct} hook throws
 * {@link IllegalStateException} if the property is blank. Micronaut
 * surfaces this as a bean construction failure at Lambda init, which
 * the AWS Lambda runtime turns into a {@code Runtime.InitError} on every
 * subsequent invocation — the desired fail-closed posture.
 *
 * <p>Mirrors the {@link com.functorful.stripewebhook.dynamodb.InvestmentPaymentProperties}
 * shape (typed bean over scattered {@code @Value} injection sites), with
 * the addition of an explicit validation hook because — unlike the DDB
 * table name where an unset value would fail-loud on first DDB call — an
 * unset bucket name would silently break audit logging without a runtime
 * failure (the writer would still receive a verified payload and try to
 * write; only the eventual {@link software.amazon.awssdk.services.s3.S3Client}
 * call would fail, by which time the request-id has already been
 * structurally lost from the call site).
 */
@Data
@ConfigurationProperties("webhook-payload-audit")
public class WebhookPayloadAuditProperties {

    private String bucketName;

    /**
     * Fail-closed at Lambda cold start if the audit-log bucket is not
     * configured. An audit requirement does not run in degraded mode.
     */
    @PostConstruct
    public void validate() {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException(
                    "webhook-payload-audit.bucket-name is mandatory but was blank. "
                            + "Set the WEBHOOK_PAYLOAD_AUDIT_BUCKET env var on the Lambda. "
                            + "See aws-stripe-webhook-lambda.tofu and "
                            + "aws-webhook-payload-audit-s3.tofu in the infrastructure repo.");
        }
    }
}
