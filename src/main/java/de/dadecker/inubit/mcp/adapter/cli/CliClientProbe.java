package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines the {@link CliClientOptions} of a node's StartCLI client (feature 011, research D-8,
 * FR-012a), only for nodes pinned to a {@linkplain CapabilityMatrix#isCliOnly CLI-only} version:
 * {@code startcli.sh -h} without server, credentials or stdin, and the Java version from
 * {@code <runtime>/release} (runtime: {@code cli.javaHome}, else the server's {@code JAVA_HOME}).
 * Other nodes get no {@code -h} run; their client is probed with {@code -v} only
 * ({@link CliVersionProbe}).
 *
 * <p>A help output that is not readable (failed run, exit code other than 0, no login option
 * listed) means every option absent (fail closed). Read options are cached per node; a failed run
 * is not cached and is repeated at most once per {@link NodeVersionState#RETRY_AFTER}.
 */
public final class CliClientProbe {

    /** Every StartCLI help lists the login option; without it the output is not a help text. */
    private static final Pattern LOGIN = option("-u");
    private static final Pattern TRUST_STORE = option("--trustStoreFilePath");
    private static final Pattern HOST_NAMES = option("--disableHostNameVerification");
    private static final Pattern EXEC_COMMAND = option("--execCommand");
    /** {@code 1.7.0_80} and {@code 1.8.0_292} are Java 7 and 8, {@code 17.0.2} is Java 17. */
    private static final Pattern JAVA_VERSION =
        Pattern.compile("^JAVA_VERSION=\"(\\d{1,3})(?:\\.(\\d{1,3}))?[^\"]*\"$");

    private final CliRunner runner;
    private final Function<NodeId, EffectiveNodeConfig> nodes;
    private final CapabilityMatrix matrix;
    private final Clock clock;
    private final CliOutputClassifier classifier = CliOutputClassifier.withGlobalScrubber();
    private final Map<NodeId, CliClientOptions> cache = new ConcurrentHashMap<>();
    private final Map<NodeId, Instant> failures = new ConcurrentHashMap<>();

    /** @param nodes the configuration of every node the probe may be asked about */
    public CliClientProbe(CliRunner runner, Function<NodeId, EffectiveNodeConfig> nodes,
        CapabilityMatrix matrix, Clock clock) {
        this.runner = runner;
        this.nodes = nodes;
        this.matrix = matrix;
        this.clock = clock;
    }

    /**
     * The client options of {@code node}: empty for a node not pinned to a CLI-only version,
     * else the cached options or those of a synchronous {@code -h} run bounded by
     * {@code timeout}. Concurrent first calls may each run {@code -h}.
     */
    public Optional<CliClientOptions> optionsFor(NodeId node, Duration timeout) {
        EffectiveNodeConfig server = nodes.apply(node);
        if (!matrix.isCliOnly(NodeVersionState.initial(server.versionLine()).effectiveVersion())) {
            return Optional.empty();
        }
        CliClientOptions cached = cache.get(node);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Integer> java = javaFeatureVersion(server);
        Instant failed = failures.get(node);
        Instant now = clock.instant();
        if (failed == null || !now.isBefore(failed.plus(NodeVersionState.RETRY_AFTER))) {
            Optional<CliClientOptions> read = help(server, timeout, java);
            if (read.isPresent()) {
                cache.put(node, read.get());
                failures.remove(node);
                return read;
            }
            failures.put(node, clock.instant());
        }
        return Optional.of(new CliClientOptions(false, false, false, java));
    }

    /** The options of the help output, or empty if it is not readable. */
    private Optional<CliClientOptions> help(EffectiveNodeConfig server, Duration timeout,
        Optional<Integer> java) {
        CliResult result;
        try {
            result = runner.execute(server, List.of("-h"), Optional.empty(), timeout);
        } catch (ToolErrorException e) {
            // no client, not startable or timed out: nothing is known about its options
            return Optional.empty();
        }
        List<String> lines = classifier.parse(result).outputLines();
        if (result.exitCode() != 0 || !lists(lines, LOGIN)) {
            return Optional.empty();
        }
        return Optional.of(new CliClientOptions(lists(lines, TRUST_STORE),
            lists(lines, HOST_NAMES), lists(lines, EXEC_COMMAND), java));
    }

    /** The feature version from {@code JAVA_VERSION} in the runtime's {@code release} file. */
    private Optional<Integer> javaFeatureVersion(EffectiveNodeConfig server) {
        Optional<String> home = runner.javaHome(server);
        if (home.isEmpty()) {
            return Optional.empty();
        }
        List<String> release;
        try {
            release = Files.readAllLines(Path.of(home.get()).resolve("release"),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        for (String line : release) {
            Matcher version = JAVA_VERSION.matcher(line.strip());
            if (version.matches()) {
                return version.group(1).equals("1")
                    ? Optional.ofNullable(version.group(2)).map(Integer::valueOf)
                    : Optional.of(Integer.valueOf(version.group(1)));
            }
        }
        return Optional.empty();
    }

    private static boolean lists(List<String> lines, Pattern option) {
        return lines.stream().anyMatch(line -> option.matcher(line).find());
    }

    /** {@code name} as a whole token, not as part of a longer option. */
    private static Pattern option(String name) {
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + "(?![\\w-])");
    }
}
