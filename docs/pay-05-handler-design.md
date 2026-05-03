# PAY-05 — Stripe Webhook Event Handler Design

**Status:** accepted (Phase-1 scope) — **Tomás's PAY-05 design review folded in 2026-05-03 (M1–M4 + R1–R4)**
**Reviewers:** Rui (Tech Lead, primary), Tomás (Security Champion, APPROVE WITH AMENDMENTS — see §10)
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
2. Extract `lastPaymentError.code` (Stripe's category — `card_declined`,
   `insufficient_funds`, etc.) and `lastPaymentError.message`
   (vendor-supplied free text) from `event.data.object` (both may be
   null — Stripe doesn't always populate them on async-failure types).
3. **Sanitize the error message** (Tomás §10 M1):
   - Length-cap to **≤500 chars** before persisting.
   - Strip control characters (`[\x00-\x1F\x7F]`) on write.
   - Document on the in-app surface (PAY-09 / Flutter) that this field
     **must not be rendered as HTML** — only as plain text.
   - **Log only the `errorCode` at INFO**, NOT the raw message. Same
     posture as PAY-23's `PiiLogFilter` STRICT default. The full
     sanitized message is persisted to DDB but stays out of CloudWatch.
4. Query `InvestmentPayment` by `stripePaymentIntentId` GSI.
5. **Guard:** if `InvestmentPayment.status != "processed"`, log + return.
6. Read the linked `InvestmentReservation`.
7. **Guard:** if `InvestmentReservation.status != "confirmed"`, log +
   return.
8. **Transactional write set:**
   1. `UpdateItem InvestmentPayment` → `status = "failed"`,
      `errorMessage = <sanitized + capped lastPaymentError.message>`,
      with condition `status = "processed"`.
   2. `UpdateItem InvestmentReservation` — branch:
      - if `expiresAt > now()`: `status = "pending"` (allow retry).
        Condition `status = "confirmed"`.
      - else: `status = "expired"`. Condition `status = "confirmed"`.
   3. `PutItem AuditLog` — see §5.
9. **No email.** Investor sees the failure in-app via the existing
   PaymentBloc subscription path (PAY-09's responsibility — the DDB
   stream → AppSync bridge propagates the status update).
10. Update `WebhookEvent.processed = true`.

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
| details    | see below — includes `receivedAt` (Tomás §10 M2) and `lambdaVersion`/`gitSha` (Tomás §10 R3) | see below |

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
  "lambdaVersion": "v0.0.5",
  "gitSha": "d01f7c9",
  "eventId": "evt_...",
  "stripePaymentIntentId": "pi_...",
  "paymentRowId": "...",
  "paymentRowVersion": 1,
  "receivedAt": "2026-05-03T10:00:42Z",
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
`lastPaymentErrorCode: "..."` (Stripe's categorised code, may be
`null`) and `lastPaymentError: "..."` (sanitized + capped to 500 chars,
may be `null`); the transition reads `processed -> failed` /
`confirmed -> pending` (or `expired`).

**Why `receivedAt` AND `timestamp`** (Tomás §10 M2):
`AuditLog.timestamp` carries `event.created` (Stripe-attestable wall
clock); `details.receivedAt` carries the Lambda's wall clock at the
moment dispatch reached the handler. A meaningful gap between the two
(>5 min in normal operation) is itself an alarm signal — points at
either Stripe-side delivery latency or our queueing / retry hops, both
of which are forensically interesting.

**Why `lambdaVersion` + `gitSha`** (Tomás §10 R3): forensic
correlation. The Lambda image tag rolls forward over time; without
the version pinned in the audit row, "which build of the handler
wrote this row" is only answerable via CloudWatch log retention
windows. Sourced from the existing `DD_VERSION` env var (image tag)
and a build-time embedded `git-sha.properties` resource (added under
the standard `processResources` task).

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

**CloudWatch alarm** (Tomás §10 M3): make the dashboard query an
**alarm**, not just a panel. A partial-write that goes unfixed leaves
a payment in indeterminate state — silent data corruption is the
worst-class outcome here. Provision an alarm that fires when the
count of `WebhookEvent` rows with `processed = false` and
`receivedAt < now() - 5m` is greater than zero for more than 5
minutes. Routes to the same Slack chatbot as the webhook DLQ alarm
(via `aws_sns_topic.webhook_dlq_alarms` — already wired by PAY-03 /
`infrastructure!12`). Ops paging target: <10 min.

Ticket-level note: the alarm itself ships in Phase 2b's infrastructure
MR (alongside the IAM + env-var expansion); same blast radius, same
review pair.

**Catch specificity** (Tomás §10 R4): the handler's catch around
`TransactWriteItems` MUST be `ConditionalCheckFailedException`-specific
(or, for the transactional API, `TransactionCanceledException`
followed by inspection of the per-item cancellation reasons). A
generic `catch (Exception e)` would swallow real bugs (transient DDB,
IAM regressions, throttling) into the same code path as benign guard
violations and silently mark them as guard-skip cases. The
distinction matters: guard violations log+skip+200 (no human action);
real bugs must propagate to the orchestrator's outer catch which
logs at ERROR with the exception class + stack and leaves
`WebhookEvent.processed=false` for the M3 alarm to fire on.

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
| SES from-domain identity        | `ses:SendEmail` (scoped to identity ARN AND condition `ses:FromAddress = "noreply@<env-domain>"` per Tomás §10 R2) |

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

**Wildcard-ARN scoping note** (Tomás §10 R1): the resource ARN patterns
above use `*` because Amplify generates per-environment table-name
suffixes (e.g. `WebhookEvent-s5av65nkjnejtbhhvag56io4jm-NONE`). Each
environment is a separate AWS account today, so `*<TableName>*` only
matches that env's table. **If we ever consolidate accounts** (single
account hosting multiple environments), this scoping becomes too
permissive and must be tightened to the SSM-bridged exact ARN. Tracked
as a TODO comment in the IAM policy file alongside the resource block.

**SES `Condition: ses:FromAddress`** (Tomás §10 R2): the `ses:SendEmail`
grant carries an explicit `Condition` block constraining the from-address
to `noreply@<env-domain>`. Belt-and-braces against a bug that would
otherwise let the Lambda send from any address under the verified
domain (e.g. `support@`, `compliance@`). The grant covers
`ses:SendEmail` only (not `ses:SendRawEmail`); PAY-21's email-template
work — including any attachment-bearing emails — owns its own grant
expansion. Bounce/complaint configuration is also PAY-21's concern, not
this handler's.

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
| 16 | `event.data.object.id` missing/null → log + return, no write (Tomás §10 M4)            | unit |
| 17 | `event.created` missing/zero → log warn + fallback to `Instant.now()` for AuditLog timestamp (Tomás §10 M4) | unit |

Phase 1 hits 14 unit + 3 smoke = 17 cases. PAY-05 acceptance criteria
require ≥ 6 negative paths; cases 4–11, 16, 17 cover 10.

## 9. Tomás's design review (2026-05-03) — folded in

Verdict: **APPROVE WITH AMENDMENTS** for the Phase-2 (handler-bodies)
MR. None of the items below blocked the scaffold MR's merge — the
scaffold ships pure routing infrastructure (dispatcher refactor +
EventHandler interface + skeleton handlers) with no IAM, no DDB
writes, no behaviour change vs PAY-02. Tomás's APPROVE on the
scaffold is unconditional. The amendments below shape the Phase-2 MR
that follows.

### 9.1 Mandatory (blocking the Phase-2 MR)

| # | Title | Folded into |
|---|-------|-------------|
| **M1** | `lastPaymentError.message` is raw Stripe-supplied data — cap, sanitize, log only Stripe's `errorCode` not the raw message | §4.2 step 3 + persist contract |
| **M2** | Add `receivedAt` (Lambda wall-clock) to `AuditLog.details`; keep `timestamp` = `event.created` (Stripe-attestable) | §5 details JSON shape + rationale |
| **M3** | CloudWatch alarm on `WebhookEvent.processed = false` count > 0 for >5 min — make the dashboard query an alarm, not just a panel | §6 + Phase-2b infrastructure MR |
| **M4** | Add tests 16 + 17 — defensive event-shape (missing `event.data.object.id`; missing `event.created`) | §8 test matrix |

### 9.2 Recommended (non-blocking)

| # | Title | Folded into |
|---|-------|-------------|
| **R1** | Document IAM wildcard-ARN scoping assumption (per-env separate AWS accounts make `*<TableName>*` safe TODAY) — TODO comment for "consolidate accounts" change | §7 wildcard-ARN scoping note |
| **R2** | SES `Resource = arn:aws:ses:eu-west-1:<acct>:identity/<from-domain>` PLUS `Condition: ses:FromAddress = "noreply@<env-domain>"`; grant covers `SendEmail` only (PAY-21 owns `SendRawEmail`/bounce/complaint) | §7 SES `Condition: ses:FromAddress` note |
| **R3** | Bake `lambdaVersion` (image tag) and `gitSha` into `AuditLog.details` for forensic correlation | §5 details JSON shape (`lambdaVersion`, `gitSha`) |
| **R4** | Phase-2 MR catch must be `ConditionalCheckFailedException`-specific (NOT `catch (Exception e)`) — distinct paths for guard violations vs real bugs | §6 catch specificity note |

### 9.3 Cross-track interactions Tomás flagged

- **PAY-23 PII CMK constraint:** PAY-05 must NOT touch `InvestorIban`
  or the PII CMK. Phase 2 doesn't need IBAN reads (distribution flows
  are Phase 2 / EPIC-PAYOUT). If Phase 2 ever surfaces a need, route
  the diff through Tomás first.
- **PAY-09 BLoC enum:** Rui-flutter's PaymentBloc has an exhaustive
  switch on `ReservationStatus`. Phase 2 doesn't add new enum values
  (it writes the existing `executed` / `failed` / `expired` /
  `success` states the schema already declares), so no SendMessage to
  Rui-flutter needed. Pin this assumption with a unit test that
  asserts the handler writes only the documented status values.
- **PAY-22 (when spawned):** has its own 9-item §12.4 design checklist
  that Tomás is gating; orthogonal to PAY-05.

## 10. Out-of-scope for PAY-05

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
