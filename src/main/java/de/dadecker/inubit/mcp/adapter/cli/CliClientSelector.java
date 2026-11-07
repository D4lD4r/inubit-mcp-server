package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.CliChoice;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * {@link ClientSelectionPort} from {@link CliRunner#select(EffectiveNodeConfig, Optional)}, the
 * existence check of the StartCLI script and the host's platform (feature 017, research D-7).
 * {@link #anyCandidate} asks only whether a client home is configured, as the tool announcement
 * of 0.8.0 did with {@code cli.home} (FR-009).
 */
public final class CliClientSelector implements ClientSelectionPort {

    private final CliRunner runner;
    private final Function<NodeId, EffectiveNodeConfig> nodes;
    private final Predicate<Path> exists;
    private final boolean windows;

    /**
     * @param nodes   the effective configuration of a node
     * @param exists  whether a file exists (StartCLI script)
     * @param windows whether StartCLI is {@code startcli.bat}
     */
    public CliClientSelector(CliRunner runner, Function<NodeId, EffectiveNodeConfig> nodes,
        Predicate<Path> exists, boolean windows) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.exists = Objects.requireNonNull(exists, "exists");
        this.windows = windows;
    }

    @Override
    public Optional<ClientKey> current(NodeId node, Optional<InubitVersion> version) {
        CliRunner.Selection selection = select(node, version);
        return selection.server().cli().home().map(home -> selection.key());
    }

    @Override
    public boolean available(NodeId node, Optional<InubitVersion> version) {
        return exists(select(node, version));
    }

    @Override
    public boolean anyCandidate(NodeId node) {
        EffectiveNodeConfig server = nodes.apply(node);
        Optional<InubitVersion> pinned =
            NodeVersionState.initial(server.versionLine()).effectiveVersion();
        if (!(server.versionLine() instanceof VersionSetting.Auto)) {
            return chosen(runner.select(server, pinned));
        }
        return Stream.concat(Stream.of(Optional.<InubitVersion>empty()),
                server.cliSources().clients().keySet().stream()
                    .map(entry -> Optional.of(new InubitVersion(entry.major(), entry.minor(),
                        OptionalInt.empty(), entry.major() + "." + entry.minor()))))
            .anyMatch(version -> chosen(runner.select(server, version)));
    }

    @Override
    public Optional<String> warning(NodeId node, Optional<InubitVersion> version) {
        return select(node, version).addressWarning();
    }

    @Override
    public NodeSummary.StartCli startCli(NodeId node, Optional<InubitVersion> version) {
        CliRunner.Selection selection = select(node, version);
        CliChoice choice = selection.choice();
        return new NodeSummary.StartCli(choice.cli().home().map(Path::toString),
            choice.source().name().toLowerCase(Locale.ROOT), choice.entry().map(MajorMinor::text),
            choice.address().name().toLowerCase(Locale.ROOT), version.isPresent()
                || !(selection.server().versionLine() instanceof VersionSetting.Auto));
    }

    private CliRunner.Selection select(NodeId node, Optional<InubitVersion> version) {
        return runner.select(nodes.apply(node), version);
    }

    /** Whether a client home applies, installed or not (as {@code cli.home} in 0.8.0). */
    private static boolean chosen(CliRunner.Selection selection) {
        return selection.server().cli().home().isPresent();
    }

    private boolean exists(CliRunner.Selection selection) {
        return selection.server().startCliScript(windows).filter(exists).isPresent();
    }
}
