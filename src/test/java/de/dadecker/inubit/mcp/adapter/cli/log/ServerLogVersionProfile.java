package de.dadecker.inubit.mcp.adapter.cli.log;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTraceRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The profile of the US3 tests of feature 021 (T047): {@code defaults.cliClients."8.1"} with its
 * client installation and a JDK 17, {@code defaults.cliClients."9.0"} with its own installation
 * and a JDK 21, plain {@code http} nodes on {@code *.example.test} (no handshake, no network). Each
 * installation has StartCLI ({@link FakeStartCli}) for the trace log and what the INUBIT client
 * helper needs for the web service log. StartCLI and the helper are a {@link FakeProcessLauncher},
 * so the launched JDK, client, working directory and the helper request's {@code variant} and
 * {@code url} can be checked; no process is started. The version selection replays the 8.1
 * {@code ok} cases on both versions; the 9.0 contract (T048) replays the {@code v9_0} cases.
 */
public final class ServerLogVersionProfile implements AutoCloseable {

    /** The CliResources profile; the private directories are {@code inubit-mcp-log-t047-<pid>-…}. */
    private static final String PROFILE = "t047";
    /** The nonce in the recorded frames; the fake helper answers with the request's nonce. */
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final Pattern DOWNLOAD = Pattern.compile("--download '([^']+)'");
    private static final String FIXTURES = "server-log/";
    /** The server's environment, with credentials no launch may get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    private final Path client81;
    private final Path client90;
    private final Path jdk17;
    private final Path jdk21;
    private final CliResources resources = new CliResources(PROFILE, PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();

    /** Creates both client installations and JDKs below {@code root}. */
    public ServerLogVersionProfile(Path root) throws IOException {
        client81 = client(root.resolve("client-8.1"));
        client90 = client(root.resolve("client-9.0"));
        jdk17 = jdk(root.resolve("jdk-17"));
        jdk21 = jdk(root.resolve("jdk-21"));
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
    }

    public Path client81() {
        return client81;
    }

    public Path client90() {
        return client90;
    }

    public Path jdk17() {
        return jdk17;
    }

    public Path jdk21() {
        return jdk21;
    }

    /**
     * Node {@code id} at {@code http://<node>.example.test:8000} with both
     * {@code defaults.cliClients} entries.
     */
    public TestNodeConfig node(String id, VersionSetting line) {
        return withoutClients(id, line)
            .cliClient("8.1", client81, Optional.of(jdk17))
            .cliClient("9.0", client90, Optional.of(jdk21));
    }

    /** Node {@code id} without any client entry; the caller adds the ones it needs. */
    public TestNodeConfig withoutClients(String id, VersionSetting line) {
        String host = id.substring(id.indexOf('/') + 1);
        return TestNodeConfig.node().id(id).baseUrl("http://" + host + ".example.test:8000")
            .allowInsecureHttp(true).versionLine(line);
    }

    /**
     * The node's server-log port, running StartCLI and the helper through {@code launcher};
     * StartCLI is chosen for the effective version {@code versions} gives, as in production.
     */
    public CliServerLogAdapter port(EffectiveNodeConfig server, ProcessLauncher launcher,
        Function<NodeId, Optional<InubitVersion>> versions) {
        NodeCredentials credentials = new NodeCredentials(server.id(),
            Optional.of(new SourcedValue<>(QueueEntryVersionProfile.USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources, versions);
        CredentialGuard guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliTraceRunner traces = new CliTraceRunner(server, credentials, guard, runner,
            new CliOutputClassifier(scrubber, server.secretLabels(), runner.windows()));
        CliWebServiceLogAdapter webServiceLogs = new CliWebServiceLogAdapter(server,
            () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources,
                Path.of(System.getProperty("java.io.tmpdir"))),
            new ProcessRun(launcher, resources), scrubber);
        return new CliServerLogAdapter(server, traces, webServiceLogs,
            new CliConcurrencyLimiter(CapabilityMatrix.production()));
    }

    /** StartCLI replaying the 8.1 {@code ok} trace case: writes its log to the download path. */
    public static FakeProcessLauncher startCli() {
        return startCli("v8_1", "ok");
    }

    /**
     * StartCLI replaying trace case {@code fixtureCase} of {@code version} ({@code v8_1},
     * {@code v9_0}): prints its {@code .stdout} and {@code .stderr}, exits with its {@code .exit}
     * and writes its {@code .log} to the download path.
     */
    public static FakeProcessLauncher startCli(String version, String fixtureCase) {
        String trace = FIXTURES + version + "/trace/" + fixtureCase;
        return FakeProcessLauncher.of(text(trace + ".stdout"), text(trace + ".stderr"),
            Integer.parseInt(text(trace + ".exit").strip())).onLaunch(spec -> write(
                downloadFile(spec), FakeProcessLauncher.resource(trace + ".log")));
    }

    /**
     * The helper replaying the 8.1 {@code ok} web service case in the requested {@code variant}:
     * writes its log to the request's {@code logOut}.
     */
    public static FakeProcessLauncher helper() {
        return helper("v8_1", "ok");
    }

    /**
     * The helper replaying web service case {@code fixtureCase} of {@code version}: writes its
     * {@code .log} to the request's {@code logOut} and answers with its {@code .frames} and
     * {@code .exit}; a recorded {@code start SOAP_81} becomes the requested {@code variant}.
     */
    public static FakeProcessLauncher helper(String version, String fixtureCase) {
        String webService = FIXTURES + version + "/webservice/";
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = QueueEntryVersionProfile.request(stdin);
            write(Path.of(fields.get("logOut")),
                FakeProcessLauncher.resource(webService + fixtureCase + ".log"));
            return replay(webService, fixtureCase, fields);
        });
    }

    /**
     * The helper on a client installation without the 9.0 {@code ClusterClient} method: the
     * {@code api_missing} web service case, exit 4.
     */
    public static FakeProcessLauncher helperLackingClusterClient() {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> replay(
            FIXTURES + "v8_1/webservice/", "api_missing", QueueEntryVersionProfile.request(stdin)));
    }

    /** The {@code --download} path of a StartCLI launch. */
    private static Path downloadFile(LaunchSpec spec) {
        List<String> arguments = spec.startCliArguments();
        Matcher matcher = DOWNLOAD.matcher(arguments.get(arguments.indexOf("--execCommand") + 1));
        if (!matcher.find()) {
            throw new AssertionError("no --download path in " + arguments);
        }
        return Path.of(matcher.group(1));
    }

    /** This process's private log directories of {@link #PROFILE} below {@code java.io.tmpdir}. */
    public static List<Path> leftovers() {
        String prefix = CliResources.LOG_PREFIX + PROFILE + "-" + ProcessHandle.current().pid()
            + "-";
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        resources.close();
    }

    /**
     * The frames and exit code of web service case {@code fixtureCase} in {@code webService}, with
     * the request's nonce and {@code variant}.
     */
    private static Answer replay(String webService, String fixtureCase,
        Map<String, String> fields) {
        String frames = text(webService + fixtureCase + ".frames")
            .replace(FIXTURE_NONCE, fields.get("nonce"))
            .replace(" start SOAP_81", " start " + fields.get("variant"));
        return new Answer(frames, Integer.parseInt(text(webService + fixtureCase + ".exit")
            .strip()));
    }

    private static String text(String resource) {
        return new String(FakeProcessLauncher.resource(resource), StandardCharsets.UTF_8);
    }

    private static void write(Path file, byte[] content) {
        try {
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A client home with StartCLI, {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}.
     */
    private static Path client(Path client) throws IOException {
        FakeStartCli.install(Files.createDirectories(client));
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        return client;
    }

    /** A JDK directory with an executable {@code bin/java} and {@code bin/java.exe}. */
    private static Path jdk(Path directory) throws IOException {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        for (String name : List.of("java", "java.exe")) {
            Path java = Files.createFile(bin.resolve(name));
            if (!Platform.windows()) {
                Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            }
        }
        return directory;
    }
}
