package com.functorful.stripewebhook.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Typed configuration for the Lambda's build provenance — the version
 * tag and git SHA stamped into AuditLog rows + INFO log lines for
 * forensics across the deploy timeline.
 *
 * <p>Replaces four scattered {@code @Value} injection sites the codebase
 * carried before ARCH-09 (one pair on each of
 * {@link com.functorful.stripewebhook.dispatch.handlers.PaymentIntentSucceededHandler}
 * and
 * {@link com.functorful.stripewebhook.dispatch.handlers.PaymentIntentFailedHandler}):
 * <ul>
 *   <li>{@code @Value("${dd.version:unknown}")} → {@code build.version}</li>
 *   <li>{@code @Value("${git.sha:unknown}")} → {@code build.git-sha}</li>
 * </ul>
 *
 * <p>Mirrors the {@code WaitlistProperties} (sobrado-site-api) and
 * {@code InvestmentPaymentProperties} (this repo, PR #6) shape — the
 * canonical Micronaut idiom for grouped configuration across the
 * Sobrado backend Lambdas (ARCH-09).
 *
 * <p><strong>Naming improvement (ARCH-09).</strong> Pre-refactor, the
 * Lambda read the build version through the {@code dd.*} (Datadog)
 * namespace because Micronaut auto-maps the {@code DD_VERSION} env var
 * to {@code dd.version}. That conflated two concerns: the Datadog APM
 * "version" tag and Sobrado's build provenance. Post-refactor the YAML
 * binds {@code build.version} and {@code build.git-sha} to the underlying
 * env vars explicitly:
 *
 * <pre>
 * build:
 *   version: ${DD_VERSION:unknown}
 *   git-sha: ${GIT_SHA:unknown}
 * </pre>
 *
 * <p>The env-var contract is preserved byte-for-byte (no infrastructure
 * change required); only the Java-side namespace decouples from the
 * Datadog convention. Future: when a dedicated build-provenance source
 * is wanted (e.g. CI-injected {@code BUILD_VERSION}), only the YAML
 * changes — application code already reads through the typed bean.
 *
 * <p><strong>Defaults.</strong> Both fields default to the literal
 * {@code "unknown"}, matching the pre-refactor {@code @Value} default
 * values. The default applies when the corresponding env var is not
 * set (today's reality for {@code GIT_SHA} — no infrastructure wires
 * it yet, so audit-log rows currently carry {@code "gitSha":"unknown"};
 * tracked separately as a non-blocking ARCH-09 follow-up).
 */
@ConfigurationProperties("build")
public final class BuildMetadataProperties {

    /** Sentinel value used when the underlying env var is unset. */
    static final String UNKNOWN = "unknown";

    private String version = UNKNOWN;
    private String gitSha = UNKNOWN;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getGitSha() {
        return gitSha;
    }

    public void setGitSha(String gitSha) {
        this.gitSha = gitSha;
    }
}
