package com.functorful.stripewebhook.dynamodb;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration binding for {@link InvestmentPaymentStore}.
 *
 * <p>Encapsulates the two settings the store needs:
 * <ul>
 *   <li>{@code investment-payment.table-name} (required, no default) — the
 *       suffixed Amplify-managed DDB table name, sourced from the
 *       {@code INVESTMENT_PAYMENT_TABLE_NAME} env var via Micronaut's
 *       standard kebab-to-uppercase mapping. Lambda init throws
 *       fail-closed if unset.</li>
 *   <li>{@code investment-payment.by-reservation-index-name} (default
 *       {@code "gsi-InvestmentReservation.payments"}) — the Amplify-
 *       generated GSI name used to look up payment rows by their
 *       reservation FK. The default matches the Amplify Gen 2 auto-
 *       generated GSI naming convention for {@code hasMany} ↔
 *       {@code belongsTo} relationships:
 *       {@code gsi-<ParentModel>.<relationshipField>}. An env override
 *       exists so operators can re-point without a code deploy if the
 *       generated name ever diverges in a future Amplify codegen change.</li>
 * </ul>
 *
 * <p><strong>Default value history</strong>: the original default was
 * {@code "investmentPaymentByReservation"}, guessed by analogy to the
 * PAY-07-style explicit {@code .queryField()} naming. Empirically
 * verified wrong via Tomás's PAY-05 Phase 2b Option (c) drill 2026-05-12
 * (handler queried a non-existent index → {@code DynamoDbException} →
 * outer catch → 200 to Stripe + zero writes — customer-facing data
 * integrity bug). Fixed via infrastructure MR !51 (IaC env-var bump) +
 * this constant (handler default mirror). See HYG-19 axis-4 canonical
 * empirical anchor (https://www.notion.so/35ed485396648140bc0cd6a195c819eb).
 *
 * <p>Lifted from inline {@code @Value} bindings on the
 * {@link InvestmentPaymentStore} constructor (Miguel review on PR #6,
 * commit {@code 528aefc}, comment on
 * {@code InvestmentPaymentStore.java:72}). A typed properties bean lets
 * the framework validate / surface defaults centrally and keeps the
 * store's constructor signature small.
 */
@ConfigurationProperties("investment-payment")
public final class InvestmentPaymentProperties {

    /**
     * Default matches the Amplify Gen 2 auto-generated GSI naming
     * convention for {@code hasMany} ↔ {@code belongsTo} relationships:
     * {@code gsi-<ParentModel>.<relationshipField>}. Source-of-truth is
     * the Amplify codegen output for the InvestmentReservation.payments /
     * InvestmentPayment.investmentReservation relation declared in
     * {@code application/amplify/data/resource.ts}.
     */
    static final String DEFAULT_BY_RESERVATION_INDEX_NAME = "gsi-InvestmentReservation.payments";

    private String tableName;
    private String byReservationIndexName = DEFAULT_BY_RESERVATION_INDEX_NAME;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getByReservationIndexName() {
        return byReservationIndexName;
    }

    public void setByReservationIndexName(String byReservationIndexName) {
        this.byReservationIndexName = byReservationIndexName;
    }
}
