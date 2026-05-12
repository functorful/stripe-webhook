package com.functorful.stripewebhook.dynamodb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link UserInvestmentStore} — the
 * {@code payment_intent.succeeded}-driven UserInvestment-row put builder
 * (PAY-05 Phase 2b).
 *
 * <p><strong>Why store-level tests exist for this class</strong>: Tomás's
 * PAY-05 Phase 2b Option (c) drill (2026-05-12) caught a bug where the
 * {@link UserInvestmentStore#buildIdempotentPut} put-item shape did not
 * match the deployed DynamoDB table's KeySchema — the put cancelled
 * the surrounding {@code TransactWriteItems} at runtime, producing a
 * 200-to-Stripe-with-zero-writes customer-facing data integrity bug.
 *
 * <p>The structural failure was that no store-level test asserted the
 * put-item key shape against the deployed schema (the handler tests
 * only verified that the store's mock was called, not what the store
 * actually produced). This test class is the structural correction:
 * each store that constructs a DDB item must have a test that asserts
 * the item's key attributes match the deployed table's actual KeySchema.
 * See HYG-19 axis-4 + the {@code feedback_amplify_gen2_default_id_pk}
 * standing constraint (forthcoming).
 *
 * <p><strong>Deployed schema reference</strong> (verified
 * {@code aws dynamodb describe-table} on 2026-05-12):
 * <pre>
 *   KeySchema:           id (S, HASH)             ← Amplify Gen 2 default UUID-id
 *   AttributeDefinitions: id (S), userId (S), investmentId (S),
 *                         investmentVersion (N),
 *                         investmentId#investmentVersion (S)
 *   GSI gsi-Investment.userInvestments:   investmentId HASH + investmentVersion RANGE
 *   GSI UserInvestmentByUserAndInvestment: userId HASH + investmentId#investmentVersion RANGE
 * </pre>
 */
class UserInvestmentStoreTest {

    private static final String TABLE_NAME =
            "UserInvestment-2mm6zfbtrngy5jqblv7nna6b2e-NONE";
    private static final String USER_ID = "u-test-cognito-sub-abc-123";
    private static final String INVESTMENT_ID = "inv-test-uuid-xyz-789";
    private static final long INVESTMENT_VERSION = 3;
    private static final long PARTICIPATIONS = 5;
    private static final Instant NOW = Instant.parse("2026-05-12T12:34:56Z");

    private UserInvestmentStore store;

    @BeforeEach
    void setUp() {
        store = new UserInvestmentStore(TABLE_NAME);
    }

    @Test
    void putItemHasIdHashKeyAsRandomUUID() {
        Put put = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);

        // Deployed PK is `id` (S) HASH — Amplify Gen 2 default UUID identifier.
        // The store must produce an `id` attribute or the surrounding
        // TransactWriteItems will cancel with a ValidationException-class
        // failure (missing HASH key) — the exact bug Tomás's Option (c)
        // drill caught on 2026-05-12.
        AttributeValue idAttr = put.item().get("id");
        assertThat(idAttr)
                .as("put item must include `id` (the deployed HASH key)")
                .isNotNull();
        assertThat(idAttr.s())
                .as("`id` must be a non-empty UUID string")
                .isNotNull()
                .isNotEmpty();
        // Verify it's a parseable UUID — defensive against future
        // refactors swapping to a different key-generation strategy.
        assertThat(UUID.fromString(idAttr.s()))
                .as("`id` must be a syntactically valid UUID")
                .isNotNull();
    }

    @Test
    void putItemDoesNotIncludeCompositeAttributeAsBaseTableKey() {
        // Regression test for the 2026-05-12 bug class: pre-fix the store
        // put `userId` HASH + `investmentId#investmentVersion` RANGE as
        // if the base table had a composite key (analogous to
        // InvestmentReservation). It does not. The base-table KeySchema
        // is `id`-only; populating `userId` + composite as if they were
        // the keys produced a put missing the actual HASH and the TWI
        // cancelled.
        //
        // This test asserts the composite attribute IS still populated
        // (it remains needed for the UserInvestmentByUserAndInvestment
        // GSI sort key — see resource.ts:441-443) but the put-item
        // construction does not treat it as the base-table key.
        Put put = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);

        Map<String, AttributeValue> item = put.item();
        AttributeValue compositeAttr = item.get(
                UserInvestmentStore.GSI_USER_INVESTMENT_SORT_KEY_ATTRIBUTE_NAME);
        assertThat(compositeAttr)
                .as("composite GSI sort key must still be populated for "
                        + "UserInvestmentByUserAndInvestment queryability")
                .isNotNull();
        assertThat(compositeAttr.s())
                .as("composite GSI sort key must be `investmentId#investmentVersion`")
                .isEqualTo(INVESTMENT_ID + "#" + INVESTMENT_VERSION);
    }

    @Test
    void putItemIncludesAllDomainAttributesAsScalars() {
        // Scalar fields populated for the GSI key columns + Amplify-
        // managed metadata. The deployed AttributeDefinitions list
        // includes investmentId (S) and investmentVersion (N) — both
        // used by gsi-Investment.userInvestments.
        Put put = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);
        Map<String, AttributeValue> item = put.item();

        assertThat(item.get("userId").s()).isEqualTo(USER_ID);
        assertThat(item.get("investmentId").s()).isEqualTo(INVESTMENT_ID);
        assertThat(item.get("investmentVersion").n())
                .as("investmentVersion is Number-typed on the deployed schema")
                .isEqualTo(Long.toString(INVESTMENT_VERSION));
        assertThat(item.get("participations").n()).isEqualTo(Long.toString(PARTICIPATIONS));
        assertThat(item.get("createdAt").s()).isEqualTo(NOW.toString());
        assertThat(item.get("updatedAt").s()).isEqualTo(NOW.toString());
        assertThat(item.get("__typename").s()).isEqualTo("UserInvestment");
    }

    @Test
    void putItemHasAttributeNotExistsConditionOnIdHashKey() {
        // The conditional check must target the actual HASH key (`id`),
        // not the previously-assumed `userId`. With a random UUID this
        // is structurally a tautology (collisions astronomically rare),
        // but it's cheap defensive insurance — same pattern as
        // AuditLogStore. The load-bearing duplicate-prevention is the
        // surrounding reservation-status guard in the TransactWriteItems
        // envelope; see UserInvestmentStore class-level javadoc.
        Put put = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);

        assertThat(put.conditionExpression())
                .as("condition must target the actual HASH key `id`, "
                        + "not the previously-assumed `userId` (PAY-05 Phase 2b "
                        + "Option (c) drill 2026-05-12 bug class)")
                .isEqualTo("attribute_not_exists(id)");
    }

    @Test
    void putTargetsCorrectTable() {
        Put put = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);
        assertThat(put.tableName()).isEqualTo(TABLE_NAME);
    }

    @Test
    void differentInvocationsProduceDistinctIds() {
        // UUID-based id generation must produce a fresh value per
        // invocation. Belt-and-braces defence against future refactors
        // accidentally hoisting the UUID into a constant field.
        Put first = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);
        Put second = store.buildIdempotentPut(USER_ID, INVESTMENT_ID, INVESTMENT_VERSION, PARTICIPATIONS, NOW);

        assertThat(first.item().get("id").s())
                .as("two successive put-builds must produce distinct UUID ids")
                .isNotEqualTo(second.item().get("id").s());
    }
}
