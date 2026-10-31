package de.dadecker.inubit.mcp.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Environment variables that an earlier version read as INUBIT credentials (feature 011, FR-003,
 * research R-10). Since 0.7.0 no credential comes from the environment; variables with names this
 * profile derives are reported in one warning, names only, so that they are moved and removed.
 */
public final class LeftoverVariables {

    private LeftoverVariables() {
    }

    /**
     * One warning naming the set variables of {@link ProfileConfig#credentialNameSet()}, or none.
     * Values are never read beyond "set and not empty".
     */
    public static Optional<String> warning(Map<String, String> environment, ProfileConfig config) {
        Set<String> set = new TreeSet<>();
        for (String name : config.credentialNameSet()) {
            String value = environment.get(name);
            if (value != null && !value.isEmpty()) {
                set.add(name);
            }
        }
        if (set.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Environment variables are no longer read for INUBIT credentials (since"
            + " 0.7.0): " + String.join(", ", set) + "; move them to the credential store or"
            + " Parameter Store (docs/migration-environment-variables.md)");
    }
}
