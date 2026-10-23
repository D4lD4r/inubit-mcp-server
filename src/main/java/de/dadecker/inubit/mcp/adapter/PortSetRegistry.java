package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which {@link PortSet} serves which INUBIT version (feature 009 research D-16, FR-013): version
 * ranges mapped to port-set factories, plus a default for every version no range contains and for
 * an unknown version. Ranges are matched in registration order. Immutable: {@link #register}
 * returns a new registry.
 *
 * <p>Production uses {@link #v81Default()}: the 8.1 port set for every version, so unverified
 * reads run on the 8.1 adapters. A later version adds its range with its own factory.
 */
public final class PortSetRegistry {

    /** Builds the port set of one server from its resources; no network access. */
    @FunctionalInterface
    public interface Factory {
        PortSet create(NodeResources node);
    }

    /**
     * What a port set is built from: the server's configuration and credentials, its credential
     * guard and REST client (shared with the monitoring), the StartCLI runner and the scrubber.
     */
    public record NodeResources(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, InubitHttpClient client, CliRunner cliRunner,
        SecretScrubber scrubber) {
    }

    /** The factory chosen for a version; {@code range} is empty for the default. */
    record Selection(Optional<VersionRange> range, Factory factory) {
    }

    private record Entry(VersionRange range, Factory factory) {
    }

    private final Factory defaultFactory;
    private final List<Entry> entries;

    /** A registry without ranges: {@code defaultFactory} serves every version. */
    public PortSetRegistry(Factory defaultFactory) {
        this(defaultFactory, List.of());
    }

    private PortSetRegistry(Factory defaultFactory, List<Entry> entries) {
        this.defaultFactory = Objects.requireNonNull(defaultFactory, "defaultFactory");
        this.entries = List.copyOf(entries);
    }

    /** The production registry: only the 8.1 port set, as default for every version. */
    public static PortSetRegistry v81Default() {
        return new PortSetRegistry(V81PortSet::create);
    }

    /** A copy of this registry in which {@code factory} serves the versions in {@code range}. */
    public PortSetRegistry register(VersionRange range, Factory factory) {
        List<Entry> extended = new ArrayList<>(entries);
        extended.add(new Entry(Objects.requireNonNull(range, "range"),
            Objects.requireNonNull(factory, "factory")));
        return new PortSetRegistry(defaultFactory, extended);
    }

    /** The first registered range containing {@code version}, else the default. */
    Selection select(Optional<InubitVersion> version) {
        if (version.isPresent()) {
            for (Entry entry : entries) {
                if (entry.range().contains(version.get())) {
                    return new Selection(Optional.of(entry.range()), entry.factory());
                }
            }
        }
        return new Selection(Optional.empty(), defaultFactory);
    }
}
