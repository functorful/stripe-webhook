# stripe-webhook

AWS Lambda that receives Stripe webhook events, verifies signatures, and persists payment status updates. Runs behind API Gateway v2 (HTTP API), payload format 2.0. Built with Micronaut 4.9 and compiled to a GraalVM native image for fast Lambda cold starts.

## Status

PAY-02 skeleton — verify → dedup → dispatch plumbing is complete; per-event-type handlers (`payment_intent.succeeded` / `.payment_failed`) are no-ops pending PAY-05. The Lambda already:

- Fetches its webhook signing secret from AWS Secrets Manager at cold start (one-shot, cached for the Lambda lifetime).
- Aborts cold start with a structured error log (ARN only, no secret value) if the fetched value is the `PENDING_PAY-00a` sentinel — Tomás's standing veto trigger for PAY-02 / PAY-04.
- Verifies the `Stripe-Signature` HMAC-SHA256 with a 5-minute timestamp tolerance, accepts multi-`v1` rotation headers, constant-time compare.
- Conditionally `PutItem`s on `WebhookEvent.eventId` for at-least-once-delivery dedup; replays return 200 fast without re-processing.
- Returns generic `{"error":"invalid request"}` 400s on every signature / body failure (no detail leakage).

## Container images

Published to GitHub Container Registry on every version tag, in two flavors:

| Flavor | Tag pattern | Contents |
|---|---|---|
| **Vanilla** | `ghcr.io/functorful/stripe-webhook:vX.Y.Z` | Plain Micronaut native image. No Datadog agent, no Datadog Lambda extension layer, no `DD_*` env vars. Suitable for any consumer. |
| **Datadog** | `ghcr.io/functorful/stripe-webhook:vX.Y.Z-dd` | Vanilla + the `dd-java-agent.jar` baked in, the `public.ecr.aws/datadog/lambda-extension` layer mounted at `/opt`, and `DD_*` env vars. |

## Versioning

Tags are created automatically on every push to `main` using [github-tag-action](https://github.com/anothrNick/github-tag-action). Default bump is **patch**.

### Controlling the version bump

Include one of these tokens in your commit message to control the bump:

| Token | Example | Result |
|-------|---------|--------|
| `#patch` | `fix: handle null body #patch` | `v0.1.0` → `v0.1.1` |
| `#minor` | `feat: add html parsing #minor` | `v0.1.0` → `v0.2.0` |
| `#major` | `refactor!: new payload schema #major` | `v0.1.0` → `v1.0.0` |
| `#none` | `docs: update readme #none` | no tag created |

If no token is present, the default `#patch` bump is applied. When multiple tokens are present, the highest-ranking one wins: `#major` > `#minor` > `#patch` > `#none`.

## Development

```bash
./gradlew build                                # compile + test (vanilla classpath)
./gradlew test                                 # tests only
./gradlew optimizedDockerBuildNative           # build vanilla native Docker image locally
./gradlew optimizedDockerBuildNativeDatadog    # build Datadog native Docker image locally
```

Requires Java 21 and GraalVM. Native compilation needs ~4 GB of RAM.

The Gradle build derives the project version from the latest git tag (`git describe --tags --abbrev=0`, with leading `v` stripped). With no tags it falls back to `0.0.0-SNAPSHOT`. Image tag derivation:

- Vanilla task → `ghcr.io/functorful/stripe-webhook:dev-<short-sha>`
- Datadog task → `ghcr.io/functorful/stripe-webhook:dev-<short-sha>-dd`

Override with `DOCKER_IMAGE_TAG=…` (the `-dd` suffix is appended automatically when a Datadog task is invoked) or `DOCKER_IMAGE_REPO=…`.

## Handler

`com.functorful.stripewebhook.FunctionRequestHandler` — entry point for API Gateway v2 HTTP events. Thin wrapper that delegates to `WebhookEventProcessor`, which contains all orchestration logic and is unit-tested directly without a Micronaut application context.

## Required Lambda environment variables

| Variable | Source | Purpose |
|---|---|---|
| `STRIPE_WEBHOOK_SIGNING_SECRET_ARN` | OpenTofu (`aws-stripe-webhook-lambda.tofu`) | Secrets Manager ARN to fetch the webhook signing secret from at cold start. |
| `WEBHOOK_EVENTS_TABLE_NAME` | OpenTofu (Amplify SSM bridge) | Physical name of the Amplify-managed `WebhookEvent` DynamoDB table used for idempotency dedup. |
| `AWS_REGION` | Lambda runtime | Standard. |

Both are provisioned by the OpenTofu IaC in `gitlab.com/functorful/projects/sobrado/infrastructure`. See `runbooks/stripe-test-secrets-bootstrap.md` for dev setup.

## IAM (least privilege)

The Lambda role grants only:

- `secretsmanager:GetSecretValue` on the webhook signing secret ARN (and Datadog credentials secret).
- `kms:Decrypt` / `DescribeKey` on the external-trust CMK (where the signing secret is encrypted).
- `kms:Decrypt` / `Encrypt` / `GenerateDataKey` on the application CMK (DynamoDB SSE).
- `dynamodb:GetItem` / `PutItem` / `UpdateItem` / `Query` on `*WebhookEvent*` and `*InvestmentPayment*` table ARNs.
- `ecr:*` for image pull from the GHCR pull-through cache.

The Lambda has **no** access to the Stripe API key — that secret is read by `payment-lambda` (PAY-04) under a separate IAM role per Tomás's spec.
