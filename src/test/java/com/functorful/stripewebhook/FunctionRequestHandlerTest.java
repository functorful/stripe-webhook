package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionRequestHandlerTest {

    @Test
    void returnsTwoHundredForAnyInput() {
        FunctionRequestHandler handler = new FunctionRequestHandler();
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRawPath("/webhooks/stripe");
        event.setBody("{}");

        APIGatewayV2HTTPResponse response = handler.execute(event);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"received\":true");
    }
}
