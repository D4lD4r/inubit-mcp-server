package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ClientVersionPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines the version of a node's INUBIT client with {@code startcli.sh -v}: no server,
 * no credentials, nothing on stdin (feature 001 research R-6; feature 009 research D-7). The
 * comparison with the server version is {@link ClientVersion#compare} (FR-011).
 */
public final class CliVersionProbe implements ClientVersionPort {

    private static final Pattern VERSION = Pattern.compile("^CLI (\\S+)$");
    private static final Pattern SUPPORTED = Pattern.compile(
        "^Supported inubit Process Engine versions from (\\S+) to (\\S+?)\\.?$");
    /** URLs are removed from a failure's excerpt (FR-016). */
    private static final Pattern URL = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://\\S*");
    private static final String URL_REMOVED = "<url removed>";

    private final CliRunner runner;
    private final Function<NodeId, EffectiveNodeConfig> nodes;
    private final SecretScrubber scrubber;
    private final CliOutputClassifier classifier;

    /** @param nodes the configuration of every node the probe may be asked about */
    public CliVersionProbe(CliRunner runner, Function<NodeId, EffectiveNodeConfig> nodes) {
        this(runner, nodes, SecretScrubber.global());
    }

    CliVersionProbe(CliRunner runner, Function<NodeId, EffectiveNodeConfig> nodes,
        SecretScrubber scrubber) {
        this.runner = runner;
        this.nodes = nodes;
        this.scrubber = scrubber;
        this.classifier = new CliOutputClassifier(scrubber);
    }

    /** Runs {@code startcli -v} with the node's CLI home and JDK, bounded by {@code timeout}. */
    @Override
    public ClientVersion probe(NodeId node, Duration timeout) {
        EffectiveNodeConfig server = nodes.apply(node);
        CliResult result = runner.execute(server, List.of("-v"), Optional.empty(), timeout);
        CliOutput output = classifier.parse(result);
        Optional<InubitVersion> version = Optional.empty();
        Optional<InubitVersion> from = Optional.empty();
        Optional<InubitVersion> to = Optional.empty();
        for (String line : output.outputLines()) {
            Matcher versionLine = VERSION.matcher(line.strip());
            if (versionLine.matches() && version.isEmpty()) {
                version = InubitVersion.parse(versionLine.group(1));
            }
            Matcher supported = SUPPORTED.matcher(line.strip());
            if (supported.matches()) {
                from = InubitVersion.parse(supported.group(1));
                to = InubitVersion.parse(supported.group(2));
            }
        }
        if (result.exitCode() != 0 || version.isEmpty()) {
            String excerpt = scrubber.scrub(String.join("\n", output.outputLines()));
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                    "StartCLI did not report its version (exit code " + result.exitCode() + ")",
                    "cliHome is not an INUBIT client, or the JDK in cli.javaHome does not fit",
                    "Run bin/startcli.sh -v in cliHome with that JDK and check the output")
                .withNode(node)
                .withExcerpt(URL.matcher(excerpt).replaceAll(URL_REMOVED)));
        }
        // a range StartCLI printed in an unknown form is not evaluable, not a failed probe (D-8)
        boolean range = from.isPresent() && to.isPresent() && from.get().compareTo(to.get()) <= 0;
        return new ClientVersion(version.get(), range ? from : Optional.empty(),
            range ? to : Optional.empty());
    }
}
