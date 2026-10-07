package de.dadecker.inubit.mcp.adapter.cli.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ImportPort.Mode;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T014 (feature 004, research D-8): the 8.1 import port checks first, confirms the credentials
 * by REST (so that StartCLI never holds the single permit of an unconfirmed guard), then runs
 * StartCLI.
 */
class V81ImportAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");

    @TempDir
    Path cliHome;

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private V81ImportAdapter adapter(EffectiveNodeConfig server, ScriptedProcessLauncher cli) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("pw-import-adapter"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-10-07T08:00:00Z")));
        CliRunner runner = new CliRunner(spec -> {
            calls.add("startcli");
            return cli.launch(spec);
        }, Map.of("PATH", "/usr/bin"), false, new CliResources("acme"));
        return new V81ImportAdapter(new CliImportRunner(server, credentials, guard, runner,
            new CliOutputClassifier(new SecretScrubber())), () -> calls.add("confirm"));
    }

    private EffectiveNodeConfig withCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
        return TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"))
            .build();
    }

    @Test
    void anImportIsCheckedThenTheCredentialsAreConfirmedThenStartCliRuns() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher().expect("import ")
            .replying("import_modified");

        var protocol = adapter(withCli(), cli).importArchive(new byte[] {1}, Mode.WORKFLOW,
            "jdoe");

        assertThat(protocol.modified()).hasSize(5);
        assertThat(calls).containsExactly("confirm", "startcli");
    }

    @Test
    void withoutCliNothingIsConfirmedOrLaunched() {
        EffectiveNodeConfig server = TestNodeConfig.node().build();
        V81ImportAdapter adapter = adapter(server, new ScriptedProcessLauncher());

        assertThatThrownBy(adapter::checkAvailable).isInstanceOfSatisfying(
            ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        assertThatThrownBy(() -> adapter.importArchive(new byte[] {1}, Mode.MODULE, "jdoe")).isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        assertThat(calls).isEmpty();
    }

    // --- feature 005 (T007, research D-1) ----------------------------------------------------

    @Test
    void aRepositoryImportIsCheckedThenConfirmedThenRun() throws IOException {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher()
            .expect("import --importFile ").replying("import_repository_ok");

        adapter(withCli(), cli).importRepository(new byte[] {1}, "jdoe");

        assertThat(cli.execCommands()).singleElement().asString()
            .endsWith(" --importRepositoryPath '/Root/jdoe'");
        assertThat(calls).containsExactly("confirm", "startcli");
    }

    @Test
    void withoutCliNoRepositoryImportIsConfirmedOrLaunched() {
        V81ImportAdapter adapter = adapter(TestNodeConfig.node().build(),
            new ScriptedProcessLauncher());

        assertThatThrownBy(() -> adapter.importRepository(new byte[] {1}, "jdoe"))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
        assertThat(calls).isEmpty();
    }

    @Test
    void anInvalidOwnerIsRefusedBeforeTheCredentialsAreConfirmed() throws IOException {
        // stage 1 review #9: no REST login for input that cannot be sent
        V81ImportAdapter adapter = adapter(withCli(), new ScriptedProcessLauncher());

        for (String owner : List.of("it's", "..", "")) {
            assertThatThrownBy(() -> adapter.importRepository(new byte[] {1}, owner))
                .isInstanceOfSatisfying(ToolErrorException.class, e ->
                    assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        }
        assertThat(calls).isEmpty();
    }
}

