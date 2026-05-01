package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * StripeWebhookLambda entry point. Per the EPIC-PAY 360 review (PAY-02):
 *
 * <ol>
 *   <li>verify the {@code Stripe-Signature} HMAC against the cached
 *       cold-start-fetched secret (≤ 5-min timestamp tolerance);</li>
 *   <li>parse the event id and type from the verified raw body;</li>
 *   <li>conditional-{@code PutItem} on
 *       {@code WebhookEvent.eventId} for at-least-once-delivery
 *       deduplication;</li>
 *   <li>dispatch to a per-type handler (empty handlers in PAY-02; real
 *       ones in PAY-05);</li>
 *   <li>return 200 fast (sub-second).</li>
 * </ol>
 *
 * <p>This class is a thin Micronaut-aware wrapper. The orchestration logic
 * lives in {@link WebhookEventProcessor} so it can be unit-tested without
 * bringing up an application context.
 *
 * <p>Failure mapping:
 * <ul>
 *   <li>missing / malformed / stale / mismatched signature → 400 with a
 *       generic error body (no detail leakage to clients).</li>
 *   <li>malformed JSON body → 400 with a generic error body.</li>
 *   <li>cold-start sentinel detected → bean construction throws,
 *       Lambda runtime returns 5xx for any in-flight invocation.</li>
 *   <li>any other unexpected exception → 500.</li>
 * </ul>
 */
@Slf4j
public class FunctionRequestHandler extends MicronautRequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Inject
    WebhookEventProcessor processor;

    @Override
    public APIGatewayV2HTTPResponse execute(APIGatewayV2HTTPEvent input) {
        return processor.process(input);
    }
}
