package de.dadecker.inubit.mcp.domain.model.management;

import java.util.Objects;

/**
 * A non-secret top-level setting whose shown value differs between the managing element and the
 * module (data-model.md → Difference, FR-007, research D-6).
 *
 * @param name    the setting name, present on both sides
 * @param central the element's shown value; never contains a {@code ${secret:} placeholder
 * @param module  the module's shown value; never contains a {@code ${secret:} placeholder
 */
public record Difference(String name, String central, String module) {

    private static final String SECRET_PLACEHOLDER = "${secret:";

    public Difference {
        Objects.requireNonNull(name, "name");
        requireNoSecret(central, "central");
        requireNoSecret(module, "module");
    }

    private static void requireNoSecret(String value, String field) {
        if (Objects.requireNonNull(value, field).contains(SECRET_PLACEHOLDER)) {
            throw new IllegalArgumentException(field + " must not contain a secret placeholder");
        }
    }
}
