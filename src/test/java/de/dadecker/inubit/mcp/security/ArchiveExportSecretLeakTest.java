package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticKeystore;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Feature 015 T021 (SC-004, FR-001, FR-003, Constitution II), modelled on {@link SecretLeakTest}:
 * every fixture diagram group is exported through {@code export_archives} with the production
 * adapters ({@link TestWiring}, a scripted StartCLI that writes the recorded exports) into a
 * local folder. No synthetic secret value of {@code fixtures/v8_1/artifacts/README.md} and no
 * synthetic keystore file (raw, base64 or in its stored module form) occurs in the tool results, the tool errors (a refused
 * and a failed call included), the log output (our loggers at TRACE) or the audit files; the
 * archives themselves do contain them. The private export directories are gone, and the workspace
 * has no new file and no new history entry.
 */
@Timeout(120)
class ArchiveExportSecretLeakTest {

    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static final String USER = "jdoe";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final String password = "archive-leak-pw-" + UUID.randomUUID();
    private final List<Path> exportDirectories = new CopyOnWriteArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    /** The export StartCLI writes, by the quoted diagram group of the command. */
    private final Map<String, byte[]> exports = new LinkedHashMap<>();
    private Level previousLevel;
    private Level previousSdkLevel;
    private Path workspace;
    private WireMockServer server;
    private TestWiring wiring;
    private McpTestClient client;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        captureLogs();
        // the REST login check and the version detection before StartCLI
        server = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        server.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        workspace = Files.createDirectories(temp.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "workspace of acme\n");
        git("init", "-q");
        git("add", "README.md");
        git("-c", "user.name=jdoe", "-c", "user.email=jdoe@example.test", "commit", "-q", "-m",
            "seed");
        Path cliHome = temp.resolve("cli");
        de.dadecker.inubit.mcp.testing.FakeStartCli.install(cliHome);
        exports.put("'GRP-01'", ArtifactFixtures.bytes("grp-a.zip"));
        exports.put("'GRP-02'", ArtifactFixtures.bytes("grp-b.zip"));
        String yaml = """
            profile:
              name: acme
            workspace: '%s'
            auditDirectory: '%s'
            groups:
              - name: dev
                cli:
                  home: '%s'
                  javaHome: '%s'
                inventory:
                  owner: OWNERS
                tls:
                  trustStore: '%s'
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
            """.formatted(workspace, temp.resolve("audit"), cliHome,
            System.getProperty("java.home"), TestCertificates.get().trustStore(),
            server.httpsPort());
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        Map<String, String> environment = Map.of("INUBIT_ACME_DEV_USERNAME", USER,
            "INUBIT_ACME_DEV_PASSWORD", password);
        SecretScrubber scrubber = new SecretScrubber();
        wiring = TestWiring.of(config, TestSecrets.store(environment), scrubber, Files::exists,
            de.dadecker.inubit.mcp.testing.Platform.windows(), startCli(),
            Map.of("PATH", de.dadecker.inubit.mcp.testing.Platform.searchPath()));
        client = McpTestClient.start(wiring.toolHandlers(), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        if (server != null) {
            server.stop();
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
        context.getLogger("io.modelcontextprotocol").setLevel(previousSdkLevel);
    }

    private void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
        Logger sdk = context.getLogger("io.modelcontextprotocol");
        previousSdkLevel = sdk.getLevel();
        sdk.setLevel(Level.DEBUG);
    }

    /**
     * The scripted StartCLI: {@code -v} answers the recorded client version; an export writes
     * {@link #exports} of the command's diagram group into the private export file.
     */
    private ProcessLauncher startCli() {
        return spec -> {
            List<String> command = spec.startCliArguments();
            if (!command.contains("--execCommand")) {
                return FakeProcessLauncher.replaying("version").launch(spec);
            }
            String exec = command.get(command.indexOf("--execCommand") + 1);
            return FakeProcessLauncher.of("1-OK: Workflow group exported successfully.\n", "", 0)
                .onLaunch(launched -> {
                    Matcher file = EXPORT_FILE.matcher(exec);
                    assertThat(file.find()).as(exec).isTrue();
                    byte[] export = exports.entrySet().stream()
                        .filter(entry -> exec.contains(entry.getKey())).findFirst()
                        .orElseThrow().getValue();
                    exportDirectories.add(Path.of(file.group(1)).getParent());
                    try {
                        Files.write(Path.of(file.group(1)), export);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).launch(spec);
        };
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("export_archives", arguments, Duration.ofSeconds(60));
    }

    private Map<String, Object> arguments(String diagramGroup, String folder) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "dev");
        arguments.put("diagramGroup", diagramGroup);
        arguments.put("targetFolder", temp.resolve(folder).toString());
        arguments.put("acknowledgeClearTextSecrets", true);
        return arguments;
    }

    @Test
    void noSyntheticSecretLeavesTheArchives() throws IOException, InterruptedException {
        String history = git("log", "--all", "--format=%H");
        String objects = git("cat-file", "--batch-all-objects", "--batch-check");
        Map<String, String> workspaceBefore = workspaceFiles();
        List<String> results = new ArrayList<>();

        Map<String, Object> personal = arguments("GRP-01", "archives-a");
        personal.put("owner", USER);
        JsonNode grpA = call(personal);
        Map<String, Object> group = arguments("GRP-02", "archives-b");
        group.put("reason", "backup before the test");
        JsonNode grpB = call(group);
        Map<String, Object> unacknowledged = arguments("GRP-02", "archives-c");
        unacknowledged.put("acknowledgeClearTextSecrets", false);
        JsonNode staticRefusal = call(unacknowledged);
        JsonNode existingFiles = call(arguments("GRP-02", "archives-b"));
        exports.put("'GRP-02'", brokenExport());
        JsonNode failed = call(arguments("GRP-02", "archives-c"));
        for (JsonNode result : List.of(grpA, grpB, staticRefusal, existingFiles, failed)) {
            results.add(result.toString());
        }

        assertThat(grpA.path("isError").asBoolean()).as(grpA.toString()).isFalse();
        assertThat(grpB.path("isError").asBoolean()).as(grpB.toString()).isFalse();
        assertThat(grpB.path("structuredContent").path("archives")).hasSize(4);
        assertThat(errorCode(staticRefusal)).isEqualTo("INVALID_INPUT");
        assertThat(errorCode(existingFiles)).isEqualTo("INVALID_INPUT");
        assertThat(errorCode(failed)).isEqualTo("UNEXPECTED_RESPONSE");
        String audit = auditText();
        assertThat(audit).contains("EXECUTED", "REFUSED", "FAILED");

        String logs = logText();
        assertThat(logs).as("the log capture is live: our loggers and the SDK's")
            .contains("export_archives on dev/node1: EXECUTED", "Received JSON message");

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("tool results and errors", String.join("\n", results));
        sources.put("logs", logs);
        sources.put("audit", audit);
        List<SyntheticSecret> secrets = ArtifactFixtures.syntheticSecrets().stream()
            .filter(secret -> secret.fixture().startsWith("grp-")).toList();
        assertThat(secrets).hasSizeGreaterThan(40);
        List<SyntheticKeystore> keystores = ArtifactFixtures.syntheticKeystores();
        assertThat(keystores).isNotEmpty();
        String archives = archiveText(temp.resolve("archives-a"))
            + archiveText(temp.resolve("archives-b"));

        List<String> leaks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (SyntheticSecret secret : secrets) {
            String name = secret.kind() + " " + secret.location();
            sources.forEach((source, text) -> {
                if (text.contains(secret.value())) {
                    leaks.add(name + " in " + source);
                }
            });
            if (!archives.contains(secret.value())) {
                missing.add(name);
            }
        }
        for (SyntheticKeystore keystore : keystores) {
            String raw = new String(keystore.bytes(), StandardCharsets.ISO_8859_1);
            String base64 = Base64.getEncoder().encodeToString(keystore.bytes());
            String stored = storedForm(keystore);
            sources.forEach((source, text) -> {
                if (text.contains(raw) || text.contains(base64) || text.contains(stored)) {
                    leaks.add("keystore " + keystore.file() + " in " + source);
                }
                if (text.contains(keystore.storePassword())) {
                    leaks.add("store password of " + keystore.file() + " in " + source);
                }
            });
            if (!archives.contains(stored)) {
                missing.add("keystore " + keystore.file());
            }
        }
        String credentials = Base64.getEncoder().encodeToString((USER + ":" + password)
            .getBytes(StandardCharsets.UTF_8));
        sources.forEach((source, text) -> {
            if (text.contains(password) || text.contains(credentials)) {
                leaks.add("the node password in " + source);
            }
        });
        assertThat(leaks).as("synthetic secrets found (names only)").isEmpty();
        assertThat(missing).as("synthetic secrets the archives lack (names only)").isEmpty();

        assertThat(exportDirectories).hasSize(4)
            .allSatisfy(directory -> assertThat(directory).as("deleted").doesNotExist());
        assertThat(files(temp.resolve("archives-c"))).as("nothing written").isEmpty();
        assertThat(workspaceFiles()).as("workspace files").isEqualTo(workspaceBefore);
        assertThat(git("log", "--all", "--format=%H")).as("history").isEqualTo(history);
        assertThat(git("cat-file", "--batch-all-objects", "--batch-check"))
            .as("git objects").isEqualTo(objects);
    }

    private static String errorCode(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        String text = result.path("content").get(0).path("text").asString();
        Matcher code = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"").matcher(text);
        assertThat(code.find()).as(text).isTrue();
        return code.group(1);
    }

    /**
     * {@code grp-b.zip} whose {@code module/module-0028.xml} ends right after its first synthetic
     * secret: not well-formed, so the call fails after the export with the value in memory.
     */
    private static byte[] brokenExport() {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        String module = "module/module-0028.xml";
        String text = new String(entries.get(module), StandardCharsets.ISO_8859_1);
        SyntheticSecret secret = ArtifactFixtures.syntheticSecrets().stream()
            .filter(candidate -> candidate.location().startsWith(module)
                && text.contains(candidate.value()))
            .findFirst().orElseThrow();
        int end = text.indexOf(secret.value()) + secret.value().length();
        entries.put(module, text.substring(0, end).getBytes(StandardCharsets.ISO_8859_1));
        return ArtifactFixtures.zip(entries);
    }

    /**
     * The keystore as the export carries it: the raw file in the {@code Repository.zip}, or the
     * module property's content (base64 of the gzipped file); checked to decode to the file.
     */
    private static String storedForm(SyntheticKeystore keystore) throws IOException {
        String[] location = keystore.location().split(" ");
        if (location[0].equals("Repository.zip")) {
            return new String(keystore.bytes(), StandardCharsets.ISO_8859_1);
        }
        String module = new String(ArtifactFixtures.entries(keystore.fixture()).get(location[0]),
            StandardCharsets.ISO_8859_1);
        Matcher property = Pattern.compile("<Property name=\"" + Pattern.quote(location[1])
            + "\"[^>]*>([^<]+)</Property>").matcher(module);
        assertThat(property.find()).as(keystore.location()).isTrue();
        byte[] decoded = Base64.getMimeDecoder().decode(property.group(1));
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
            assertThat(in.readAllBytes()).as(keystore.file()).isEqualTo(keystore.bytes());
        }
        return property.group(1);
    }

    /** The text of every entry of the archives in {@code folder}, nested ZIPs unpacked. */
    private static String archiveText(Path folder) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path file : files(folder)) {
            assertThat(file.getFileName().toString()).endsWith(".diagram.zip");
            text.append(zipText(Files.readAllBytes(file)));
        }
        return text.toString();
    }

    private static String zipText(byte[] zip) {
        StringBuilder text = new StringBuilder();
        ArtifactFixtures.entries(zip).forEach((name, bytes) -> text.append(
            name.endsWith(".zip") ? zipText(bytes) : new String(bytes,
                StandardCharsets.ISO_8859_1)).append('\n'));
        return text.toString();
    }

    private static List<Path> files(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** Every file of the workspace outside {@code .git}, by relative path, as text. */
    private Map<String, String> workspaceFiles() throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (Path file : files(workspace)) {
            String relative = workspace.relativize(file).toString();
            if (!relative.startsWith(".git")) {
                files.put(relative, Files.readString(file, StandardCharsets.ISO_8859_1));
            }
        }
        return files;
    }

    private String auditText() throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path file : files(temp.resolve("audit"))) {
            text.append(Files.readString(file, StandardCharsets.ISO_8859_1)).append('\n');
        }
        return text.toString();
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    /** Runs the system git in the workspace (no system or global configuration). */
    private String git(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", workspace.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process git = builder.start();
        git.getOutputStream().close();
        String out;
        try (InputStream in = git.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(git.waitFor()).as(String.join(" ", command) + ": " + out).isZero();
        return out;
    }
}
