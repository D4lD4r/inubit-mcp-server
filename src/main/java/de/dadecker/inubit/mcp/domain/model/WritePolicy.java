package de.dadecker.inubit.mcp.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The effective write settings of one server (FR-019, FR-022, data-model.md → NodeConfig
 * {@code write.*}), as the write guard and the process control service need them.
 *
 * @param production      the server's stage is classified {@code production: true}
 * @param enabled         {@code write.enabled}
 * @param productionOptIn {@code write.productionOptIn}
 * @param confirmationTtl how long a confirmation code is valid
 * @param account         the INUBIT username of the server (for the audit), if resolved
 * @param warnings        feature 009 (research D-5): the warnings of the version gate's
 *                        admission, for the result of the call (empty in the configuration)
 */
public record WritePolicy(NodeId node, boolean production, boolean enabled,
    boolean productionOptIn, Confirmation confirmation, Duration confirmationTtl,
    Optional<String> account, List<String> warnings) {

    /** {@code write.confirmation}: two-step on the server (default) or left to the client. */
    public enum Confirmation {
        SERVER,
        CLIENT
    }

    public WritePolicy {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(confirmation, "confirmation");
        Objects.requireNonNull(confirmationTtl, "confirmationTtl");
        account = account == null ? Optional.empty() : account;
        warnings = List.copyOf(warnings);
    }

    /** The settings as configured, without warnings. */
    public WritePolicy(NodeId node, boolean production, boolean enabled, boolean productionOptIn,
        Confirmation confirmation, Duration confirmationTtl, Optional<String> account) {
        this(node, production, enabled, productionOptIn, confirmation, confirmationTtl, account,
            List.of());
    }

    /** These settings with the warnings of an admission. */
    public WritePolicy withWarnings(List<String> warnings) {
        return new WritePolicy(node, production, enabled, productionOptIn, confirmation,
            confirmationTtl, account, warnings);
    }

    /** Write access after the production lock: {@code enabled && (!production || optIn)}. */
    public boolean effective() {
        return enabled && (!production || productionOptIn);
    }
}
