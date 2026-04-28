# stripe-webhook

AWS Lambda that receives Stripe webhook events, verifies signatures, and persists payment status updates. Runs behind API Gateway v2 (HTTP API), payload format 2.0. Built with Micronaut 4.9 and compiled to a GraalVM native image for fast Lambda cold starts.

## Status

Bootstrap stub — handler returns HTTP 200 for any input. Real signature-verification and persistence logic to be added in subsequent releases.

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

`com.functorful.stripewebhook.FunctionRequestHandler` — entry point for API Gateway v2 HTTP events.
