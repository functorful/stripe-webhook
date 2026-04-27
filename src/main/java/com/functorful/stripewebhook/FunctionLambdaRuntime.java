package com.functorful.stripewebhook;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.function.aws.runtime.AbstractMicronautLambdaRuntime;

import java.net.MalformedURLException;

public class FunctionLambdaRuntime extends AbstractMicronautLambdaRuntime<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse, APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    public static void main(String[] args) {
        try {
            new FunctionLambdaRuntime().run(args);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    @Override
    @Nullable
    protected RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> createRequestHandler(String... args) {
        return new FunctionRequestHandler();
    }
}
