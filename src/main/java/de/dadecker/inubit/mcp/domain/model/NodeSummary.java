package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;

/**
 * One configured server as listed by {@code list_nodes} (data-model.md → NodeSummary, FR-003).
 * Deliberately without URLs, usernames or credentials.
 *
 * @param writeEnabled       effective: {@code write.enabled && (!production || productionOptIn)};
 *                           governs restart and kill only
 * @param developmentEnabled a development stage: {@code development.enabled && !production};
 *                           governs the development tools (import, restore, set_active, tag)
 *                           (0.4.2)
 * @param confirmationMode   {@code SERVER} or {@code CLIENT} (of {@code write})
 * @param versionLine        {@code AUTO}, {@code V8_1} or {@code V9_X} as configured
 * @param cliAvailable       CLI home configured and {@code startcli} script found
 */
public record NodeSummary(
    NodeId id,
    GroupId group,
    String node,
    boolean production,
    boolean writeEnabled,
    boolean developmentEnabled,
    String confirmationMode,
    String versionLine,
    boolean cliAvailable) {

    public NodeSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(confirmationMode, "confirmationMode");
        Objects.requireNonNull(versionLine, "versionLine");
        if (!id.group().equals(group) || !id.name().equals(node)) {
            throw new IllegalArgumentException("id " + id + " does not match " + group + "/"
                + node);
        }
    }
}
