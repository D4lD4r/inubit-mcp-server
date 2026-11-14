package de.dadecker.inubit.mcp.mcp.tools;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.assertMatchesOutputSchema;
import static de.dadecker.inubit.mcp.mcp.tools.DiagnosisToolFixture.toolError;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81WorkflowArchives;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.v81.V81ArtifactAdapter;
import de.dadecker.inubit.mcp.application.ArchiveExportAudit;
import de.dadecker.inubit.mcp.application.ArchiveExportService;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter;
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
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * T044 (feature 022, US3, research D-21): the {@code export_archives} {@code content} forms
 * {@code ownerWithoutGroups} and {@code metadata} over MCP, on the real
 * {@link ArchiveExportService}, {@link V81ArtifactAdapter} and {@link CliExportRunner} with a
 * scripted StartCLI that answers with the T039 recordings ({@code fixtures/v8_1/cli/
 * admin-model-export-*}): {@code 1-OK: Workflow group exported successfully.}, {@code 1-OK:
 * Metadata exported successfully.}, {@code 1-OK: Metadata Names exported successfully.} and
 * {@code 2-NOK: Missing export option.}
 *
 * <p>The coverage list is a fixture whose new rows are reachable on 8.1 and {@code planned} on
 * 9.0 (until T046 / T046a flip the production list), so each form is admitted per call. The
 * owner export of T039 is not committed (clear-text secrets); a synthetic archive of its layout
 * stands in for it. The folder rules and the production opt-in are those of {@code workflows}.
 */
@Timeout(60)
class ExportArchivesContentTest {

    private static final NodeId DEV1 = NodeId.parse("dev/node1");
    private static final NodeId TEST1 = NodeId.parse("test/node1");
    private static final NodeId PROD1 = NodeId.parse("prod/node1");
    private static final String OWNER = "OWNERS";
    private static final String PASSWORD = "S3cr3t-content-pw";

    /** The export rows of interface-coverage.tsv; the new forms reachable on 8.1 only. */
    private static final String COVERAGE = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\texport --exportWorkflowUser\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowType\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowGroup\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportFile\tEXPORT\tE\t-\treachable:export_archives/workflows\treachable:export_archives/workflows\t-
        CLI\texport --exportWorkflowGroupExclude\tEXPORT\tE\tUS3\treachable:export_archives/ownerWithoutGroups\tplanned\t-
        CLI\texport --exportMetadata\tEXPORT\tE\tUS3\treachable:export_archives/metadata\tplanned\t-
        CLI\texport --exportMetadataNames\tEXPORT\tE\tUS3\treachable:export_archives/metadata\tplanned\t-
        """;

    private static final Pattern OWNER_COMMAND = Pattern.compile(
        "export --exportWorkflowUser 'OWNERS' --exportWorkflowType 'technical'"
            + " --exportWorkflowGroup '' --exportWorkflowGroupExclude 'GRP-01,GRP 02'"
            + " --exportFile '[^']+/owner\\.zip'");
    private static final Pattern METADATA_COMMAND = Pattern.compile(
        "export --exportMetadata --exportFile '[^']+/metadata\\.zip'");
    /** T041 review: the names go quoted, unlike the unquoted list of the T039 recording. */
    private static final Pattern METADATA_NAMES_COMMAND = Pattern.compile(
        "export --exportMetadata --exportMetadataNames 'Example1,Example2'"
            + " --exportFile '[^']+/metadata\\.zip'");

    @TempDir(factory = GitFreeTempDir.class)
    Path tmp;
    @TempDir
    Path cliHome;

    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private final Set<NodeId> optIn = new HashSet<>();
    private final ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
    private McpTestClient client;

    @BeforeEach
    void setUp() throws IOException {
        FakeStartCli.install(cliHome);
        NodeVersions versions = new NodeVersions(new MutableClock(
            Instant.parse("2026-11-14T08:00:00Z")), Map.of(DEV1, VersionSetting.V8_1,
            TEST1, VersionSetting.V9_0, PROD1, VersionSetting.V8_1)::get,
            node -> Duration.ofMinutes(10));
        versions.clientProbed(DEV1, new ProbedClient(ClientKey.NONE, client("8.1")));
        versions.clientProbed(TEST1, new ProbedClient(ClientKey.NONE, client("9.0")));
        versions.clientProbed(PROD1, new ProbedClient(ClientKey.NONE, client("8.1")));
        VersionGate gate = new VersionGate(versions, new FakeVersionDetection(versions),
            everythingSupported(), (node, version, timeout) -> {
                throw new AssertionError("no client probe expected for " + node);
            }, TestClientSelection.of(node -> true), node -> Duration.ZERO,
            Terminology.DEFAULT, false, InterfaceCoverage.load(new StringReader(COVERAGE)));
        AuditPort auditPort = audit::add;
        ArchiveExportService service = new ArchiveExportService(
            new TargetResolver(List.of(DEV1, TEST1, PROD1)), node -> Optional.of(OWNER),
            node -> Optional.of("jdoe"), PROD1::equals, optIn::contains,
            new TargetFolderRules(tmp.resolve("home"), tmp.resolve("workspace"),
                tmp.resolve("audit"), List.of(tmp.resolve("config")), Map.of(),
                letter -> false, false),
            this::artifacts, new V81WorkflowArchives(), gate, new ArchiveFolderWriter(),
            ResultLimiter.withDefaults(), new ArchiveExportAudit.Dependencies(auditPort,
                new MutableClock(Instant.parse("2026-11-14T10:00:00Z")), "acme",
                UUID::randomUUID));
        client = McpTestClient.start(List.of(new ExportArchivesTool(service)));
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // --- ownerWithoutGroups ----------------------------------------------------------------------

    @Test
    void ownerWithoutGroupsWritesTheOwnersTechnicalWorkflowsAsOneArchive() throws IOException {
        byte[] export = ownerExport();
        cli.expect(OWNER_COMMAND).replying("admin-model-export-group-exclude")
            .writingExportFile(export);
        Path folder = tmp.resolve("owner");

        JsonNode result = client.callTool("export_archives", owner(folder));

        assertMatchesOutputSchema("export_archives", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("source").asString()).isEqualTo("CLI");
        assertThat(content.path("owner").asString()).isEqualTo(OWNER);
        assertThat(content.has("diagramGroup")).isFalse();
        assertThat(content.path("clearTextSecrets").asString())
            .startsWith("The archives contain the secrets of these workflows");
        JsonNode archive = content.path("archives").get(0);
        assertThat(content.path("archives")).hasSize(1);
        assertThat(archive.path("workflow").asString()).isEqualTo(OWNER);
        assertThat(archive.path("file").asString()).isEqualTo("OWNERS.workflows.zip");
        assertThat(archive.path("path").asString())
            .isEqualTo(folder.resolve("OWNERS.workflows.zip").toString());
        assertThat(archive.path("size").asLong()).isEqualTo(export.length);
        assertThat(Files.readAllBytes(folder.resolve("OWNERS.workflows.zip")))
            .as("written as exported, not sliced").isEqualTo(export);
        assertThat(folder.toFile().list()).containsExactly("OWNERS.workflows.zip");
        cli.verifyComplete();

        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(audit.get(1).inputs()).containsEntry("content", "ownerWithoutGroups")
            .containsEntry("owner", OWNER).containsEntry("excludeGroup.1", "GRP-01")
            .containsEntry("excludeGroup.2", "GRP 02")
            .containsEntry("file.1", "OWNERS.workflows.zip")
            .containsEntry("acknowledgeClearTextSecrets", "true")
            .doesNotContainKey("diagramGroup");
    }

    @Test
    void ownerWithoutGroupsNeedsTheAcknowledgement() {
        Map<String, Object> arguments = owner(tmp.resolve("owner"));
        arguments.remove("acknowledgeClearTextSecrets");

        JsonNode error = toolError(client.callTool("export_archives", arguments));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).contains("acknowledgeClearTextSecrets");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.REFUSED);
        assertThat(cli.launches()).isEmpty();
    }

    /** The recorded refusal of an export option missing (T039, without type and group). */
    @Test
    void theMissingExportOptionAnswerFailsTheCallWithNothingWritten() {
        cli.expect(OWNER_COMMAND).replying("admin-model-export-group-exclude-missing-option");
        Path folder = tmp.resolve("owner");

        JsonNode result = client.callTool("export_archives", owner(folder));

        JsonNode error = toolError(result);
        assertThat(error.path("code").asString()).isEqualTo("UNEXPECTED_RESPONSE");
        assertThat(error.path("excerpt").asString()).contains("2-NOK: Missing export option.");
        assertThat(audit).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(folder).doesNotExist();
        cli.verifyComplete();
    }

    // --- metadata --------------------------------------------------------------------------------

    @Test
    void metadataWritesTheWholeRegistryWithoutOwnerOrAcknowledgement() throws IOException {
        byte[] export = FakeProcessLauncher.fixture("admin-model-export-metadata.zip");
        cli.expect(METADATA_COMMAND).replying("admin-model-export-metadata")
            .writingExportFile(export);
        Path folder = tmp.resolve("metadata");

        JsonNode result = client.callTool("export_archives", Map.of("target", "dev/node1",
            "content", "metadata", "targetFolder", folder.toString()));

        assertMatchesOutputSchema("export_archives", result);
        JsonNode content = result.path("structuredContent");
        assertThat(content.path("source").asString()).isEqualTo("CLI");
        assertThat(content.has("owner")).isFalse();
        assertThat(content.has("clearTextSecrets")).as("the registry holds no module secrets")
            .isFalse();
        JsonNode archive = content.path("archives").get(0);
        assertThat(archive.path("workflow").asString()).isEqualTo("metadata registry");
        assertThat(archive.path("file").asString()).isEqualTo("metadata-registry.zip");
        assertThat(Files.readAllBytes(folder.resolve("metadata-registry.zip"))).isEqualTo(export);
        cli.verifyComplete();
        assertThat(audit.get(1).outcome()).isEqualTo(AuditOutcome.EXECUTED);
        assertThat(audit.get(1).inputs()).containsEntry("content", "metadata")
            .containsEntry("acknowledgeClearTextSecrets", "false")
            .doesNotContainKeys("owner", "diagramGroup");
    }

    @Test
    void metadataNamesGoQuotedAsOneCommaSeparatedList() throws IOException {
        byte[] export = FakeProcessLauncher.fixture("admin-model-export-metadata-names.zip");
        cli.expect(METADATA_NAMES_COMMAND).replying("admin-model-export-metadata-names")
            .writingExportFile(export);
        Path folder = tmp.resolve("metadata");

        JsonNode result = client.callTool("export_archives", Map.of("target", "dev/node1",
            "content", "metadata", "metadataNames", List.of("Example1", "Example2"),
            "targetFolder", folder.toString()));

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(cli.execCommands()).singleElement().asString()
            .contains("--exportMetadataNames 'Example1,Example2' --exportFile");
        assertThat(Files.readAllBytes(folder.resolve("metadata-registry.zip"))).isEqualTo(export);
        assertThat(audit.get(1).inputs()).containsEntry("metadataName.1", "Example1")
            .containsEntry("metadataName.2", "Example2");
    }

    // --- schema ----------------------------------------------------------------------------------

    @Test
    void theSchemaAllowsEachInputOnlyWithItsForm() {
        String folder = tmp.resolve("x").toString();
        List<Map<String, Object>> calls = List.of(
            Map.of("target", "dev/node1", "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "workflows", "targetFolder", folder),
            Map.of("target", "dev/node1", "diagramGroup", "GRP-01", "excludeGroups",
                List.of("GRP-02"), "targetFolder", folder),
            Map.of("target", "dev/node1", "diagramGroup", "GRP-01", "metadataNames",
                List.of("Example1"), "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "ownerWithoutGroups", "targetFolder",
                folder),
            Map.of("target", "dev/node1", "content", "ownerWithoutGroups", "diagramGroup",
                "GRP-01", "excludeGroups", List.of("GRP-02"), "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "ownerWithoutGroups", "excludeGroups",
                List.of(), "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "ownerWithoutGroups", "excludeGroups",
                IntStream.rangeClosed(1, 21).mapToObj(i -> "GRP-" + i).toList(),
                "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "ownerWithoutGroups", "excludeGroups",
                List.of("GRP,01"), "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "metadata", "diagramGroup", "GRP-01",
                "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "metadata", "owner", OWNER,
                "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "metadata", "excludeGroups",
                List.of("GRP-01"), "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "metadata", "metadataNames",
                IntStream.rangeClosed(1, 51).mapToObj(i -> "Name" + i).toList(),
                "targetFolder", folder),
            Map.of("target", "dev/node1", "content", "other", "targetFolder", folder));

        for (Map<String, Object> arguments : calls) {
            JsonNode result = client.callTool("export_archives", arguments);
            assertThat(result.path("isError").asBoolean()).as(arguments.toString()).isTrue();
        }
        assertThat(audit).as("schema violations are not audited").isEmpty();
        assertThat(cli.launches()).isEmpty();
    }

    // --- gate, production opt-in, folder rules ---------------------------------------------------

    @Test
    void aFormWhoseRowsArePlannedForTheVersionIsRefusedBeforeStartCli() {
        for (Map<String, Object> arguments : List.of(owner(tmp.resolve("a")), Map.<String,
            Object>of("target", "test/node1", "content", "metadata", "targetFolder",
                tmp.resolve("b").toString()))) {
            arguments = new HashMap<>(arguments);
            arguments.put("target", "test/node1");

            JsonNode error = toolError(client.callTool("export_archives", arguments));

            assertThat(error.path("code").asString()).isEqualTo("UNSUPPORTED_VERSION");
            assertThat(error.path("message").asString()).contains("not yet verified");
        }
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(
            AuditOutcome.PENDING, AuditOutcome.REFUSED, AuditOutcome.PENDING,
            AuditOutcome.REFUSED);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void aProductionGroupNeedsTheArchivesOptInForEachForm() throws IOException {
        Map<String, Object> metadata = Map.of("target", "prod/node1", "content", "metadata",
            "targetFolder", tmp.resolve("m").toString());
        Map<String, Object> owner = new HashMap<>(owner(tmp.resolve("o")));
        owner.put("target", "prod/node1");
        for (Map<String, Object> arguments : List.of(owner, metadata)) {
            JsonNode error = toolError(client.callTool("export_archives", arguments));
            assertThat(error.path("code").asString()).isEqualTo("PRODUCTION_PROTECTED");
            assertThat(error.path("message").asString()).contains("archives.productionOptIn");
        }
        assertThat(cli.launches()).isEmpty();

        optIn.add(PROD1);
        cli.expect(METADATA_COMMAND).replying("admin-model-export-metadata")
            .writingExportFile(FakeProcessLauncher.fixture("admin-model-export-metadata.zip"));
        JsonNode result = client.callTool("export_archives", metadata);

        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        assertThat(tmp.resolve("m/metadata-registry.zip")).exists();
    }

    @Test
    void theFolderRulesOfWorkflowsApply() {
        JsonNode error = toolError(client.callTool("export_archives", Map.of("target",
            "dev/node1", "content", "metadata", "targetFolder",
            tmp.resolve("workspace/archives").toString())));

        assertThat(error.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(error.path("message").asString()).contains("workspace");
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void anExistingFileOfTheSameNameIsRefusedBeforeStartCli() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("existing"));
        Files.writeString(folder.resolve("Metadata-Registry.zip"), "kept");
        Files.writeString(folder.resolve("owners.WORKFLOWS.zip"), "kept");

        for (Map<String, Object> arguments : List.of(owner(folder), Map.<String, Object>of(
            "target", "dev/node1", "content", "metadata", "targetFolder", folder.toString()))) {
            JsonNode error = toolError(client.callTool("export_archives", arguments));

            assertThat(error.path("code").asString()).as(arguments.toString())
                .isEqualTo("INVALID_INPUT");
            assertThat(error.path("message").asString()).as(arguments.toString())
                .containsAnyOf("of 'OWNERS' with owners.WORKFLOWS.zip",
                    "of 'metadata registry' with Metadata-Registry.zip")
                .contains("never overwritten");
        }

        assertThat(cli.launches()).as("the file name is known before the export, so no StartCLI"
            + " run (and no check-in comment trace) happens").isEmpty();
        assertThat(Files.readString(folder.resolve("Metadata-Registry.zip"))).isEqualTo("kept");
        assertThat(Files.readString(folder.resolve("owners.WORKFLOWS.zip"))).isEqualTo("kept");
        assertThat(folder.toFile().list()).containsExactlyInAnyOrder("Metadata-Registry.zip",
            "owners.WORKFLOWS.zip");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.REFUSED, AuditOutcome.PENDING, AuditOutcome.REFUSED);
    }

    // --- support ---------------------------------------------------------------------------------

    private Map<String, Object> owner(Path folder) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("target", "dev/node1");
        arguments.put("content", "ownerWithoutGroups");
        arguments.put("excludeGroups", List.of("GRP-01", "GRP 02"));
        arguments.put("targetFolder", folder.toString());
        arguments.put("acknowledgeClearTextSecrets", true);
        return arguments;
    }

    private V81ArtifactAdapter artifacts(NodeId node) {
        EffectiveNodeConfig server = TestNodeConfig.node().id(node.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build();
        NodeCredentials credentials = new NodeCredentials(node,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new V81ArtifactAdapter(new CliExportRunner(server, credentials,
            new CredentialGuard(storeLabels("INUBIT", node),
                new MutableClock(Instant.parse("2026-11-14T08:00:00Z"))),
            TestCliRunners.forHost(cli, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber())), () -> { });
    }

    /** The layout of the T039 owner export (archive.properties, workflow and module index). */
    private static byte[] ownerExport() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : Map.of(
                "archive.properties", "sourceVersion=8.1.17\n",
                "workflow/workflow.xml", "<Workflows><WorkflowGroup><WorkflowGroupName>GRP-03"
                    + "</WorkflowGroupName></WorkflowGroup></Workflows>",
                "module/module.xml", "<Modules/>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static ClientVersion client(String line) {
        return new ClientVersion(InubitVersion.parse(line + ".12").orElseThrow(),
            InubitVersion.parse(line + ".0"), InubitVersion.parse(line + ".99"));
    }

    private static CapabilityMatrix everythingSupported() {
        Map<Capability, SupportState> states = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            states.put(capability, SupportState.SUPPORTED);
        }
        return new CapabilityMatrix(List.of(), states);
    }
}
