package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * T006 (feature 005, research D-1): the repository mode of the StartCLI import — the exact
 * command line with the owner's root as import path, success by {@code n-OK: Imported
 * successfully} without a protocol, failures as {@code IMPORT_FAILED}, the private temporary
 * archive gone afterwards.
 */
class CliRepositoryImportTest {

    private static final NodeId INT = NodeId.parse("int/node1");
    private static final byte[] ARCHIVE = {'P', 'K', 3, 4, 'r', 'e', 'p', 'o'};

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

    @BeforeEach
    void installFakeCli() throws IOException {
        Files.writeString(Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh"),
            "#!/bin/sh\n");
    }

    @AfterEach
    void everyScriptedLaunchHappenedAndNothingElse() {
        cli.verifyComplete();
    }

    private CliImportRunner imports() {
        CliRunner runner = new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        NodeCredentials credentials = new NodeCredentials(INT,
            Optional.of(new SourcedValue<>("jdoe", "TARGET_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("repo-import-pw"), "TARGET_PASSWORD")),
            Optional.empty(), Optional.empty());
        return new CliImportRunner(TestNodeConfig.node().id(INT.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials,
            new CredentialGuard(new CredentialVariables("INUBIT", INT),
                new MutableClock(Instant.parse("2026-10-07T08:00:00Z"))), runner,
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

    @Test
    void importsIntoTheOwnersRootAndSucceedsWithoutAProtocol() throws IOException {
        cli.expect(Pattern.compile("^import --importFile '[^']+/import\\.zip'"
                + " --importRepositoryPath '/Root/jdoe'$"))
            .capturingImportFile().replying("import_repository_ok");

        imports().importRepository(ARCHIVE, "jdoe");

        assertThat(cli.launches()).singleElement()
            .satisfies(launch -> assertThat(launch.importFile()).isEqualTo(ARCHIVE));
        assertTempRootEmpty();
    }

    @Test
    void aRefusedImportIsImportFailed() throws IOException {
        cli.expect("import ").replying("import_nok");

        ToolErrorException e = failure(() -> imports().importRepository(ARCHIVE, "OWNERS"));

        assertThat(e.error().code()).isEqualTo(ErrorCode.IMPORT_FAILED);
        assertThat(e.error().node()).contains(INT);
        assertThat(e.error().message()).contains("Import failed");
        assertTempRootEmpty();
    }

    @Test
    void exitCodeZeroWithoutTheSuccessLineIsNoSuccess() {
        cli.expect("import ").replying("JAVA_HOME is set\nPassword: \nCompleted = 0 MB / 0 MB\n",
            "", 0);
        cli.expect("import ").replying("JAVA_HOME is set\nPassword: \n1-OK: Something else\n",
            "", 0);

        for (int i = 0; i < 2; i++) {
            assertThat(failure(() -> imports().importRepository(ARCHIVE, "jdoe")).error()
                .code()).isEqualTo(ErrorCode.IMPORT_FAILED);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "it's", "a/b", "..", "-rf"})
    void anOwnerStartCliCannotTakeIsRefusedBeforeAnyLaunch(String owner) {
        ToolErrorException e = failure(() -> imports().importRepository(ARCHIVE, owner));

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cli.launches()).isEmpty();
    }
}
