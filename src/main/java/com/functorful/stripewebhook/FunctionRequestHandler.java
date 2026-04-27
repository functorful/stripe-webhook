package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.function.aws.MicronautRequestHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class FunctionRequestHandler extends MicronautRequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse execute(APIGatewayV2HTTPEvent input) {
        log.info("Received Stripe webhook event (bootstrap stub): rawPath={}, method={}",
                input.getRawPath(),
                input.getRequestContext() != null && input.getRequestContext().getHttp() != null
                        ? input.getRequestContext().getHttp().getMethod()
                        : null);

        APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();
        response.setStatusCode(200);
        response.setHeaders(Map.of("Content-Type", "application/json"));
        response.setBody("{\"received\":true,\"stub\":true}");
        return response;
    }
}
