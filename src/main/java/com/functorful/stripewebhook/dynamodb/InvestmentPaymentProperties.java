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
 *       {@code "investmentPaymentByReservation"}) — the Amplify-generated
 *       GSI name used to look up payment rows by their reservation FK.
 *       The default mirrors the Amplify Gen-2 naming convention; an env
 *       override exists so operators can re-point without a code deploy
 *       if the generated name diverges.</li>
 * </ul>
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

    /** Default mirrors the Amplify Gen-2 belongsTo GSI naming convention. */
    static final String DEFAULT_BY_RESERVATION_INDEX_NAME = "investmentPaymentByReservation";

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
