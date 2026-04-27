# stripe-webhook

AWS Lambda function that receives Stripe webhook events, verifies signatures, and persists payment status updates.

Runs behind API Gateway v2 (HTTP API), payload format 2.0. Container image deployed to ECR.

## Status

Bootstrap skeleton — handler returns HTTP 200 for any input. Signature verification and persistence logic come later.

## Build

```bash
./gradlew shadowJar      # fat JAR
./gradlew build          # full build with tests
./gradlew test
```

## Native image and Docker

```bash
./gradlew optimizedDockerBuildNative   # native image build
./gradlew optimizedDockerPushNative    # push to ECR (requires Docker logged into target registry)
```

Native compilation requires GraalVM 21+ and at least 4 GB of RAM.

## CI

GitHub Actions (`.github/workflows/build.yml`) builds and pushes to ECR via OIDC. Set repo secret `AWS_IAM_ROLE_ARN_CI` to an IAM role that trusts the GitHub OIDC issuer (`token.actions.githubusercontent.com`).

## Handler

`com.functorful.stripewebhook.FunctionRequestHandler` — entry point for API Gateway v2 HTTP events.
