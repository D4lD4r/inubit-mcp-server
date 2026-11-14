package de.dadecker.inubit.mcp.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registered {@link ActionSpec}s by tool and action (feature 022, research D-2); coverage
 * check 6 reads them.
 */
public final class ActionCatalog {

    private record Key(String tool, String action) {
    }

    private final Map<Key, ActionSpec> specs = new LinkedHashMap<>();

    /** @throws IllegalArgumentException on two specs for the same tool and action */
    public ActionCatalog(Collection<ActionSpec> specs) {
        for (ActionSpec spec : specs) {
            if (this.specs.putIfAbsent(new Key(spec.tool(), spec.action()), spec) != null) {
                throw new IllegalArgumentException("Two specs for " + spec.tool() + "/"
                    + spec.action());
            }
        }
    }

    /** The spec of {@code action} of {@code tool}, if registered. */
    public Optional<ActionSpec> spec(String tool, String action) {
        return Optional.ofNullable(specs.get(new Key(tool, action)));
    }

    /** Every spec, in registration order. */
    public List<ActionSpec> specs() {
        return List.copyOf(specs.values());
    }
}
