package de.dadecker.inubit.mcp.config;

import java.util.List;
import java.util.Optional;

/**
 * Feature 024 (T003, FR-002, research D-5): the four node-tool opt-ins below {@code unsupported},
 * addressed by their YAML key so tests can cover each key the same way.
 */
final class NodeToolOptIns {

    /** The four keys in the order of {@link UnsupportedConfig}. */
    static final List<String> KEYS =
        List.of("nodeStatusRead", "ioLogRead", "nodeConfigRead", "logLevelWrite");

    private NodeToolOptIns() {
    }

    /** An {@code unsupported} block that sets only {@code key}. */
    static UnsupportedConfig only(String key, boolean value) {
        requireKey(key);
        Optional<Boolean> set = Optional.of(value);
        return new UnsupportedConfig(Optional.empty(), Optional.empty(),
            key.equals("nodeStatusRead") ? set : Optional.empty(),
            key.equals("ioLogRead") ? set : Optional.empty(),
            key.equals("nodeConfigRead") ? set : Optional.empty(),
            key.equals("logLevelWrite") ? set : Optional.empty());
    }

    /** The effective value of {@code key} on {@code server}. */
    static boolean of(EffectiveNodeConfig server, String key) {
        return switch (key) {
            case "nodeStatusRead" -> server.nodeStatusRead();
            case "ioLogRead" -> server.ioLogRead();
            case "nodeConfigRead" -> server.nodeConfigRead();
            case "logLevelWrite" -> server.logLevelWrite();
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static void requireKey(String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException(key);
        }
    }
}
