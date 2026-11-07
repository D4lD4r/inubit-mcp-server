package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.v81.ModuleIndexParser;
import de.dadecker.inubit.mcp.adapter.cli.v81.VersionHistoryParser;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T030 (feature 017, FR-002, FR-013, research D-10, D-15, D-16): the 8.1 StartCLI parsing and
 * classification against the INUBIT 9.0 fixtures (opened only through {@link V90Cases}): the
 * version and help output, the 8.1 error codes of the 9.0 failures, the OK messages, the import
 * protocol and the export archives.
 */
@Timeout(30)
class V90CliContractTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final OptionalInt NINE = OptionalInt.of(9);

    @TempDir
    Path cliHome;

    @TempDir
    Path javaHome;

    private final CliOutputClassifier classifier = new CliOutputClassifier(new SecretScrubber());

    private ToolError failure(String name) {
        CliOutcome outcome = classifier.classify(V90Cases.cliResult(name).forClientMajor(NINE));
        assertThat(outcome).as(name).isInstanceOf(CliOutcome.Failure.class);
        return ((CliOutcome.Failure) outcome).error();
    }

    private CliOutput success(String name) {
        CliOutcome outcome = classifier.classify(V90Cases.cliResult(name).forClientMajor(NINE));
        assertThat(outcome).as(name).isInstanceOf(CliOutcome.Success.class);
        return ((CliOutcome.Success) outcome).output();
    }

    private static FakeProcessLauncher replaying(String name) {
        CliResult result = V90Cases.cliResult(name);
        return FakeProcessLauncher.of(result.stdout(), result.stderr(), result.exitCode());
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    // --- version and help ----------------------------------------------------------------------

    @Test
    void theVersionOfA90ClientAndItsRangeAreRead() throws IOException {
        FakeStartCli.install(cliHome);
        EffectiveNodeConfig server = TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(javaHome).build();
        CliVersionProbe probe = new CliVersionProbe(TestCliRunners.forHost(replaying("version"),
            Map.of("PATH", Platform.searchPath()), new CliResources("acme",
                PrivateFiles.forHost())), id -> server, new SecretScrubber());

        ClientVersion client = probe.probe(ID, Optional.empty(), TIMEOUT).version();

        assertThat(client.version()).isEqualTo(version("9.0.7"));
        assertThat(client.supportedFrom()).contains(version("9.0.0"));
        assertThat(client.supportedTo()).contains(version("9.0.7"));
    }

    @Test
    void aNineZeroNodeStartsNoHelpRunWithTheProductionMatrix() throws IOException {
        FakeProcessLauncher launcher = replaying("help");

        assertThat(helpProbe(launcher, CapabilityMatrix.production()).optionsFor(ID, TIMEOUT))
            .isEmpty();
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void theHelpOfA90ClientListsEveryOptionTheServerUses() throws IOException {
        // research D-15: the 9.0 help is shown only with an explicit -h; read with a matrix that
        // probes 9.0 like a cliOnly line
        CapabilityMatrix probing = new CapabilityMatrix(List.of(new CapabilityMatrix.Entry(
            new VersionRange(9, 0, 9, 0), Map.of(), Map.of(), true)), Map.of());
        FakeProcessLauncher launcher = replaying("help");

        assertThat(helpProbe(launcher, probing).optionsFor(ID, TIMEOUT)).contains(
            new CliClientOptions(true, true, true, Optional.of(21)));
        assertThat(launcher.last().spec().startCliArguments()).containsExactly("-h");
        assertThat(V90Cases.cliResult("help").stdout())
            .contains("--help", "-u", "--trustStoreFilePath", "--disableHostNameVerification",
                "--execCommand");
    }

    private CliClientProbe helpProbe(FakeProcessLauncher launcher, CapabilityMatrix matrix)
        throws IOException {
        Path bin = Files.createDirectories(cliHome.resolve("bin"));
        Files.writeString(bin.resolve("startcli.sh"), "#!/bin/sh\n");
        Files.writeString(bin.resolve("startcli.bat"), "@echo off\r\n");
        Files.writeString(javaHome.resolve("release"),
            "IMPLEMENTOR=\"Example\"\nJAVA_VERSION=\"21.0.5\"\nOS_NAME=\"Linux\"\n");
        EffectiveNodeConfig server = TestNodeConfig.node().id(ID.toString())
            .versionLine(VersionSetting.V9_0).cliHome(cliHome).cliJavaHome(javaHome).build();
        return new CliClientProbe(TestCliRunners.forHost(launcher, Map.of("PATH", "/usr/bin"),
            new CliResources("acme", PrivateFiles.forHost())), id -> server, matrix,
            new MutableClock(Instant.parse("2026-11-06T10:00:00Z")));
    }

    // --- failures ------------------------------------------------------------------------------

    @Test
    void aRejectedLoginIsAuthFailedWithTheOauthHint() {
        ToolError error = failure("login_failed");

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.nextStep()).contains("OAuth");
    }

    @Test
    void anUnreachableServerIsUnreachable() {
        assertThat(failure("unreachable").code()).isEqualTo(ErrorCode.UNREACHABLE);
        // research D-15: the 9.0 CLI URL is the base URL without the SOAP servlet path
        assertThat(V90Cases.cliResult("unreachable").stdout()).doesNotContain("Soap", "SOAP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"processErrorStart_unknown", "kill_unknown"})
    void anUnknownProcessIsNotFound(String name) {
        assertThat(failure(name).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void anUnknownCommandIsInternal() {
        assertThat(failure("unknown_command").code()).isEqualTo(ErrorCode.INTERNAL);
    }

    @Test
    void theProcessListWithoutAnOkMessageIsNeverAGuessedSuccess() {
        assertThat(failure("ps_all").code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(V90Cases.cliResult("ps_all").stdout())
            .contains("UID,PID,PRIO,STATE,DATE,WORKFLOW,MODULE,NODE,TAG");
    }

    // --- successes -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"processErrorStart_ok", "kill_ok", "export_history_sample",
        "export_modules_sample"})
    void anOkMessageIsASuccess(String name) {
        assertThat(success(name).messages()).singleElement()
            .satisfies(message -> assertThat(message.ok()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tag_ok", "import_created"})
    void aRunWithoutAResultLineEndsWithExitZeroAndNoNokMessage(String name) {
        // the tag and import runners judge these by exit code and NOK messages (CliTagRunner)
        CliResult result = V90Cases.cliResult(name);

        assertThat(result.exitCode()).isZero();
        assertThat(classifier.parse(result).nokMessages()).isEmpty();
    }

    @Test
    void theImportProtocolOf90IsRead() {
        ImportProtocol protocol = ImportProtocolParser.parse(
            V90Cases.cliResult("import_created").stdout());

        assertThat(protocol.total()).isEqualTo(5);
        assertThat(protocol.entries()).hasSize(5);
    }

    @Test
    void theExportArchivesOf90AreRead() {
        assertThat(VersionHistoryParser.parse(ID, entry("export_history_sample",
            CliExportRunner.HISTORY_ENTRY)).workflows()).isNotEmpty();
        assertThat(ModuleIndexParser.parse(ID, "OWNERS", entry("export_modules_sample",
            CliExportRunner.MODULE_INDEX_ENTRY))).isNotEmpty();
    }

    private static byte[] entry(String name, String entryName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(
            V90Cases.file(name, "zip")))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(entryName)) {
                    return zip.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new AssertionError(name + ".zip has no " + entryName);
    }
}
