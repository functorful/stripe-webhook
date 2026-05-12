package com.functorful.stripewebhook.dynamodb;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the idempotent {@link Put} payload that creates the
 * {@code UserInvestment} row on {@code payment_intent.succeeded}.
 *
 * <p><strong>Deployed key shape</strong> (verified via
 * {@code aws dynamodb describe-table} against
 * {@code UserInvestment-2mm6zfbtrngy5jqblv7nna6b2e-NONE} on 2026-05-12,
 * Tomás's PAY-05 Phase 2b Option (c) drill anchor):
 * <ul>
 *   <li>partition key: {@code id} (S) — Amplify Gen 2 default
 *       UUID identifier. No sort key on the base table.</li>
 *   <li>GSI {@code gsi-Investment.userInvestments}: HASH={@code investmentId},
 *       RANGE={@code investmentVersion}.</li>
 *   <li>GSI {@code UserInvestmentByUserAndInvestment}: HASH={@code userId},
 *       RANGE={@code investmentId#investmentVersion} (composite separator-
 *       joined attribute, populated alongside the singleton scalars to
 *       keep the GSI queryable).</li>
 * </ul>
 *
 * <p>The earlier handler shape (PAY-05 Phase 2b initial implementation)
 * assumed a composite base-table key of {@code userId} HASH +
 * {@code investmentId#investmentVersion} RANGE — analogous to
 * {@link InvestmentReservationStore}. That assumption was wrong: the
 * Amplify Gen 2 schema declaration for {@code UserInvestment} uses the
 * default UUID-id identifier, so the deployed PK is {@code id} only. The
 * TWI cancelled at operation 3 with a
 * {@code ValidationException}-class failure because the put item was
 * missing the actual {@code id} HASH key. See {@code feedback_amplify_gen2_default_id_pk}
 * (forthcoming) for the structural framing.
 *
 * <p><strong>Idempotency posture</strong>: the put carries
 * {@code conditionExpression = "attribute_not_exists(id)"}. With a fresh
 * random UUID this is structurally a tautology (UUID collisions are
 * astronomically rare), so the conditional is cheap defensive insurance
 * — same pattern as {@link AuditLogStore}. The **load-bearing** duplicate-
 * prevention for the UserInvestment row comes from the surrounding
 * {@code TransactWriteItems} reservation-status guard
 * ({@code #s = :expected} on the {@code InvestmentReservation} update):
 * if a replay re-runs the TWI after the first successful run, the
 * reservation's status is already {@code executed}, so the conditional-
 * check fails, the entire TWI rolls back atomically, and no duplicate
 * UserInvestment row is created. Combined with the
 * {@code WebhookEvent.eventId}-keyed dispatcher-level dedup (PAY-02),
 * the duplicate-prevention is two-layered.
 */
@Slf4j
@Singleton
public class UserInvestmentStore {

    /**
     * Composite sort-key attribute name on the
     * {@code UserInvestmentByUserAndInvestment} GSI. Populated as a
     * non-key item attribute on the base-table put so the GSI is
     * queryable via {@code getByUserAndInvestment} (Amplify-generated
     * query field name; resource.ts:441).
     */
    static final String GSI_USER_INVESTMENT_SORT_KEY_ATTRIBUTE_NAME =
            "investmentId#investmentVersion";

    static final String GSI_USER_INVESTMENT_SORT_KEY_SEPARATOR = "#";

    private final String tableName;

    public UserInvestmentStore(@Value("${user-investment.table-name}") String tableName) {
        this.tableName = tableName;
    }

    /** Visible for testing. */
    public String tableName() {
        return tableName;
    }

    /**
     * Build (do NOT execute) the {@link Put} that creates the
     * UserInvestment row idempotently. Caller adds it to the
     * surrounding {@code TransactWriteItems} request.
     *
     * @param userId            owner of the investment
     * @param investmentId      target investment id
     * @param investmentVersion target investment version (composite identifier)
     * @param participations    number of participations purchased
     * @param now               wall-clock for {@code createdAt}/{@code updatedAt}
     */
    public Put buildIdempotentPut(
            String userId,
            String investmentId,
            long investmentVersion,
            long participations,
            Instant now
    ) {
        String id = UUID.randomUUID().toString();
        String gsiUserInvestmentSortKey =
                investmentId + GSI_USER_INVESTMENT_SORT_KEY_SEPARATOR + investmentVersion;
        String createdAt = now.toString();

        Map<String, AttributeValue> item = new LinkedHashMap<>();
        // Base-table PK — Amplify Gen 2 default UUID identifier.
        item.put("id", AttributeValue.fromS(id));
        // Domain attributes (also keys on the GSIs — Amplify-managed).
        item.put("userId", AttributeValue.fromS(userId));
        item.put("investmentId", AttributeValue.fromS(investmentId));
        item.put("investmentVersion", AttributeValue.fromN(Long.toString(investmentVersion)));
        // Composite GSI sort-key attribute for UserInvestmentByUserAndInvestment.
        // Amplify writes this alongside the scalar fields to keep the
        // user-scoped index queryable (resource.ts:441-443).
        item.put(GSI_USER_INVESTMENT_SORT_KEY_ATTRIBUTE_NAME,
                AttributeValue.fromS(gsiUserInvestmentSortKey));
        item.put("participations", AttributeValue.fromN(Long.toString(participations)));
        // Amplify-managed metadata fields.
        item.put("createdAt", AttributeValue.fromS(createdAt));
        item.put("updatedAt", AttributeValue.fromS(createdAt));
        item.put("__typename", AttributeValue.fromS("UserInvestment"));

        // attribute_not_exists check on `id` — defensive against the
        // (astronomically unlikely) UUID collision case. The
        // load-bearing duplicate-prevention is the surrounding
        // reservation-status guard in the TransactWriteItems envelope;
        // see class-level javadoc.
        return Put.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(id)")
                .build();
    }
}
