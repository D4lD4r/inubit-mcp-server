package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.V90Cases;
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
 * T046a (feature 022, US3, research "Live recording T039a"): the {@code export_archives} forms
 * {@code ownerWithoutGroups} and {@code metadata} through {@link CliExportRunner} against the
 * answers of a 9.0.10 server in {@code fixtures/v9_0/cli/admin-model-export-*}, opened only
 * through {@link V90Cases}, and {@code fetchXSLT1.0}, which 9.0.10 does not know either.
 *
 * <ul>
 *   <li>{@code admin-model-export-group-exclude}: {@code 1-OK: Workflow group exported
 *       successfully.} for the T041 form. Its archive holds clear-text module secrets and is not
 *       committed, so the fake StartCLI writes placeholder bytes, which the runner hands back
 *       unread;
 *   <li>{@code admin-model-export-metadata[-names]} with their recorded archives; the names were
 *       sent single-quoted, the form the runner sends, and names that exist nowhere are accepted;
 *   <li>{@code admin-model-fetchxslt-not-found}: {@code Command not found.}, so both
 *       {@code fetchXSLT1.0} rows are excluded on 9.0 (L-7, T046d does not apply).
 * </ul>
 */
@Timeout(30)
class ExportContentV90ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-export-content-v90-pw";
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");
    private static final List<String> SIX_GROUPS =
        List.of("GRP-01", "GRP-02", "GRP-03", "GRP-04", "GRP-05", "GRP-06");
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);

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
            .cliJavaHome(Path.of("/opt/jdk-21")).build(), credentials, guard,
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    /** The recorded 9.0 answer of {@code fixtureCase}; StartCLI writes {@code archive}. */
    private FakeProcessLauncher replaying(String fixtureCase, byte[] archive) {
        CliResult recorded = V90Cases.cliResult(fixtureCase);
        return FakeProcessLauncher.of(recorded.stdout(), recorded.stderr(), recorded.exitCode())
            .onLaunch(spec -> {
                String command = execCommand(spec);
                commands.add(command);
                Matcher file = EXPORT_FILE.matcher(command);
                assertThat(file.find()).as(command).isTrue();
                try {
                    Files.write(Path.of(file.group(1)), archive);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
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
        assertThat(V90Cases.text("admin-model-export-group-exclude", "stdout"))
            .contains("1-OK: Workflow group exported successfully.");
        byte[] placeholder = "owner archive, not committed".getBytes(StandardCharsets.UTF_8);

        byte[] archive = exports(replaying("admin-model-export-group-exclude", placeholder))
            .exportOwnerWithoutGroups("OWNERS", SIX_GROUPS);

        assertThat(archive).isEqualTo(placeholder);
        assertThat(commands).singleElement().asString().matches(
            "export --exportWorkflowUser 'OWNERS' --exportWorkflowType 'technical'"
                + " --exportWorkflowGroup '' --exportWorkflowGroupExclude"
                + " 'GRP-01,GRP-02,GRP-03,GRP-04,GRP-05,GRP-06' --exportFile '[^']+owner\\.zip'");
    }

    // --- metadata: export --exportMetadata [--exportMetadataNames] ------------------------------

    @Test
    void theMetadataExportReturnsTheRecordedRegistryArchive() throws IOException {
        byte[] recorded = V90Cases.file("admin-model-export-metadata", "zip");

        byte[] archive = exports(replaying("admin-model-export-metadata", recorded))
            .exportMetadata(List.of());

        assertThat(archive).isEqualTo(recorded);
        assertThat(entries(archive))
            .containsExactlyInAnyOrder("MetadataRegistry.xml", "MetadataAllocations.xml");
        assertThat(commands).singleElement().asString()
            .matches("export --exportMetadata --exportFile '[^']+metadata\\.zip'");
    }

    /** INUBIT accepted the single-quoted names that exist nowhere ({@code 'Example1,Example2'}). */
    @Test
    void theNamedMetadataExportReturnsTheRecordedArchiveForUnknownNames() throws IOException {
        assertThat(V90Cases.text("admin-model-export-metadata-names", "stdout"))
            .contains("1-OK: Metadata Names exported successfully.");
        byte[] recorded = V90Cases.file("admin-model-export-metadata-names", "zip");

        byte[] archive = exports(replaying("admin-model-export-metadata-names", recorded))
            .exportMetadata(List.of("Example1", "Example2"));

        assertThat(archive).isEqualTo(recorded);
        assertThat(entries(archive))
            .containsExactlyInAnyOrder("MetadataRegistry.xml", "MetadataAllocations.xml");
        assertThat(commands).singleElement().asString().matches("export --exportMetadata"
            + " --exportMetadataNames 'Example1,Example2' --exportFile '[^']+metadata\\.zip'");
    }

    // --- fetchXSLT1.0: not found on 9.0.10 (L-7) ------------------------------------------------

    @Test
    void fetchXslt10IsNotFoundOn9010AndBothRowsAreExcluded() {
        CliResult recorded = V90Cases.cliResult("admin-model-fetchxslt-not-found");
        assertThat(recorded.exitCode()).isEqualTo(1);
        assertThat(recorded.stdout()).contains("EXECUTION ERROR")
            .contains("Internal INUBIT error!@End@Command not found.");

        for (String entry : List.of("fetchXSLT1.0", "fetchXSLT1.0 --exportFilename")) {
            assertThat(InterfaceCoverage.production().state(Channel.CLI, entry, V9_0)).as(entry)
                .contains(new CoverageState.Excluded("command not found on INUBIT 9.0.10"));
        }
    }

    // --- coverage --------------------------------------------------------------------------------

    @Test
    void theExportRowsAreReachableByTheirContentFormsOn90() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        assertThat(coverage.state(Channel.CLI, "export --exportWorkflowGroupExclude", V9_0))
            .contains(new CoverageState.Reachable("export_archives", "ownerWithoutGroups"));
        for (String entry : List.of("export --exportMetadata", "export --exportMetadataNames")) {
            assertThat(coverage.state(Channel.CLI, entry, V9_0)).as(entry)
                .contains(new CoverageState.Reachable("export_archives", "metadata"));
        }
    }
}
