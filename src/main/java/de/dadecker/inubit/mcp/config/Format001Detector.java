package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recognizes a configuration in the format of feature 001 (002 research D-12, FR-018): a
 * top-level {@code stages} key, a {@code servers} key at any depth, or no top-level
 * {@code profile} key. Such a file is refused before binding, so that the user reads which keys
 * to change instead of "Unknown key 'stages'". Mixed files (old and new keys) are refused as well,
 * naming each old key.
 *
 * <p>The message names keys and list positions only ({@code stages[1].servers}), never a value
 * of the file: names, URLs and owners stay out of it.
 */
final class Format001Detector {

    /** The migration guide, relative to the repository root. */
    static final String GUIDE = "docs/migration-001-to-002.md";

    /** The audit directory of feature 001; its records stay there. */
    static final String OLD_AUDIT_DIRECTORY = "~/.inubit-mcp/audit";

    private static final String STAGES = "stages";
    private static final String SERVERS = "servers";
    private static final String PROFILE = "profile";

    private Format001Detector() {
    }

    /**
     * Throws a {@link ConfigException} naming every obsolete key if {@code root} is in the format
     * of feature 001 or mixes it with format v2. Top-level keys starting with {@code x-} must
     * already be removed: they are ignored, so a {@code servers} key inside one is no old key.
     */
    static void check(ObjectNode root, Path source) {
        boolean stages = root.has(STAGES);
        List<String> servers = new ArrayList<>();
        collectServers(root, "", servers);
        boolean profileMissing = !root.has(PROFILE);
        if (!stages && servers.isEmpty() && !profileMissing) {
            return;
        }
        StringBuilder message = new StringBuilder("Configuration file ").append(source)
            .append(" uses the format of feature 001 (or is not fully migrated); this version"
                + " reads only the new format. Change these keys:");
        if (stages) {
            // with groups present, renaming would give a duplicate key
            message.append(root.has("groups")
                ? "\n  - stages (top level): move its entries into groups"
                : "\n  - stages (top level): rename to groups");
        }
        if (!servers.isEmpty()) {
            message.append("\n  - servers (at ").append(String.join(", ", servers))
                .append("): rename to nodes");
        }
        if (profileMissing) {
            message.append("\n  - profile (missing): add a profile block at the top,"
                + " profile: {name: <name>}, the name matching ").append(ProfileInfo.NAME_RULE);
        }
        message.append("\nTo keep the credential variables of feature 001"
                + " (INUBIT_<STAGE>[_<SERVER>]_USERNAME / _PASSWORD), add"
                + " credentials.envPrefix: INUBIT; without it the variables start with"
                + " INUBIT_<PROFILE>.")
            .append("\nAudit records of feature 001 stay in their directory (default ")
            .append(OLD_AUDIT_DIRECTORY).append("); new records go to"
                + " ~/.inubit-mcp/<profile.name>/audit unless auditDirectory is set.")
            .append("\nStep-by-step guide: ").append(GUIDE);
        throw new ConfigException(message.toString());
    }

    /** The positions of all {@code servers} keys, e.g. {@code stages[0].servers}. */
    private static void collectServers(JsonNode node, String path, List<String> found) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (entry.getKey().equals(SERVERS)) {
                    found.add(childPath);
                } else {
                    collectServers(entry.getValue(), childPath, found);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectServers(node.get(i), path + "[" + i + "]", found);
            }
        }
    }
}
