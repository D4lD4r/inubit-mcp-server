package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The profile of the US3 tests (feature 020, T031): {@code defaults.cliClients."8.1"} with its
 * client installation and a JDK 17, {@code defaults.cliClients."9.0"} with its own installation and
 * a JDK 21, plain {@code http} nodes on {@code *.example.test} (no handshake, no network). The
 * helper is a {@link FakeProcessLauncher} that answers the request it reads from stdin, so the
 * launched JDK, class path, working directory and the request's {@code variant} and {@code url}
 * can be checked; no process is started.
 */
public final class QueueEntryVersionProfile implements AutoCloseable {

    public static final String USER = "jdoe";
    public static final byte[] DATA = "<order id=\"Order-0001\"/>"
        .getBytes(StandardCharsets.UTF_8);
    public static final String WFSP = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<Properties version=\"4.1\"><Property name=\"orderId\">Order-0001</Property>"
        + "</Properties>";
    /** The 9.0 class an 8.1 client installation lacks (helper source, {@code REST_90}). */
    public static final String REST_CLIENT_FACTORY =
        "com.inubit.ibis.client.rest.InubitClientFactory";

    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    private final Path client81;
    private final Path client90;
    private final Path jdk17;
    private final Path jdk21;
    private final Path tempRoot;
    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();

    /** Creates both client installations and JDKs below {@code root}. */
    public QueueEntryVersionProfile(Path root) throws IOException {
        client81 = client(root.resolve("client-8.1"));
        client90 = client(root.resolve("client-9.0"));
        jdk17 = jdk(root.resolve("jdk-17"));
        jdk21 = jdk(root.resolve("jdk-21"));
        tempRoot = Files.createDirectories(root.resolve("tmp"));
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

    /** The node's queue-entry adapter, running the helper through {@code launcher}. */
    public CliQueueEntryAdapter adapter(EffectiveNodeConfig server,
        FakeProcessLauncher launcher) {
        NodeCredentials credentials = new NodeCredentials(server.id(),
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        HelperInstallation.Host host = Platform.windows()
            ? new HelperInstallation.Host(true, PARENT,
                new FixedSystem(new CliSystem.Read(TestCliRunners.SYSTEM_DIRECTORY),
                    Charset.forName("IBM850")),
                ProcessContainment.Bound::new, resources, tempRoot)
            : new HelperInstallation.Host(false, PARENT,
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, tempRoot);
        return new CliQueueEntryAdapter(server, () -> credentials,
            new CredentialGuard(server.secretLabels(), Clock.systemUTC()), runner, host,
            new ProcessRun(launcher, resources),
            new CliConcurrencyLimiter(CapabilityMatrix.production()), scrubber);
    }

    /** A helper that reads the entry: writes {@link #DATA} and {@link #WFSP}, as its variant. */
    public static FakeProcessLauncher helperReading() {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = request(stdin);
            List<String> frames = new ArrayList<>(List.of("start " + fields.get("variant"),
                "login ok"));
            if (!fields.get("dataOut").isEmpty()) {
                write(fields.get("dataOut"), DATA);
                frames.add("part data ok " + DATA.length);
            }
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            frames.add("part variables ok " + WFSP.length());
            frames.add("end");
            return answer(0, fields.get("nonce"), frames);
        });
    }

    /** A helper on a client installation without {@code what}: one {@code api missing}, exit 4. */
    public static FakeProcessLauncher helperLacking(String what) {
        return FakeProcessLauncher.of("", "", 0).responding(stdin -> answer(4,
            request(stdin).get("nonce"), List.of("api missing " + what)));
    }

    /** The request's {@code key=value} lines up to {@code end}, percent-decoded. */
    public static Map<String, String> request(byte[] stdin) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(stdin, StandardCharsets.US_ASCII).split("\n")) {
            if (line.equals("end")) {
                break;
            }
            int equals = line.indexOf('=');
            fields.put(line.substring(0, equals), percentDecode(line.substring(equals + 1)));
        }
        return fields;
    }

    /** The argument after {@code -cp}. */
    public static String classPath(LaunchSpec spec) {
        List<String> command = spec.command();
        return command.get(command.indexOf("-cp") + 1);
    }

    /** The java launcher of {@code jdk} as the helper's first argument names it. */
    public static String java(Path jdk) {
        return jdk.resolve("bin").resolve(Platform.windows() ? "java.exe" : "java").toString();
    }

    /** The class path entries of the client installation {@code home}. */
    public static List<String> clientClassPath(Path home) {
        String separator = home.getFileSystem().getSeparator();
        return List.of(home.resolve("lib") + separator + "*",
            home.resolve("lib").resolve("shared") + separator + "*");
    }

    /** Neither the argument array nor the environment carries a secret. */
    public static void assertLaunchedWithoutSecrets(LaunchSpec spec) {
        TestSecrets.assertNoLeak(String.join(" ", spec.command()));
        TestSecrets.assertNoLeak(spec.environment().toString());
    }

    /** Nothing is left below the temporary root. */
    public void assertTempRootEmpty() {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        resources.close();
    }

    private static Answer answer(int exit, String nonce, List<String> frames) {
        StringBuilder out = new StringBuilder("vendor noise before the frames\n");
        for (String frame : frames) {
            out.append("#QE1:").append(nonce);
            for (String token : frame.split(" ", 5)) {
                out.append(' ').append(URLEncoder.encode(token, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
            }
            out.append('\n');
        }
        return new Answer(out.toString(), exit);
    }

    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                bytes.writeBytes(HexFormat.of().parseHex(value, i + 1, i + 3));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void write(String file, byte[] content) {
        try {
            Files.write(Path.of(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A client home with {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private static Path client(Path client) throws IOException {
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
