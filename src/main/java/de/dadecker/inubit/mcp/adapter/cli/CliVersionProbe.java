package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines the version of the configured INUBIT client with {@code startcli.sh -v}: no server,
 * no credentials, nothing on stdin (spec edge case "CLI client version mismatch", research R-6).
 * The client must match the server's patch level, so a difference is reported as a warning.
 */
public final class CliVersionProbe {

    private static final Pattern VERSION = Pattern.compile("^CLI (\\S+)$");
    private static final Pattern SUPPORTED = Pattern.compile(
        "^Supported inubit Process Engine versions from (\\S+) to (\\S+?)\\.?$");

    /** The client version and the server versions it declares to support. */
    public record CliVersion(String version, Optional<String> supportedFrom,
        Optional<String> supportedTo) {

        public CliVersion {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(supportedFrom, "supportedFrom");
            Objects.requireNonNull(supportedTo, "supportedTo");
        }
    }

    private final CliRunner runner;
    private final SecretScrubber scrubber;
    private final CliOutputClassifier classifier;

    public CliVersionProbe(CliRunner runner) {
        this(runner, SecretScrubber.global());
    }

    CliVersionProbe(CliRunner runner, SecretScrubber scrubber) {
        this.runner = runner;
        this.scrubber = scrubber;
        this.classifier = new CliOutputClassifier(scrubber);
    }

    /**
     * Runs {@code startcli -v} with the server's CLI home, JDK and {@code cliTimeout}.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} if StartCLI cannot be run or prints no
     *     version, {@code TIMEOUT} if it does not finish in time
     */
    public CliVersion probe(EffectiveNodeConfig server) {
        CliResult result = runner.execute(server, List.of("-v"), Optional.empty(),
            server.cliTimeout());
        CliOutput output = classifier.parse(result);
        Optional<String> version = Optional.empty();
        Optional<String> from = Optional.empty();
        Optional<String> to = Optional.empty();
        for (String line : output.outputLines()) {
            Matcher versionLine = VERSION.matcher(line.strip());
            if (versionLine.matches() && version.isEmpty()) {
                version = Optional.of(versionLine.group(1));
            }
            Matcher supported = SUPPORTED.matcher(line.strip());
            if (supported.matches()) {
                from = Optional.of(supported.group(1));
                to = Optional.of(supported.group(2));
            }
        }
        if (result.exitCode() != 0 || version.isEmpty()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                    "StartCLI did not report its version (exit code " + result.exitCode() + ")",
                    "cliHome is not an INUBIT client, or the JDK in cli.javaHome does not fit",
                    "Run bin/startcli.sh -v in cliHome with that JDK and check the output")
                .withNode(server.id())
                .withExcerpt(scrubber.scrub(String.join("\n", output.outputLines()))));
        }
        return new CliVersion(version.get(), from, to);
    }

    /** A warning if the client version differs from the server version (from /system/info). */
    public static Optional<String> mismatchWarning(NodeId server, CliVersion cli,
        String serverVersion) {
        if (cli.version().equals(serverVersion)) {
            return Optional.empty();
        }
        String range = cli.supportedTo()
            .map(to -> " (it supports INUBIT servers " + cli.supportedFrom().orElse("?") + " to " + to
                + ")")
            .orElse("");
        return Optional.of(server + ": the INUBIT client in cli.home is version " + cli.version()
            + range + ", but the INUBIT server runs " + serverVersion + "; StartCLI must match the"
            + " INUBIT server patch level, so set cli.home to a matching client");
    }
}
