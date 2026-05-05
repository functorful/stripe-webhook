# ADR-0008 — Consolidate JSON serialization on Micronaut Serde (remove direct Jackson use)

| | |
|--|--|
| **Status** | Proposed |
| **Date** | 2026-05-05 |
| **Decider(s)** | Rui (Tech Lead), Tomás (Security Champion), Miguel (Coordinator) |
| **Owner ticket** | ARCH-08 |
| **Scope** | `backend/stripe-webhook` (this ADR); `backend/revolut-webhook` follows in a sibling MR. |
| **Replaces** | n/a |

## Context

`stripe-webhook` currently uses **two** JSON layers:

1. **Micronaut Serde (`io.micronaut.serde:micronaut-serde-jackson`)** — the project-wide
   serialization framework. Used in `AuditLogStore` (PAY-05 PR #6) for the audit-details
   `Map<String, Object>` write, and implicitly by Micronaut for typed Lambda I/O.
2. **Direct Jackson Databind (`com.fasterxml.jackson.core:jackson-databind`)** — used to
   parse the raw HTTPS POST body of the Stripe webhook into `JsonNode` and traverse it
   via `path(...).get(...)`. The `JsonNode` floats through `WebhookEventProcessor` →
   `StripeWebhookEvent.dataObject` → handler classes (`PaymentIntentSucceededHandler`,
   `PaymentIntentFailedHandler`) and `ReservationKey.fromStripeMetadata`.

This is two trust boundaries' worth of JSON parsing in a Lambda that handles untrusted
input from Stripe's public webhook endpoint.

### Inventory (production code, `src/main/`)

| File | Jackson types used | Trust boundary |
|------|-------------------|----------------|
| `WebhookEventProcessor.java` | `ObjectMapper`, `JsonNode`, `MissingNode` | **Yes** — parses raw HTTP body |
| `event/StripeWebhookEvent.java` | `JsonNode dataObject` | Internal DTO holding parsed payload |
| `reservation/ReservationKey.java` | `JsonNode metadata` | Reads typed strings/longs from metadata |
| `dispatch/handlers/PaymentIntentSucceededHandler.java` | `JsonNode` | Reads from `StripeWebhookEvent.dataObject` |
| `dispatch/handlers/PaymentIntentFailedHandler.java` | `JsonNode` | Reads from `StripeWebhookEvent.dataObject` |

Plus 4 test files (acceptable — tests can stay on Jackson if they want; we only constrain
production deserialization).

### Why this is a problem

#### 1. Deserialization-gadget surface (Tomás-flagged)

Jackson's polymorphic-deserialization features are the root of a long history of CVEs:
`@JsonTypeInfo(use = Id.CLASS)`, `enableDefaultTyping`, polymorphic `Object` returns from
`@JsonAnyGetter`. The CVE pattern: a malicious payload writes
`{"@class":"some.gadget.Class", ...}` and on deserialization, Jackson reflectively
constructs that class via its no-arg constructor + setters, executing arbitrary code paths
(JNDI lookups, file IO, deserialization-of-deserialization chains).

We do **not** use polymorphic deserialization in our code today. But a direct
`ObjectMapper` dependency leaves the API reachable: any future contributor could enable
default typing or annotate a record with `@JsonTypeInfo`, and CI would not catch it. The
deserialization-gadget surface is a "default-deny" property best enforced by **not having
the API in the dependency tree**.

**Micronaut Serde rejects polymorphic deserialization at the type level.** Its
`@Serdeable` processor generates static, build-time deserializers per record. There is no
runtime reflection-driven polymorphism, no `@class` token resolution, no
`enableDefaultTyping` switch — the machinery to express the CVE-class doesn't exist in
the API surface. Removing direct Jackson from production code is the structural fix.

#### 2. Untyped `JsonNode` flows beyond the trust boundary

`StripeWebhookEvent.dataObject` is a `JsonNode`. The handlers do
`event.dataObject().path("amount").asLong()` and `event.dataObject().path("metadata")` —
the typed shape is implicit, scattered across multiple files, and any field-rename or
shape-change at Stripe's end won't surface as a compile error. It surfaces at runtime
as `null` returns, `MissingNode` traversals, and `NumberFormatException`s.

A typed sealed `StripeEvent` hierarchy makes the contract explicit:

```java
public sealed interface StripeEvent
        permits StripeEvent.PaymentIntentSucceeded,
                StripeEvent.PaymentIntentFailed,
                StripeEvent.Ignored {

    String eventId();
    Instant occurredAt();

    @Serdeable
    record PaymentIntentSucceeded(
            String eventId,
            Instant occurredAt,
            PaymentIntentPayload payload
    ) implements StripeEvent { }

    // …
}

@Serdeable
public record PaymentIntentPayload(
        String id,
        long amount,
        String currency,
        @Nullable Map<String, String> metadata
) { }
```

With `@Serdeable`, every field is typed. A field-rename at Stripe is a parse error at the
Lambda boundary — the dispatcher never sees malformed input.

#### 3. Library boundary risk

Stripe's first-party `stripe-java` SDK uses **Gson**, not Jackson. We currently don't
depend on it (we manually verify signatures + parse the body), but if we ever do, having
Jackson too means three JSON libraries in one Lambda. Locking on Micronaut Serde now
prevents that drift. Gson can stay isolated to the Stripe SDK boundary if we add it later;
Micronaut Serde is the canonical layer for everything we own.

## Decision

**`stripe-webhook` shall use Micronaut Serde for all JSON serialization and
deserialization in production code. Direct `com.fasterxml.jackson.core:jackson-databind`
imports in `src/main/` are forbidden.**

The `io.micronaut.serde:micronaut-serde-jackson` dependency stays — that's the Micronaut
Serde implementation backed by Jackson **internally** (Serde uses Jackson's streaming
parser under the hood, but exposes only the Serde API). The constraint is on the
**API surface in source code**, not on the transitive dependency graph.

### Concrete changes (this MR)

This MR ships the **decision document only**. Code migration ships in a follow-up MR
(`feat/arch-08-stripe-webhook-typed-events`) so reviewers can react to the architecture
before ~1000 lines of refactor land. The follow-up's contents are listed below for
visibility.

1. **ADR (this file).**

### Concrete changes (follow-up MR)
2. **CI static check** in `.github/workflows/build.yml` — a new step that runs before
   `./gradlew build`, fail-fast on match. **Important: pattern is `com.fasterxml.jackson`
   without the `import` prefix** — fully-qualified-name uses
   (`new com.fasterxml.jackson.databind.ObjectMapper()`, parameter types declared inline)
   are legal Java and would bypass an `import`-anchored grep. The unanchored pattern
   matches both:
   ```yaml
   - name: Static check — no direct Jackson use in production code
     run: |
       OFFENDERS=$(find src/main -name "*.java" -exec grep -l "com\.fasterxml\.jackson" {} + || true)
       if [ -n "$OFFENDERS" ]; then
         echo "::error::Direct Jackson references found in production code (forbidden — see ADR-0008):"
         echo "$OFFENDERS"
         exit 1
       fi
       echo "✓ No direct Jackson references in production code."
   ```
   The check lives **before** the gradle build so it fails inside seconds rather than
   waiting for the full native-image compile.
3. **Typed sealed `StripeEvent` hierarchy** + `@Serdeable` records for the Stripe payload
   shapes we consume (`PaymentIntentPayload`, `PaymentIntentMetadata`).
4. **`WebhookEventProcessor`** parses the raw body via Micronaut Serde's `ObjectMapper`
   (injected) into the typed `StripeEvent` once, at the boundary. No `JsonNode` past the
   boundary.
5. **Handlers** (`PaymentIntentSucceededHandler`, `PaymentIntentFailedHandler`) consume
   the typed `PaymentIntentPayload`. No `path(...)` traversals.
6. **`ReservationKey.fromStripeMetadata`** consumes the typed metadata record. No `JsonNode`.
7. **`build.gradle`** drops `implementation("com.fasterxml.jackson.core:jackson-databind")`.
   Tests that still want Jackson can declare `testImplementation("com.fasterxml.jackson.core:jackson-databind")`.

### Boundary parsing — fail-closed contract

`WebhookEventProcessor` parses the raw body **once** and converts every parse error into
an HTTP 400 response. The dispatcher only ever sees a well-formed `StripeEvent`. Three
layered checks:

1. Body is not empty → `400 EMPTY_BODY` if so.
2. `objectMapper.readValue(body, StripeEventEnvelope.class)` succeeds → `400 MALFORMED_JSON`
   on `JsonParseException` / `MismatchedInputException` (Serde wraps both as
   `SerdeException`).
3. The envelope's `type` field maps to a registered variant → `200 OK` with `{"ignored":true}`
   for unrecognised types (Stripe sends events we don't subscribe to; ignoring them is the
   contract).

No exception escape from `WebhookEventProcessor.processEvent`. No `JsonNode` ever reaches
the dispatcher.

## Consequences

### Positive

- Direct removal of the Jackson polymorphic-deserialization API surface (default-deny).
- Compile-time type-safety on Stripe payload shape — Stripe schema drift surfaces as a
  parse error at the boundary, not a `MissingNode.path(...).asLong()` traversal at handler
  level.
- One fewer JSON library in the runtime classpath (~1.5MB native image savings vs.
  bundling jackson-databind directly + via micronaut-serde-jackson).
- CI static check makes the policy enforceable — no future contributor can re-introduce
  Jackson in production without explicitly editing the CI config.
- Pattern is replicated in `revolut-webhook` (sibling ADR after this lands).

### Negative / cost

- ~1034 lines of production code touched (5 files). Plus 4 test files migrated (test
  files can keep `JsonNode` on test-classpath — the CI check targets `src/main` only).
- The handlers' control flow shifts slightly: today they receive `JsonNode dataObject` and
  branch on `path(...).asLong()` checks; after this they receive a typed
  `PaymentIntentPayload` and branch on field equality. Equivalent logic, different shape.
- A **new** Stripe event type requires adding a sealed-interface variant + a record. This
  is a benefit (explicit contract) but also a friction point. We accept it.

### Mitigations / explicit non-goals

- **Tests stay on Jackson if convenient** — `testImplementation("com.fasterxml.jackson.core:jackson-databind")`
  in `build.gradle`. The CI check targets `src/main` only. Tests need flexible parsing
  (raw fixture loading, partial-payload assertions); imposing Serde everywhere is overkill.
- **`payment-lambda` and `data-handler` are out of scope.** They already use Micronaut
  Serde and don't have direct Jackson imports — confirmed by ADR-author inventory before
  drafting.
- **`revolut-webhook` is a sibling MR.** Same pattern, different payload shape (Revolut
  reservation events). Filed as ARCH-08-followup.
- **Stripe SDK adoption** is a future decision (out of scope here). If we ever add
  `stripe-java`, Gson is allowed inside the SDK boundary — we just don't surface it past
  our adapter classes.

## Tomás review-pair concerns (resolution)

Tomás flagged three concerns when ARCH-08 was queued:

1. **Polymorphic deserialization gadget surface** → §"Why this is a problem" #1 above.
   Resolution: Micronaut Serde's `@Serdeable` processor generates static deserializers;
   the polymorphic API doesn't exist in the surface.
2. **`JsonNode` traversal in webhook bodies** → §"Why this is a problem" #2 above.
   Resolution: typed sealed `StripeEvent` hierarchy + `@Serdeable` records.
3. **Library boundary risk** → §"Why this is a problem" #3 above.
   Resolution: explicitly out-of-scope for this ADR; if we ever add the Stripe SDK, the
   `gson` it brings is constrained inside an adapter package, not surfaced.

## Verification

- `./gradlew test` green after the migration.
- `find src/main -name "*.java" -exec grep -l "com.fasterxml.jackson" {} +` returns
  empty in CI (unanchored pattern catches FQN bypasses too — see §"Concrete changes").
- `./gradlew dependencies | grep jackson-databind` shows only the transitive path through
  `io.micronaut.serde:micronaut-serde-jackson`, never a direct
  `--- com.fasterxml.jackson.core:jackson-databind` at depth 1.

## Notes from review

- **`ReservationKey.fromStripeMetadata` dual-shape parsing** (current `requireLong`
  accepts both `JsonNode.isNumber()` and `JsonNode.isTextual()` paths) — investigated
  during ADR review. PaymentLambda's `stripeMetadata` helper writes every field as a
  `String` via `Long.toString(...)` (`backend/payment-lambda/CreatePaymentIntentProcessor.java`).
  Stripe API contract is metadata-values-are-always-strings. The defensive numeric branch
  exists purely for test ergonomics: `PaymentIntentSucceededHandlerTest` uses
  `ObjectNode.put(String, long)` which produces a JSON numeric node. **Migration PR
  drops the numeric branch in production code AND updates the test fixtures to
  `Long.toString(...)` to match the production wire shape.** Documented here for the
  contract change and for future history.

## Follow-ups

- **ARCH-08-followup-revolut** — apply the same pattern to `backend/revolut-webhook`.
- **Filed during PAY-22 sub-MR-A2 review** — `DynamoDbInvestorIbanStore.buildAuditDetails`
  in `pii-ingestion` hand-rolls JSON via `StringBuilder`. Safe today (controlled-shape
  values only) but should be migrated to Micronaut Serde under this ADR's banner once it
  generalises across the project.
