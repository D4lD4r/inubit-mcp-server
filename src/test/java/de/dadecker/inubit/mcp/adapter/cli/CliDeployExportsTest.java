package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T005 (feature 005, research D-1, D-4): the release export (owner-wide, narrowed by
 * {@code --exportTag}) and the repository export on {@link ScriptedProcessLauncher} with the
 * recorded shapes of T001.
 */
class CliDeployExportsTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String EXPORT_OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Workflow group exported successfully.\n";

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T08:00:00Z"));

    @BeforeEach
    void installFakeCli() throws IOException {
        Files.writeString(Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh"),
            "#!/bin/sh\n");
    }

    @AfterEach
    void everyScriptedLaunchHappenedAndNothingElse() {
        cli.verifyComplete();
    }

    private CliExportRunner exports() {
        CliRunner runner = new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("deploy-export-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliExportRunner(TestNodeConfig.node().id(DEV.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials,
            new CredentialGuard(new CredentialVariables("INUBIT", DEV), clock), runner,
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    private static ToolErrorException failure(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("no ToolErrorException");
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> files = Files.list(tempRoot)) {
            assertThat(files).isEmpty();
        }
    }

    // --- release export ----------------------------------------------------------------------

    @Test
    void theReleaseExportNarrowsTheEmptyGroupListByTheTag() throws IOException {
        byte[] release = FakeProcessLauncher.fixture("export_release.zip");
        cli.expect(Pattern.compile("^export --exportWorkflowUser 'jdoe' --exportWorkflowType"
                + " 'technical' --exportWorkflowGroup '' --exportTag 'TAG-01' --exportFile"
                + " '[^']+/release\\.zip'$"))
            .writingExportFile(release).replying("export_release");

        byte[] archive = exports().exportRelease("jdoe", "TAG-01");

        assertThat(archive).isEqualTo(release);
        assertTempRootEmpty();
    }

    @Test
    void anArchiveWithoutWorkflowsMeansNoDiagramGroupCarriesTheTag() throws IOException {
        Map<String, byte[]> empty = new LinkedHashMap<>();
        empty.put("archive.properties", "sourceVersion=8.1.17\n".getBytes(StandardCharsets.UTF_8));
        empty.put("workflow/workflow.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<IBISWorkflow version=\"5.3\"><Workflows/></IBISWorkflow>")
            .getBytes(StandardCharsets.UTF_8));
        cli.expect("export --exportWorkflowUser 'jdoe'")
            .writingExportFile(ArtifactFixtures.zip(empty)).replying(EXPORT_OK, "", 0);
        cli.expect("export --exportWorkflowUser 'jdoe'")
            .writingExportFile(ArtifactFixtures.zip(Map.of("archive.properties", new byte[0])))
            .replying(EXPORT_OK, "", 0);

        for (int i = 0; i < 2; i++) {
            ToolErrorException e = failure(() -> exports().exportRelease("jdoe", "TAG-01"));
            assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(e.error().node()).contains(DEV);
            assertThat(e.error().message()).contains("TAG-01").contains("jdoe");
        }
        assertTempRootEmpty();
    }

    @Test
    void stopsWhenStartCliFindsNoGroupForTheTag() {
        cli.expect("export --exportWorkflowUser 'jdoe'").replying("JAVA_HOME is set\nPassword:"
            + " \nEXECUTION ERROR\nInternal INUBIT error!\n2-NOK: No workflow group containing"
            + " workflows for export found.\n", "", 1);

        ToolErrorException e = failure(() -> exports().exportRelease("jdoe", "TAG-01"));

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().node()).contains(DEV);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a'b", "REL*", "REL?", "-rf", "TAG/1"})
    void aTagStartCliCannotTakeIsRefusedBeforeAnyLaunch(String tag) {
        ToolErrorException e = failure(() -> exports().exportRelease("jdoe", tag));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void anOwnerStartCliCannotTakeIsRefusedBeforeAnyLaunch() {
        assertThat(failure(() -> exports().exportRelease("it's", "TAG-01")).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(failure(() -> exports().exportRelease(null, "TAG-01")).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cli.launches()).isEmpty();
    }

    // --- repository export -------------------------------------------------------------------

    @Test
    void theRepositoryExportReturnsTheArchiveOfThePath() throws IOException {
        byte[] repository = FakeProcessLauncher.fixture("export_repository.zip");
        cli.expect(Pattern.compile("^export --exportRepositoryPath '/Root/jdoe/xsd' --exportFile"
                + " '[^']+/repository\\.zip'$"))
            .writingExportFile(repository).replying("export_repository");

        assertThat(exports().exportRepository("/Root/jdoe/xsd")).isEqualTo(repository);
        assertTempRootEmpty();
    }

    @Test
    void aMissingRepositoryPathIsNotFound() throws IOException {
        cli.expect("export --exportRepositoryPath '/Root/jdoe/xsd/no-such-dir'")
            .replying("export_repository_not_found");

        ToolErrorException e = failure(() -> exports().exportRepository(
            "/Root/jdoe/xsd/no-such-dir"));

        assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(e.error().node()).contains(DEV);
        assertThat(e.error().message()).contains("Path not found");
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/Root", "Root/jdoe", "/Root/../x", "/Root/a'b", "/Other/x"})
    void aRepositoryPathOutsideTheRuleIsRefusedBeforeAnyLaunch(String path) {
        ToolErrorException e = failure(() -> exports().exportRepository(path));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void theChecksRunWithoutLaunching() {
        CliExportRunner exports = exports();

        exports.checkReleaseExport("jdoe", "TAG-01");
        exports.checkRepositoryExport("/Root/jdoe/xsd/release.xsl");

        assertThatThrownBy(() -> exports.checkReleaseExport("jdoe", ""))
            .isInstanceOf(ToolErrorException.class);
        assertThatThrownBy(() -> exports.checkRepositoryExport("/Root"))
            .isInstanceOf(ToolErrorException.class);
        assertThat(cli.launches()).isEmpty();
    }
}
