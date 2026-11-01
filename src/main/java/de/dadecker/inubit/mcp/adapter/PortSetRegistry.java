package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeAccess;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
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
 * <p>A range registered with a {@link CliHealthFactory} is CLI-only (feature 011, research D-4,
 * {@link NodeAccess#CLI_ONLY}): its server is reached through StartCLI only, so its factories get
 * no REST client and {@code AdapterGatewayFactory} builds none for it.
 *
 * <p>Production uses {@link #v81Default()}, the 8.1 port set for every version, so unverified
 * reads run on the 8.1 adapters; {@code Wiring} registers the CLI-only 6.1 range on top. A later
 * version adds its range with its own factory.
 */
public final class PortSetRegistry {

    /** Builds the port set of one server from its resources; no network access. */
    @FunctionalInterface
    public interface Factory {
        PortSet create(NodeResources node);
    }

    /** Builds the StartCLI health port of a CLI-only server from its resources; no contact. */
    @FunctionalInterface
    public interface CliHealthFactory {
        CliHealthPort create(NodeResources node);
    }

    /**
     * What a port set is built from: the server's configuration and credentials, its credential
     * guard and REST client (shared with the monitoring), the StartCLI runner and the scrubber.
     * {@code client} is {@code null} for a CLI-only range.
     */
    public record NodeResources(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, InubitHttpClient client, CliRunner cliRunner,
        SecretScrubber scrubber) {
    }

    /**
     * The factories chosen for a version; {@code range} is empty for the default, {@code cliHealth}
     * is present for a CLI-only range.
     */
    record Selection(Optional<VersionRange> range, Factory factory,
        Optional<CliHealthFactory> cliHealth) {

        NodeAccess access() {
            return cliHealth.isPresent() ? NodeAccess.CLI_ONLY : NodeAccess.REST_AND_CLI;
        }
    }

    private record Entry(VersionRange range, Factory factory,
        Optional<CliHealthFactory> cliHealth) {
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

    /** The 8.1 port set as default for every version, without ranges. */
    public static PortSetRegistry v81Default() {
        return new PortSetRegistry(V81PortSet::create);
    }

    /** A copy of this registry in which {@code factory} serves the versions in {@code range}. */
    public PortSetRegistry register(VersionRange range, Factory factory) {
        return register(range, factory, Optional.empty());
    }

    /**
     * A copy of this registry in which the versions in {@code range} are CLI-only: {@code factory}
     * builds their port set and {@code cliHealth} their StartCLI health port, both without REST
     * client.
     */
    public PortSetRegistry register(VersionRange range, Factory factory,
        CliHealthFactory cliHealth) {
        return register(range, factory,
            Optional.of(Objects.requireNonNull(cliHealth, "cliHealth")));
    }

    private PortSetRegistry register(VersionRange range, Factory factory,
        Optional<CliHealthFactory> cliHealth) {
        List<Entry> extended = new ArrayList<>(entries);
        extended.add(new Entry(Objects.requireNonNull(range, "range"),
            Objects.requireNonNull(factory, "factory"), cliHealth));
        return new PortSetRegistry(defaultFactory, extended);
    }

    /** The first registered range containing {@code version}, else the default. */
    Selection select(Optional<InubitVersion> version) {
        if (version.isPresent()) {
            for (Entry entry : entries) {
                if (entry.range().contains(version.get())) {
                    return new Selection(Optional.of(entry.range()), entry.factory(),
                        entry.cliHealth());
                }
            }
        }
        return new Selection(Optional.empty(), defaultFactory, Optional.empty());
    }
}
