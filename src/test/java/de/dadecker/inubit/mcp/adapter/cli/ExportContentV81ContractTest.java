package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T046 (feature 022, US3, research "Live recording T039", D-21): the {@code export_archives}
 * forms {@code ownerWithoutGroups} and {@code metadata} through {@link CliExportRunner} against
 * the answers of an 8.1.17 server in {@code fixtures/v8_1/cli/admin-model-export-*}.
 *
 * <ul>
 *   <li>{@code admin-model-export-group-exclude}: {@code 1-OK: Workflow group exported
 *       successfully.} for the owner, type {@code 'technical'}, the empty group and six excluded
 *       groups in one argument. Its archive holds clear-text module secrets and is not committed,
 *       so the fake StartCLI writes placeholder bytes, which the runner hands back unread;
 *   <li>{@code admin-model-export-group-exclude-missing-option}: without type and group INUBIT
 *       answers {@code 2-NOK: Missing export option.};
 *   <li>{@code admin-model-export-metadata[-names]} with their recorded archives
 *       ({@code MetadataRegistry.xml}, {@code MetadataAllocations.xml}).
 * </ul>
 */
@Timeout(30)
class ExportContentV81ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-export-content-pw";
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static final List<String> SIX_GROUPS =
        List.of("GRP-01", "GRP-02", "GRP-03", "GRP-04", "GRP-05", "GRP-06");
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-11-14T08:00:00Z")));
    private final List<String> commands = new ArrayList<>();

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private CliExportRunner exports(FakeProcessLauncher launcher) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliExportRunner(TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials, guard,
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    /** The recorded answer of {@code fixtureCase}; StartCLI writes {@code archive}, if any. */
    private FakeProcessLauncher replaying(String fixtureCase, Optional<byte[]> archive) {
        return FakeProcessLauncher.replaying(fixtureCase).onLaunch(spec -> {
            String command = execCommand(spec);
            commands.add(command);
            Matcher file = EXPORT_FILE.matcher(command);
            assertThat(file.find()).as(command).isTrue();
            archive.ifPresent(bytes -> {
                try {
                    Files.write(Path.of(file.group(1)), bytes);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        });
    }

    private static String execCommand(LaunchSpec spec) {
        List<String> arguments = spec.startCliArguments();
        return arguments.get(arguments.indexOf("--execCommand") + 1);
    }

    private static List<String> entries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    // --- ownerWithoutGroups: export --exportWorkflowGroupExclude --------------------------------

    @Test
    void theOwnerExportSendsTheRecordedFormWithTypeEmptyGroupAndTheExcludedGroups() {
        byte[] placeholder = "owner archive, not committed".getBytes(StandardCharsets.UTF_8);

        byte[] archive = exports(replaying("admin-model-export-group-exclude",
            Optional.of(placeholder))).exportOwnerWithoutGroups("OWNERS", SIX_GROUPS);

        assertThat(archive).isEqualTo(placeholder);
        assertThat(commands).singleElement().asString().matches(
            "export --exportWorkflowUser 'OWNERS' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup '' --exportWorkflowGroupExclude"
                + " 'GRP-01,GRP-02,GRP-03,GRP-04,GRP-05,GRP-06' --exportFile '[^']+owner\\.zip'");
    }

    /** The form without type and group, which the runner never sends, is refused by INUBIT. */
    @Test
    void theRecordedMissingExportOptionAnswerFailsTheExport() {
        assertThatThrownBy(() -> exports(replaying(
            "admin-model-export-group-exclude-missing-option", Optional.empty()))
            .exportOwnerWithoutGroups("OWNERS", SIX_GROUPS))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().excerpt()).hasValueSatisfying(excerpt ->
                    assertThat(excerpt).contains("2-NOK: Missing export option."));
                assertThat(e.error().toString()).doesNotContain(PASSWORD);
            });
    }

    // --- metadata: export --exportMetadata [--exportMetadataNames] ------------------------------

    @Test
    void theMetadataExportReturnsTheRecordedRegistryArchive() throws IOException {
        byte[] recorded = FakeProcessLauncher.fixture("admin-model-export-metadata.zip");

        byte[] archive = exports(replaying("admin-model-export-metadata", Optional.of(recorded)))
            .exportMetadata(List.of());

        assertThat(archive).isEqualTo(recorded);
        assertThat(entries(archive))
            .containsExactlyInAnyOrder("MetadataRegistry.xml", "MetadataAllocations.xml");
        assertThat(commands).singleElement().asString()
            .matches("export --exportMetadata --exportFile '[^']+metadata\\.zip'");
    }

    /** INUBIT accepted names that exist nowhere ({@code Example1,Example2}). */
    @Test
    void theNamedMetadataExportReturnsTheRecordedArchiveForUnknownNames() throws IOException {
        byte[] recorded = FakeProcessLauncher.fixture("admin-model-export-metadata-names.zip");

        byte[] archive = exports(replaying("admin-model-export-metadata-names",
            Optional.of(recorded))).exportMetadata(List.of("Example1", "Example2"));

        assertThat(archive).isEqualTo(recorded);
        assertThat(entries(archive))
            .containsExactlyInAnyOrder("MetadataRegistry.xml", "MetadataAllocations.xml");
        assertThat(commands).singleElement().asString().matches("export --exportMetadata"
            + " --exportMetadataNames 'Example1,Example2' --exportFile '[^']+metadata\\.zip'");
    }

    // --- coverage --------------------------------------------------------------------------------

    /** 9.0: {@code ExportContentV90ContractTest} (T046a). */
    @Test
    void theExportRowsAreReachableByTheirContentFormsOn81() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.CLI, "export --exportWorkflowGroupExclude", V8_1))
            .contains(new CoverageState.Reachable("export_archives", "ownerWithoutGroups"));
        for (String entry : List.of("export --exportMetadata", "export --exportMetadataNames")) {
            assertThat(coverage.state(Channel.CLI, entry, V8_1)).as(entry)
                .contains(new CoverageState.Reachable("export_archives", "metadata"));
        }
    }
}
