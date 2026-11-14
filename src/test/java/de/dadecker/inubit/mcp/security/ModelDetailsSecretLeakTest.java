package de.dadecker.inubit.mcp.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliLibraryRunner;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81ModelDetailAdapter;
import de.dadecker.inubit.mcp.application.ArchiveExportAudit;
import de.dadecker.inubit.mcp.application.ArchiveExportService;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter;
import de.dadecker.inubit.mcp.application.ModelDetailService;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.ResultLimiter;
import de.dadecker.inubit.mcp.application.TargetFolderRules;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.mcp.tools.ExportArchivesTool;
import de.dadecker.inubit.mcp.mcp.tools.ReadModelDetailsTool;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestClientSelection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * T043 (feature 022, US3 AS2, SC-005, FR-010, research D-19, Constitution II):
 * {@code read_model_details} over MCP on the real {@link ModelDetailService}, the real
 * {@link V81ModelDetailAdapter} against WireMock and the real {@link CliLibraryRunner} on scripted
 * StartCLI runs, composed as {@code V81PortSet} does.
 *
 * <ul>
 *   <li>Module properties: the recorded {@code admin-model-node-by-model.xml} (T039) carries a
 *       secret under a {@code type="Password"} property ({@link #TYPE_SECRET}) and one under a
 *       secret name ({@code proxyPassword}, {@link #NAME_SECRET}); the served body also plants a
 *       credential inside the {@code dbURL} value ({@link #URL_SECRET}), which D-19 masks in part.
 *   <li>Errors: an unknown module id, other {@code 500} answers and an unreadable {@code 200}
 *       carrying the module XML and the node's password, and failed library runs echoing the
 *       password and printing {@code password=}{@link #CLI_SECRET}.
 *   <li>A successful library run (exit code 0, no {@code n-NOK}) whose lines carry
 *       {@link #LIST_SECRET}: each line is listed with the free-text masker's placeholder.
 *   <li>None of the secrets appears in a result (JSON and decoded text), an error text or
 *       excerpt, or a captured log line (TRACE); the tool writes no audit record.
 * </ul>
 *
 * <p>T044 adds the {@code export_archives} forms {@code ownerWithoutGroups} and {@code metadata}
 * on the real {@link ArchiveExportService} and {@link CliExportRunner}: an owner export whose
 * module file holds {@link #ARCHIVE_SECRET} in clear text (as INUBIT exports it) is written to the
 * folder, but the secret appears in no result, error, log line or audit record, also when a
 * second call is refused (an existing file of the same name, before any StartCLI run); failed
 * StartCLI runs of both forms echo the password and a secret-named value. The user column follows in T045b.
 */
@Timeout(60)
class ModelDetailsSecretLeakTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final String USER = "jdoe";
    private static final String DIAGRAM = "Workflow-0301";
    private static final String MODULE_PATH = "/ibis/rest/model/nodeByModel/" + DIAGRAM + "/100";

    /** The node's configured password (INUBIT login over REST and StartCLI). */
    private static final String PASSWORD = "model-leak-password-0431";
    /** The value of the recorded {@code type="Password"} property. */
    private static final String TYPE_SECRET = "synthetic-secret-3901";
    /** The value of the recorded secret-named property {@code proxyPassword}. */
    private static final String NAME_SECRET = "synthetic-secret-3902";
    /** The password planted inside the recorded {@code dbURL} value. */
    private static final String URL_SECRET = "synthetic-secret-4301";
    /** A secret value StartCLI prints under a secret name. */
    private static final String CLI_SECRET = "synthetic-secret-4302";
    /** A secret inside a line of a successful library list (research D-19). */
    private static final String LIST_SECRET = "synthetic-secret-4303";
    /** A clear-text module secret inside the owner export archive (T044). */
    private static final String ARCHIVE_SECRET = "synthetic-secret-4401";
    private static final List<String> SECRETS = List.of(PASSWORD, TYPE_SECRET, NAME_SECRET,
        URL_SECRET, CLI_SECRET, LIST_SECRET, ARCHIVE_SECRET);

    private static final String RECORDED_URL =
        "jdbc:postgresql://inubit-db-01.example.test:5432/demodb";
    /** The recorded module with {@link #URL_SECRET} planted in its {@code dbURL} value. */
    private static final String PLANTED_MODULE = RestFixtures.text("admin-model-node-by-model.xml")
        .replace(RECORDED_URL, RECORDED_URL + "?user=demo_reader&amp;password=" + URL_SECRET
            + "&amp;ssl=true");

    /** The US3 rows of interface-coverage.tsv and the export rows, reachable on 8.1. */
    private static final String COVERAGE = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        REST\tGET /model/nodeByModel/{model}/{node}\tREAD\tE\tUS3\treachable:read_model_details/module\tplanned\t-
        CLI\tlibrary --list\tREAD\tC\tUS3\treachable:read_model_details/libraries\tplanned\t-
        CLI\tlibrary --type\tREAD\tC\tUS3\treachable:read_model_details/libraries\tplanned\t-
        CLI\texport --exportWorkflowUser\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowType\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowGroup\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportFile\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowGroupExclude\tEXPORT\tE\tUS3\treachable:export_archives/ownerWithoutGroups\tplanned\t-
        CLI\texport --exportMetadata\tEXPORT\tE\tUS3\treachable:export_archives/metadata\tplanned\t-
        CLI\texport --exportMetadataNames\tEXPORT\tE\tUS3\treachable:export_archives/metadata\tplanned\t-
        """;

    private static WireMockServer wireMock;

    @TempDir
    Path cliHome;
    @TempDir(factory = GitFreeTempDir.class)
    Path exportRoot;

    private final SecretScrubber scrubber = SecretScrubber.global();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<InubitHttpClient> clients = new ArrayList<>();
    private Level previousLevel;
    private McpTestClient client;
    private VersionGate gate;
    /** The audit records of the export_archives calls (T044). */
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    /** The scripted StartCLI of the next library run. */
    private FakeProcessLauncher launcher = FakeProcessLauncher.replaying(
        "admin-model-library-driver");

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        FakeStartCli.install(cliHome);
        // as the production wiring registers each node's password with the global scrubber
        scrubber.register(PASSWORD);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);

        NodeVersions versions = new NodeVersions(Clock.systemUTC(),
            Map.of(DEV1, VersionSetting.V8_1)::get, node -> Duration.ofMinutes(10));
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, new ClientVersion(
            InubitVersion.parse("8.1.12").orElseThrow(), InubitVersion.parse("8.1.0"),
            InubitVersion.parse("8.1.99"))));
        gate = new VersionGate(versions, new FakeVersionDetection(versions),
            everythingSupported(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(DEV1::equals), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(COVERAGE)));
        client = McpTestClient.start(List.of(new ReadModelDetailsTool(new ModelDetailService(
            new TargetResolver(List.of(DEV1)), gate, node -> adapter()))), scrubber);
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        clients.forEach(InubitHttpClient::close);
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    @Test
    void theServedModuleCarriesThePlantedSecrets() {
        assertThat(PLANTED_MODULE).contains(">" + TYPE_SECRET + "<", ">" + NAME_SECRET + "<",
            "password=" + URL_SECRET, "name=\"Password\" type=\"Password\"",
            "name=\"proxyPassword\"", "name=\"dbURL\"");
    }

    /**
     * AS2: the property of type {@code Password} and the secret-named one are masked as a whole
     * and counted; the credential inside {@code dbURL} is replaced by its placeholder while the
     * rest of the URL stays, and is not counted (D-19).
     */
    @Test
    void theModuleMasksSecretsByNameByTypeAndInsideAValue() {
        stub(aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
            .withBody(PLANTED_MODULE));

        JsonNode result = call(Map.of("target", "dev/node1", "action", "module",
            "diagram", DIAGRAM, "moduleId", "100"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode module = result.path("structuredContent").path("module");
        assertThat(property(module, "Password").path("masked").asBoolean()).isTrue();
        assertThat(property(module, "proxyPassword").path("masked").asBoolean()).isTrue();
        assertThat(property(module, "dbURL").path("value").asString())
            .startsWith(RECORDED_URL).contains("user=demo_reader", "password=${secret:");
        assertThat(module.path("maskedCount").asInt()).isEqualTo(2);
        assertNoSecret(List.of(result), List.of());
    }

    /**
     * An unknown module id, other {@code 500} answers and an unreadable {@code 200} that carry
     * the planted module and the node's password: neither the error results nor the adapter's
     * errors with their excerpts carry a secret.
     */
    @Test
    void restErrorsShowNoSecret() {
        String missing = RestFixtures.text("admin-model-node-by-model-missing.html");
        List<JsonNode> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (ResponseDefinitionBuilder answer : List.of(
            aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                .withBody(missing + " " + PASSWORD + " " + PLANTED_MODULE),
            aResponse().withStatus(500).withHeader("Content-Type", "text/html")
                .withBody(PLANTED_MODULE + " " + PASSWORD),
            aResponse().withStatus(500).withHeader("Content-Type", "application/xml")
                .withBody(PLANTED_MODULE),
            aResponse().withStatus(200).withHeader("Content-Type", "application/xml")
                .withBody(PLANTED_MODULE.replace("</ns2:Properties>", "<!-- " + PASSWORD
                    + " password=" + CLI_SECRET + " -->")))) {
            stub(answer);
            JsonNode result = call(Map.of("target", "dev/node1", "action", "module",
                "diagram", DIAGRAM, "moduleId", "100"));
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
            results.add(result);
            ToolError error = errorOf(() -> adapter().module(DIAGRAM, "100"));
            errors.add(reported(error) + "\n" + error.excerpt().orElse(""));
        }

        assertThat(texts(results.get(0))).contains("NOT_FOUND");
        assertNoSecret(results, errors);
    }

    /**
     * Failed library runs that echo the password and print a secret-named value: neither the
     * error results with their excerpts nor the runner's errors carry a secret.
     */
    @Test
    void failedLibraryRunsShowNoSecret() {
        launcher = FakeProcessLauncher.replaying("admin-model-library-driver");
        JsonNode read = call(Map.of("target", "dev/node1", "action", "libraries",
            "libraryType", "driver"));
        assertThat(read.path("isError").asBoolean()).as(read.toString()).isFalse();

        List<JsonNode> results = new ArrayList<>(List.of(read));
        List<String> errors = new ArrayList<>();
        String planted = "password=" + CLI_SECRET + " for " + PASSWORD;
        for (FakeProcessLauncher failure : List.of(
            FakeProcessLauncher.of("Password: " + PASSWORD + "\nERROR " + planted + "\n",
                "java.lang.IllegalStateException: " + planted + "\n", 1),
            FakeProcessLauncher.of("Password: " + PASSWORD + "\n2-NOK: " + planted + "\n", "",
                0))) {
            launcher = failure;
            JsonNode result = call(Map.of("target", "dev/node1", "action", "libraries"));
            assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
            assertThat(texts(result)).as("the classifier's excerpt is shown")
                .contains("\"excerpt\"");
            results.add(result);
            ToolError error = errorOf(() -> runner().list("driver"));
            errors.add(reported(error) + "\n" + error.excerpt().orElse(""));
        }

        assertNoSecret(results, errors);
    }

    /**
     * A successful run whose lines carry a secret: every line is still listed as one file name,
     * the secret replaced by its placeholder (research D-19), and it appears in no result or log.
     */
    @Test
    void aSecretInsideASuccessfulLibraryListIsMasked() {
        launcher = FakeProcessLauncher.of("JAVA_HOME is set\nPassword: \nojdbc11.jar\npassword="
            + LIST_SECRET + "\nx;pwd=" + LIST_SECRET + ".jar\n", "", 0);

        JsonNode result = call(Map.of("target", "dev/node1", "action", "libraries",
            "libraryType", "driver"));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        JsonNode items = result.path("structuredContent").path("page").path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("file").asString()).isEqualTo("ojdbc11.jar");
        assertThat(items.get(1).path("file").asString()).contains("${secret:");
        assertThat(items.get(2).path("file").asString()).startsWith("x;").contains("${secret:");
        assertNoSecret(List.of(result), List.of());
    }

    /**
     * SC-005's audit channel: read_model_details is a read, and neither the tool nor its service
     * takes an {@link AuditPort}, so it writes no audit record that could carry a secret.
     */
    @Test
    void theReadWritesNoAuditRecord() {
        for (Class<?> type : List.of(ReadModelDetailsTool.class, ModelDetailService.class)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertThat(constructor.getParameterTypes()).as(constructor.toString())
                    .doesNotContain(AuditPort.class);
            }
        }
    }

    /**
     * T044: the {@code export_archives} forms. A successful owner export writes the archive with
     * its clear-text module secret, which no result, log line or audit record repeats; the same
     * call refused for the existing file before StartCLI runs, and failed runs of both forms
     * (into another folder) that echo the password and a secret-named value, carry no secret
     * either.
     */
    @Test
    void theExportFormsShowNoSecretInResultsErrorsLogsOrAudit() throws IOException {
        Path folder = exportRoot.resolve("archives");
        Map<String, Object> owner = Map.of("target", "dev/node1", "content",
            "ownerWithoutGroups", "owner", USER, "excludeGroups", List.of("GRP-01"),
            "targetFolder", folder.toString(), "acknowledgeClearTextSecrets", true);
        Map<String, Object> failedOwner = new HashMap<>(owner);
        failedOwner.put("targetFolder", exportRoot.resolve("failed").toString());
        Map<String, Object> metadata = Map.of("target", "dev/node1", "content", "metadata",
            "metadataNames", List.of("Example1"), "targetFolder",
            exportRoot.resolve("failed").toString());
        byte[] archive = ownerExportWithSecret();
        List<JsonNode> results = new ArrayList<>();
        try (McpTestClient exports = McpTestClient.start(
            List.of(new ExportArchivesTool(exportService())), scrubber)) {
            exports.initialize();

            launcher = FakeProcessLauncher.replaying("admin-model-export-group-exclude")
                .onLaunch(spec -> write(spec, archive));
            JsonNode written = exports.callTool("export_archives", owner);
            assertThat(written.path("isError").asBoolean()).as(written.toString()).isFalse();
            assertThat(new String(Files.readAllBytes(folder.resolve(USER + ".workflows.zip")),
                StandardCharsets.ISO_8859_1)).as("the archive itself holds the secret")
                .contains(ARCHIVE_SECRET);
            results.add(written);

            List<ProcessLauncher.LaunchSpec> launched = new CopyOnWriteArrayList<>();
            launcher = FakeProcessLauncher.replaying("admin-model-export-group-exclude")
                .onLaunch(launched::add);
            JsonNode collision = exports.callTool("export_archives", owner);
            assertThat(collision.path("isError").asBoolean()).as(collision.toString()).isTrue();
            assertThat(launched).as("refused before StartCLI").isEmpty();
            results.add(collision);

            String planted = "password=" + CLI_SECRET + " for " + PASSWORD;
            for (Map<String, Object> arguments : List.of(failedOwner, metadata)) {
                for (FakeProcessLauncher failure : List.of(
                    FakeProcessLauncher.of("Password: " + PASSWORD + "\nERROR " + planted + "\n",
                        "java.lang.IllegalStateException: " + planted + "\n", 1),
                    FakeProcessLauncher.of("Password: " + PASSWORD + "\n2-NOK: " + planted
                        + "\n", "", 0))) {
                    launcher = failure;
                    JsonNode result = exports.callTool("export_archives", arguments);
                    assertThat(result.path("isError").asBoolean()).as(result.toString())
                        .isTrue();
                    results.add(result);
                }
            }
        }

        assertThat(audit).extracting(AuditRecord::outcome).contains(AuditOutcome.EXECUTED,
            AuditOutcome.REFUSED, AuditOutcome.FAILED);
        assertNoSecret(results, audit.stream().map(AuditRecord::toString).toList());
    }

    // --- support -------------------------------------------------------------------------------

    /** {@code export_archives} on the real service, adapter and runner of the node (T044). */
    private ArchiveExportService exportService() {
        return new ArchiveExportService(new TargetResolver(List.of(DEV1)),
            node -> Optional.of(USER), node -> Optional.of(USER), node -> false, node -> false,
            new TargetFolderRules(exportRoot.resolve("home"), exportRoot.resolve("workspace"),
                exportRoot.resolve("audit"), List.of(exportRoot.resolve("config")), Map.of(),
                letter -> false, false),
            node -> new V81ArtifactAdapter(exports(), () -> { }), new V81WorkflowArchives(), gate,
            new ArchiveFolderWriter(), ResultLimiter.withDefaults(),
            new ArchiveExportAudit.Dependencies(audit::add,
                new MutableClock(Instant.parse("2026-11-14T10:00:00Z")), "acme",
                UUID::randomUUID));
    }

    private CliExportRunner exports() {
        NodeCredentials credentials = new NodeCredentials(DEV1,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliExportRunner(server(), credentials,
            new CredentialGuard(storeLabels("INUBIT", DEV1),
                new MutableClock(Instant.parse("2026-11-14T08:00:00Z"))),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV1), false));
    }

    /** Writes {@code zip} to the launch's {@code --exportFile}, as StartCLI does. */
    private static void write(ProcessLauncher.LaunchSpec spec, byte[] zip) {
        try {
            Files.write(ScriptedProcessLauncher.exportFile(spec), zip);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The layout of the T039 owner export with a module file holding a clear-text secret. */
    private static byte[] ownerExportWithSecret() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(Deflater.NO_COMPRESSION); // the secret stays readable in the bytes
            for (Map.Entry<String, String> entry : Map.of(
                "archive.properties", "sourceVersion=8.1.17\n",
                "module/module.xml", "<Modules/>",
                "module/Demo-DB-Probe.xml", "<Property name=\"Password\">" + ARCHIVE_SECRET
                    + "</Property>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private JsonNode call(Map<String, Object> arguments) {
        return client.callTool("read_model_details", arguments);
    }

    private static JsonNode property(JsonNode module, String name) {
        for (JsonNode property : module.path("properties")) {
            if (property.path("name").asString().equals(name)) {
                return property;
            }
        }
        throw new AssertionError("no property " + name + " in " + module);
    }

    private static void stub(ResponseDefinitionBuilder answer) {
        wireMock.stubFor(get(urlPathEqualTo(MODULE_PATH)).willReturn(answer));
    }

    private static ToolError errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (ToolErrorException e) {
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    /** What a tool error hands on from {@code error}: code and message, cause and next step. */
    private static String reported(ToolError error) {
        return String.join("\n", error.code() + ": " + error.message(), error.likelyCause(),
            error.nextStep());
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().id(DEV1.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).timeout(Duration.ofSeconds(10))
            .cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17")).build();
    }

    /** The production composition of {@code V81PortSet}: REST client and library runner. */
    private V81ModelDetailAdapter adapter() {
        InubitHttpClient http = new InubitHttpClient(server(),
            Optional.of(new InubitHttpClient.Credentials(USER, scrubber.register(PASSWORD))),
            Optional.empty(), MaintenanceProbe.NONE, scrubber);
        synchronized (clients) {
            clients.add(http);
        }
        return new V81ModelDetailAdapter(DEV1, http, runner()::list);
    }

    private CliLibraryRunner runner() {
        NodeCredentials credentials = new NodeCredentials(DEV1,
            Optional.of(new SourcedValue<>(USER, "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliLibraryRunner(server(), credentials,
            new CredentialGuard(storeLabels("INUBIT", DEV1),
                new MutableClock(Instant.parse("2026-11-14T08:00:00Z"))),
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(scrubber, storeLabels("INUBIT", DEV1), false));
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }

    // --- the checks ----------------------------------------------------------------------------

    /**
     * No secret in the rendered {@code results} (JSON and decoded text), in the {@code errors}
     * texts or in a captured log line (SC-005).
     */
    private void assertNoSecret(List<JsonNode> results, List<String> errors) {
        String result = results.stream().map(each -> each.toString() + "\n" + texts(each))
            .collect(Collectors.joining("\n"));
        String error = String.join("\n", errors);
        String log = logText();
        for (String secret : SECRETS) {
            assertThat(result).as("result: " + secret).doesNotContain(secret);
            assertThat(error).as("error: " + secret).doesNotContain(secret);
            assertThat(log).as("log: " + secret).doesNotContain(secret);
        }
    }

    /** Every text value and property name of {@code node}, decoded. */
    private static String texts(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collect(node, text);
        return text.toString();
    }

    private static void collect(JsonNode node, StringBuilder text) {
        if (node.isString()) {
            text.append(node.asString()).append('\n');
        }
        node.forEach(inner -> collect(inner, text));
        node.propertyNames().forEach(name -> text.append(name).append('\n'));
    }

    private String logText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }
}
