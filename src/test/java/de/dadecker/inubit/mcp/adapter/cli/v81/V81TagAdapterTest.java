package de.dadecker.inubit.mcp.adapter.cli.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021 (feature 004, research D-16, D-25 M1): the 8.1 {@link TagPort} — the owner's history of
 * all diagram groups and types, the history of one diagram group, and the tag commands after
 * the credentials are confirmed.
 */
@Timeout(30)
class V81TagAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String OK = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Workflow group exported successfully.\n";
    private static final String HISTORY = "<VersionInformation><Workflows>"
        + "<WorkflowGroup Name=\"GRP-01\"><Workflow Name=\"W-1\" Type=\"technical\">"
        + "<Version><versionNode>1</versionNode><Tags><Tag>REL-1</Tag></Tags></Version>"
        + "</Workflow></WorkflowGroup></Workflows><Modules><Module Name=\"M-1\"><Version>"
        + "<versionNode>2</versionNode></Version></Module></Modules></VersionInformation>";

    @TempDir
    Path cliHome;

    private final ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
    private final AtomicInteger confirmed = new AtomicInteger();

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    private TagPort adapter() {
        EffectiveNodeConfig server = TestNodeConfig.node().id(DEV.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).cliExportTimeout(Duration.ofSeconds(2)).build();
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("tag-adapter-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(new CredentialVariables("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-10-07T08:00:00Z")));
        CliRunner runner = new CliRunner(cli, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        CliOutputClassifier classifier = new CliOutputClassifier(new SecretScrubber());
        return new V81TagAdapter(DEV, new CliExportRunner(server, credentials, guard, runner,
            classifier), new CliTagRunner(server, credentials, guard, runner, classifier),
            confirmed::incrementAndGet);
    }

    private void exporting(String prefix) {
        cli.expect(prefix).then(spec -> {
            try {
                Files.write(ScriptedProcessLauncher.exportFile(spec), ArtifactFixtures.zip(
                    Map.of("versionHistory.xml", HISTORY.getBytes(StandardCharsets.UTF_8))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).replying(OK, "", 0);
    }

    @Test
    void theOwnersHistoryCoversAllDiagramGroupsAndTypes() {
        exporting("export --exportWorkflowUser 'jdoe' --exportWorkflowType 'all'"
            + " --exportWorkflowGroup '' --includeHistory");

        TagPort.History history = adapter().history("jdoe");

        cli.verifyComplete();
        assertThat(history.diagrams().get("W-1").diagramGroup()).isEqualTo("GRP-01");
        assertThat(history.diagrams().get("W-1").versions().get(0).tags())
            .containsExactly("REL-1");
        assertThat(history.modules()).containsKey("M-1");
    }

    @Test
    void theHistoryOfOneDiagramGroupIsItsTechnicalWorkflowsAndTheirModules() {
        exporting("export --exportWorkflowUser 'jdoe' --exportWorkflowType 'technical'"
            + " --exportWorkflowGroup 'GRP-01' --includeHistory");

        TagPort.History history = adapter().history("jdoe", "GRP-01");

        cli.verifyComplete();
        assertThat(history.diagrams()).containsOnlyKeys("W-1");
        assertThat(history.modules()).containsOnlyKeys("M-1");
    }

    @Test
    void tagCommandsRunAfterTheCredentialsAreConfirmed() {
        cli.expect("tag --tagMove 'REL-2' --tagWorkflowGroup 'GRP-01' --tagWorkflowType"
            + " 'technical' --tagUser 'jdoe'").replying("tag_ok")
            .expect("tag --tagDelete 'REL-2' --tagUser 'jdoe'").replying("tag_delete_ok");
        TagPort adapter = adapter();

        adapter.checkAvailable();
        adapter.tag("REL-2", "GRP-01", "jdoe");
        adapter.deleteTag("REL-2", "jdoe");

        cli.verifyComplete();
        assertThat(confirmed).hasValue(2);
        assertThat(cli.execCommands()).hasSize(2).allMatch(line -> line.startsWith("tag "));
        assertThat(List.of(adapter.toString())).noneMatch(text -> text.contains("tag-adapter"));
    }
}
