package com.functorful.stripewebhook.email;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.util.List;

/**
 * Sends the Phase-1 payment confirmation email via Amazon SES v2.
 *
 * <p><strong>Phase-1 placeholder copy.</strong> The body and subject
 * here are English placeholders. PAY-21 owns the localised pt-PT copy
 * + template and will swap this implementation to {@code SendBulkEmail}
 * with a managed SES template + the participation count, property
 * name, and reservation reference rendered from the template variables.
 *
 * <p><strong>SES grant scope</strong> (Tomás §10 R2): the IAM grant on
 * the Lambda role uses
 * {@code Resource = arn:aws:ses:eu-west-1:<acct>:identity/<from-domain>}
 * AND a {@code Condition: ses:FromAddress = "noreply@<env-domain>"}.
 * This service ONLY uses {@code SendEmail} (no {@code SendRawEmail},
 * no attachments). PAY-21's email-template work owns the grant
 * expansion and bounce/complaint configuration.
 *
 * <p><strong>Failure semantics:</strong> SES failure is best-effort.
 * The handler logs a warning and continues — the payment is already
 * confirmed in DDB at this point; the email is a courtesy. The
 * investor will see the success in-app via the existing DDB-stream →
 * AppSync subscription bridge (PAY-09's PaymentBloc subscription).
 */
@Slf4j
@Singleton
public class SesEmailService {

    private final SesV2Client sesClient;
    private final String fromAddress;

    public SesEmailService(
            SesV2Client sesClient,
            @Value("${ses.from-address}") String fromAddress
    ) {
        this.sesClient = sesClient;
        this.fromAddress = fromAddress;
    }

    /** Visible for testing. */
    public String fromAddress() {
        return fromAddress;
    }

    /**
     * Send the payment confirmation email. Placeholder English copy
     * for Phase 1; PAY-21 swaps to a templated, pt-PT version.
     *
     * @param toAddress       investor's verified email address
     * @param participations  number of participations purchased
     * @param amountCents     amount paid in cents
     * @param paymentIntentId Stripe PaymentIntent id (for the
     *                        investor's reference + ops correlation)
     * @return {@code true} if SES accepted the request, {@code false}
     *         on any failure (already logged at WARN). The handler
     *         must not propagate a {@code false} return — the payment
     *         is confirmed regardless.
     */
    public boolean sendPaymentConfirmation(
            String toAddress,
            long participations,
            long amountCents,
            String paymentIntentId
    ) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .fromEmailAddress(fromAddress)
                    .destination(Destination.builder()
                            .toAddresses(List.of(toAddress))
                            .build())
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder()
                                            .data("Payment confirmation — Sobrado")
                                            .charset("UTF-8")
                                            .build())
                                    .body(Body.builder()
                                            .text(Content.builder()
                                                    .data(buildPlaceholderBody(
                                                            participations,
                                                            amountCents,
                                                            paymentIntentId))
                                                    .charset("UTF-8")
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .build();

            sesClient.sendEmail(request);
            log.info("Payment confirmation email sent. paymentIntentIdHash={}",
                    paymentIntentId.hashCode());
            return true;
        } catch (RuntimeException e) {
            // Best-effort: log + return false, do NOT propagate. The
            // payment is already confirmed in DDB; the email is a
            // courtesy. PAY-21 may add a retry/dead-letter path later.
            log.warn("Payment confirmation email failed; payment is confirmed regardless. "
                            + "paymentIntentIdHash={} errorClass={}",
                    paymentIntentId.hashCode(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Phase-1 placeholder body. Plain text, English. PAY-21 replaces
     * with a localised template render. The body intentionally avoids
     * any URLs / links — keeps the trust envelope tight and avoids
     * giving phishers a structural pattern to mimic.
     */
    private static String buildPlaceholderBody(
            long participations,
            long amountCents,
            String paymentIntentId
    ) {
        return "Hello,\n\n"
                + "Your payment to Sobrado has been received. "
                + participations + " participation" + (participations == 1 ? "" : "s")
                + " · " + (amountCents / 100) + "." + String.format("%02d", amountCents % 100)
                + " EUR.\n\n"
                + "Reference: " + paymentIntentId + "\n\n"
                + "This is an automated message. Please do not reply.\n";
    }
}
