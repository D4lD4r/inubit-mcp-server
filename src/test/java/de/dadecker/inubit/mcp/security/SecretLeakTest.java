package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.git.GitCli;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.WorkspaceService;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.ExportArtifactsTool;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * T024 (SC-003, FR-025): every fixture is exported through {@code export_artifacts} with the
 * production adapters (StartCLI faked); no synthetic secret value of
 * {@code fixtures/v8_1/artifacts/README.md} occurs in the workspace files, {@code .meta/}, any
 * git object, the tool results or the log output, and the private export directory is gone.
 */
@Timeout(120)
class SecretLeakTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    /** Archive by the quoted name in the StartCLI command; every fixture export. */
    private static final Map<String, String> EXPORTS = Map.of(
        "'GRP-01'", "grp-a.zip", "'GRP-02'", "grp-b.zip",
        "'Module-0023'", "module-one.zip", "'Module-0029'", "module-smime.zip");

    @TempDir
    Path workspace;
    @TempDir
    Path cliHome;

    private final List<String> toolResults = new ArrayList<>();
    private final List<Path> exportDirectories = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private McpTestClient client;

    @BeforeEach
    void setUp() throws IOException {
        captureLogs();
        Files.writeString(Files.createDirectories(cliHome.resolve("bin"))
            .resolve("startcli.sh"), "#!/bin/sh\n");
        FakeProcessLauncher startCli = FakeProcessLauncher.of(
            "1-OK: Artifacts exported successfully.\n", "", 0).onLaunch(spec -> {
                String command = spec.command().get(spec.command().indexOf("--execCommand") + 1);
                Matcher file = EXPORT_FILE.matcher(command);
                assertThat(file.find()).isTrue();
                String fixture = EXPORTS.entrySet().stream()
                    .filter(entry -> command.contains(entry.getKey())).findFirst()
                    .orElseThrow().getValue();
                exportDirectories.add(Path.of(file.group(1)).getParent());
                try {
                    Files.write(Path.of(file.group(1)), ArtifactFixtures.bytes(fixture));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("leak-test-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        ArtifactPort artifacts = new V81ArtifactAdapter(new CliExportRunner(TestNodeConfig.node()
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials,
            new CredentialGuard(new CredentialVariables("INUBIT", DEV),
                new MutableClock(Instant.parse("2026-10-06T08:00:00Z"))),
            new CliRunner(startCli, Map.of("PATH", "/usr/bin"), false, new CliResources("acme")),
            new CliOutputClassifier(new SecretScrubber())), () -> { });
        WorkspaceService service = new WorkspaceService(workspace, new GitCli(workspace, "acme",
            new SystemProcessLauncher(), Map.of("PATH",
                System.getenv().getOrDefault("PATH", "/usr/bin"))),
            new ArchiveCodec(), node -> artifacts, node -> {
                throw new AssertionError("every module names its plugin type");
            });
        client = McpTestClient.start(List.of(new ExportArtifactsTool(service,
            new TargetResolver(List.of(DEV)), node -> Optional.of("OWNERS"),
            new ResultLimiter(3, ResultLimiter.DEFAULT_MAX_CHARS))));
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    private void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
    }

    private void export(Map<String, Object> arguments) {
        JsonNode result = client.callTool("export_artifacts", arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        toolResults.add(result.toString());
    }

    @Test
    void noSyntheticSecretLeavesTheExport() throws IOException, InterruptedException {
        export(Map.of("target", "dev", "owner", "jdoe", "diagramGroups", List.of("GRP-01")));
        export(Map.of("target", "dev", "diagramGroups", List.of("GRP-02")));
        export(Map.of("target", "dev", "modules", List.of(
            Map.of("name", "Module-0023", "pluginType", "XSLT Converter"),
            Map.of("name", "Module-0029", "pluginType", "SMIME"))));

        List<SyntheticSecret> secrets = ArtifactFixtures.syntheticSecrets();
        assertThat(secrets).hasSizeGreaterThan(40);
        assertThat(secrets).extracting(SyntheticSecret::fixture)
            .allMatch(EXPORTS::containsValue);
        Map<String, String> sources = new LinkedHashMap<>();
        sources.putAll(workspaceFiles());
        sources.put("git objects", gitObjects());
        sources.put("tool results", String.join("\n", toolResults));
        sources.put("logs", logText());
        assertThat(sources).containsKey("git objects").hasSizeGreaterThan(50);
        assertThat(sources.keySet()).anyMatch(path -> path.startsWith(".meta/"));

        List<String> leaks = new ArrayList<>();
        for (SyntheticSecret secret : secrets) {
            sources.forEach((source, text) -> {
                if (text.contains(secret.value())) {
                    leaks.add(secret.kind() + " " + secret.location() + " in " + source);
                }
            });
        }
        assertThat(leaks).as("synthetic secrets found (names only)").isEmpty();
        assertThat(sources.get("git objects")).contains("${secret:");
        assertThat(exportDirectories).hasSize(4)
            .allSatisfy(directory -> assertThat(directory).as("deleted").doesNotExist());
    }

    /** Every file of the workspace (outside {@code .git}), as text. */
    private Map<String, String> workspaceFiles() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(workspace)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = workspace.relativize(file).toString();
                if (!relative.startsWith(".git/")) {
                    files.put(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    /** The content of every object of the history ({@code git cat-file --batch-all-objects}). */
    private String gitObjects() throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "-C", workspace.toString(), "cat-file",
            "--batch-all-objects", "--batch").redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = git.getInputStream()) {
            in.transferTo(out);
        }
        assertThat(git.waitFor()).isZero();
        return out.toString(StandardCharsets.UTF_8);
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
}
