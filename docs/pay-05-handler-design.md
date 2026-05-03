# PAY-05 — Stripe Webhook Event Handler Design

**Status:** accepted (Phase-1 scope)
**Reviewers:** Rui (Tech Lead, primary), Tomás (Security Champion, mandatory)
**Refs:**
- Notion PAY-05: <https://www.notion.so/34fd48539664810c9036d028db1b5e39>
- Application repo `amplify/data/resource.ts` (status enums + transition table block)
- Application repo `application/plans/epic-pay/2026-04-27-team-review.md` (sub-ticket PAY-05)

This document is the contract for the Phase-1 handlers
(`payment_intent.succeeded` and `payment_intent.payment_failed`). PAY-02
shipped the signature-verify → dedup → dispatch plumbing; PAY-05 adds the
business logic at the dispatch tip. The handler architecture is set up by
this MR's dispatcher refactor; the real handler bodies land in follow-up
MRs reviewed against this contract.

## 1. Phase-1 dispatch table

| Stripe event type                   | Handler bean                    | Phase-1 behaviour                                                                 |
|-------------------------------------|---------------------------------|-----------------------------------------------------------------------------------|
| `payment_intent.succeeded`          | `PaymentIntentSucceededHandler` | full happy-path: 4 DDB writes + AuditLog + SES email                              |
| `payment_intent.payment_failed`     | `PaymentIntentFailedHandler`    | mark payment failed + reservation back to `pending` (or `expired`) + AuditLog     |
| any other `payment_intent.*`        | `IgnoredEventHandler`           | log + return (no state change)                                                    |
| any other `*.*`                     | `IgnoredEventHandler`           | log + return (no state change)                                                    |
| `null` event type (malformed body)  | `IgnoredEventHandler`           | log + return (the body already passed HMAC + JSON parse, so this is rare)         |

Phase-2 / Phase-3 events (refunds, disputes, payout webhooks) are
out-of-scope for PAY-05 and will route to the same `IgnoredEventHandler`
until their owning epic adds a dedicated handler.

## 2. Idempotency boundary

PAY-02's `WebhookIdempotencyStore.recordFirstDelivery` writes the
`WebhookEvent` row **before** dispatch. The handler runs at most once per
distinct Stripe `event.id`. Replays short-circuit at the dispatcher entry
point (return `RecordResult.REPLAY` → 200 without invoking any handler).

Implication: **handlers do not need to be idempotent against repeated
calls with the same eventId.** They DO need to be idempotent against the
business state they touch (e.g. partial-write recovery on the next replay
after a handler crash mid-write — see §6 below).

## 3. Status transition guards

Authoritative source: the comment block at the top of
`application/amplify/data/resource.ts`. Reproduced here for handler
implementation:

### 3.1 `InvestmentReservation.status`

| From      | Allowed → To         | Driver                            |
|-----------|----------------------|-----------------------------------|
| pending   | confirmed            | `PaymentLambda` creates intent    |
| pending   | expired              | timeout sweep (out of scope here) |
| confirmed | executed             | **PAY-05 succeeded handler**      |
| confirmed | failed               | **PAY-05 failed handler**         |
| confirmed | expired              | per-method window sweep           |
| failed    | confirmed            | retry — new payment_intent        |

Disallowed transitions the PAY-05 handlers MUST reject (= log + skip
business write, do NOT throw — the WebhookEvent row is already
recorded and the handler returns 200 to keep Stripe happy):

- `executed → *` (terminal)
- `expired → *` (terminal)
- `failed → executed` (must go failed → confirmed first via retry)
- `pending → executed` (must pass through confirmed)

### 3.2 `InvestmentPayment.status`

| From      | Allowed → To         | Driver                            |
|-----------|----------------------|-----------------------------------|
| processed | success              | **PAY-05 succeeded handler**      |
| processed | failed               | **PAY-05 failed handler**         |
| processed | error                | handler exception (catch in code) |

Disallowed transitions the handlers MUST reject:

- `success → *` (terminal)
- `failed → *` (the retry-creates-new-row contract — never mutate the old row)
- `error → *` (terminal; ops attention required)

### 3.3 Guard implementation

DynamoDB `UpdateItem` with `condition_expression = "status = :expected"`.
Conditional check failure on a guard violation → log structured warning
with `eventId`, `current`, `attempted` → return 200. The webhook row
stays as `processed=false` so an ops sweep can re-drive it manually if
the guard violation was a real data anomaly (vs. a benign late replay).

## 4. Per-handler contracts

### 4.1 `PaymentIntentSucceededHandler`

Triggered by Stripe `payment_intent.succeeded`. Sequence:

1. Extract `paymentIntentId` from `event.data.object.id`.
2. Query `InvestmentPayment` by GSI on `stripePaymentIntentId` (the GSI
   exists implicitly via Amplify's owner index; see §7 for the index
   shape we'll need to add if Amplify hasn't auto-generated one).
3. **Guard:** if `InvestmentPayment.status != "processed"`, log + return.
4. Read the linked `InvestmentReservation` via the composite FK
   (`userId`, `investmentId`, `investmentVersion`, `reservationRequestedAt`,
   `reservationVersion`).
5. **Guard:** if `InvestmentReservation.status != "confirmed"`, log +
   return.
6. **Transactional write set** (single `TransactWriteItems` call —
   atomic):
   1. `UpdateItem InvestmentPayment` → `status = "success"`,
      `paidAt = event.created` (epoch second from Stripe), with
      condition `status = "processed"`.
   2. `UpdateItem InvestmentReservation` → `status = "executed"`,
      `confirmedAt = now()`, with condition `status = "confirmed"`.
   3. `PutItem UserInvestment` — composite key
      `(userId, investmentId, investmentVersion)`. **Idempotent put:**
      condition `attribute_not_exists(userId)` — if the row already
      exists (unlikely but possible if a prior handler partially-wrote
      then crashed), let DDB reject and the surrounding transaction
      fails atomically. The next webhook replay, after manual
      reconciliation, can be re-processed.
   4. `PutItem AuditLog` — see §5.
7. SES `SendEmail` to the investor's verified email — plain-text
   confirmation referencing the participation count, property name, and
   reservation reference. PAY-21 owns the localised template; this
   handler ships an English placeholder. SES failure is logged but does
   NOT roll back the transaction (the payment IS confirmed; the email
   is best-effort). Email-sent telemetry goes to Datadog as a separate
   metric so PAY-21 has a baseline.
8. Update the `WebhookEvent` row → `processed = true`. (Best-effort; a
   failure here is logged but doesn't trigger a retry — Stripe re-deliveries
   would short-circuit at the idempotency store, so the `processed` flag
   is purely an operator-readable signal that the row reached
   completion.)

### 4.2 `PaymentIntentFailedHandler`

Triggered by Stripe `payment_intent.payment_failed`. Sequence:

1. Extract `paymentIntentId` from `event.data.object.id`.
2. Extract `lastPaymentError.message` from `event.data.object` (may be
   null — Stripe doesn't always populate it on async-failure types).
3. Query `InvestmentPayment` by `stripePaymentIntentId` GSI.
4. **Guard:** if `InvestmentPayment.status != "processed"`, log + return.
5. Read the linked `InvestmentReservation`.
6. **Guard:** if `InvestmentReservation.status != "confirmed"`, log +
   return.
7. **Transactional write set:**
   1. `UpdateItem InvestmentPayment` → `status = "failed"`,
      `errorMessage = lastPaymentError.message`, with condition
      `status = "processed"`.
   2. `UpdateItem InvestmentReservation` — branch:
      - if `expiresAt > now()`: `status = "pending"` (allow retry).
        Condition `status = "confirmed"`.
      - else: `status = "expired"`. Condition `status = "confirmed"`.
   3. `PutItem AuditLog` — see §5.
8. **No email.** Investor sees the failure in-app via the existing
   PaymentBloc subscription path (PAY-09's responsibility — the DDB
   stream → AppSync bridge propagates the status update).
9. Update `WebhookEvent.processed = true`.

### 4.3 `IgnoredEventHandler`

Triggered by any other event type (or `null`). Logs `event.id` and
`event.type` at INFO and returns. Same as PAY-02 behaviour — explicitly
named so the dispatcher's routing table is exhaustive.

## 5. AuditLog payload shape

`AuditLog` schema (from `amplify/data/resource.ts`):

```
userId        string  required (index PK)
timestamp     datetime required (index SK)
eventType     enum    AuditEventType
resource      string  required
action        string  required
details       json    optional
```

PAY-05 writes one `AuditLog` row per dispatched success/failure event.
Field shapes:

| Field      | succeeded handler                             | failed handler                                    |
|------------|-----------------------------------------------|---------------------------------------------------|
| userId     | from `InvestmentPayment.userId`               | from `InvestmentPayment.userId`                   |
| timestamp  | `event.created` (Stripe's authoritative time) | `event.created`                                   |
| eventType  | `DATA_ACCESS`*                                | `DATA_ACCESS`*                                    |
| resource   | `InvestmentReservation:<reservationKey>`      | same                                              |
| action     | `payment.succeeded`                           | `payment.failed`                                  |
| details    | see below                                     | see below                                         |

\* `AuditEventType` enum currently lacks payment-specific values (the
schema was authored for the GDPR feature). Two options:

- **(A)** Reuse `DATA_ACCESS` with `action` carrying the semantic
  distinction. Zero schema change.
- **(B)** Add `PAYMENT_SUCCEEDED` / `PAYMENT_FAILED` to the
  `AuditEventType` enum.

**Decision: (A) for Phase 1.** Zero schema migration cost; the `action`
field is the searchable signal. (B) is a follow-up backlog item if the
audit-log search UX needs typed filtering.

`details` JSON shape (succeeded):

```json
{
  "provider": "stripe",
  "actor": "stripe-webhook-lambda",
  "eventId": "evt_...",
  "stripePaymentIntentId": "pi_...",
  "paymentRowId": "...",
  "paymentRowVersion": 1,
  "reservationKey": {
    "userId": "...",
    "investmentId": "...",
    "investmentVersion": 1,
    "requestedAt": "...",
    "version": 1
  },
  "amountCents": 50000,
  "currency": "EUR",
  "transition": {
    "reservation": "confirmed -> executed",
    "payment": "processed -> success"
  }
}
```

`details` JSON shape (failed) is the same plus
`lastPaymentError: "..."` (may be `null`) and the transition reads
`processed -> failed` / `confirmed -> pending` (or `expired`).

## 6. Partial-write recovery

If the `TransactWriteItems` call fails mid-flight, DDB reverts. If it
succeeds and the SES call fails, the payment IS confirmed in DDB and the
email is best-effort. If the Lambda crashes between the TWI call and the
`processed = true` update on `WebhookEvent`, the next Stripe delivery
short-circuits at the idempotency store (the eventId is recorded with
`processed = false`). This is an **expected** state for the row sweeper:
ops dashboard query `WebhookEvent where processed = false and
receivedAt < now() - 5m` surfaces it. Manual replay is via the AWS
console or a future ops Lambda.

## 7. IAM scope expansion (infrastructure repo)

Current `aws-stripe-webhook-iam.tofu` grants the Lambda DDB Get/Put/
Update/Query on `*InvestmentPayment*` and `*WebhookEvent*` only. PAY-05
adds:

| Resource                        | Actions needed                                  |
|---------------------------------|-------------------------------------------------|
| `*InvestmentReservation*`       | `dynamodb:GetItem`, `dynamodb:UpdateItem`       |
| `*InvestmentReservation*/index` | `dynamodb:Query`                                |
| `*UserInvestment*`              | `dynamodb:GetItem`, `dynamodb:PutItem`          |
| `*AuditLog*`                    | `dynamodb:PutItem`                              |
| SES (any verified identity)     | `ses:SendEmail` (scoped to from-address ARN)    |

Plus new env vars for table names (resolved via the SSM bridge from
Amplify, same pattern as `WEBHOOK_EVENTS_TABLE_NAME` from PAY-02):

- `INVESTMENT_RESERVATION_TABLE_NAME`
- `INVESTMENT_PAYMENT_TABLE_NAME`
- `USER_INVESTMENT_TABLE_NAME`
- `AUDIT_LOG_TABLE_NAME`
- `SES_FROM_ADDRESS` (e.g. `noreply@sobrado.casa` in dev,
  `noreply@sobrado.pt` in prd — domain identity is already SES-verified
  per the existing `aws-ses.tofu`)

The `transactWriteItems` action does not require its own IAM action —
DDB authorises each item-level action against the resource (i.e. all
four resource ARNs above must be allowed for their respective actions).

## 8. Test coverage matrix

| # | Scenario                                                                 | Type    |
|---|--------------------------------------------------------------------------|---------|
| 1 | Happy path — succeeded                                                   | unit    |
| 2 | Happy path — failed (reservation back to pending)                        | unit    |
| 3 | Happy path — failed (reservation expired because past `expiresAt`)       | unit    |
| 4 | Guard violation — payment already success → no write                     | unit    |
| 5 | Guard violation — payment already failed → no write                      | unit    |
| 6 | Guard violation — reservation already executed → no write                | unit    |
| 7 | Guard violation — reservation already expired → no write                 | unit    |
| 8 | Missing `InvestmentPayment` (orphan webhook) → log + 200, no write       | unit    |
| 9 | Missing `InvestmentReservation` (data anomaly) → log + 200, no write     | unit    |
| 10 | UserInvestment already exists → transaction fails, log, 200 (replay path) | unit    |
| 11 | SES SendEmail fails → DDB writes succeeded, log warning, 200             | unit    |
| 12 | Replayed event id → idempotency store short-circuits (existing test)    | unit    |
| 13 | `stripe trigger payment_intent.succeeded` against dev sandbox            | smoke   |
| 14 | `stripe trigger payment_intent.payment_failed` against dev sandbox       | smoke   |
| 15 | Replay smoke (run #13 twice) — assert single UserInvestment row created  | smoke   |

Phase 1 hits 12 unit + 3 smoke = 15 cases. PAY-05 acceptance criteria
require ≥ 6 negative paths; cases 4–11 cover 8.

## 9. Out-of-scope for PAY-05

- Localised email template / pt-PT copy → **PAY-21**.
- Real-time Flutter `PaymentBloc` subscription updates → **PAY-09**
  (independent track; uses the existing DDB stream → AppSync bridge).
- DDB stream DLQ on `InvestmentReservation` / `InvestmentPayment` table
  streams → **SEC-05** (not yet started; tracked in backlog).
- Per-investment > €2,500 pre-approval gate → **PAY-24**.
- Reconciliation Lambda for daily Stripe-vs-DDB diff → **PAY-07**.
- S3 audit-log bucket with Object Lock → **PAY-08** (separate audit
  artefact for raw webhook bodies; this AuditLog row is the in-DDB
  business-event audit trail).
