package de.dadecker.inubit.mcp.adapter;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestPermits;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 022 T063 (US4, research D-18): the 8.1 tag administration adapter, scripted StartCLI
 * runs. The active tag and its forms run on the {@link CliTagAdminRunner}; their answers are
 * RECONSTRUCTED as in {@code CliTagAdminRunnerTest}. Tag removal is excluded after T055.
 */
@Timeout(30)
class V81TagAdministrationAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.TAG_ADMINISTRATION);

    @TempDir
    Path cliHome;

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TagAdministrationPort adapter(ProcessLauncher launcher) {
        EffectiveNodeConfig server = TestNodeConfig.node().id(DEV.value()).cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build();
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("tag-admin-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-11-18T08:00:00Z")));
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        CliOutputClassifier classifier = new CliOutputClassifier(new SecretScrubber());
        return new V81TagAdministrationAdapter(
            new CliTagAdminRunner(server, credentials, guard, runner, classifier));
    }

    @Test
    void theChangesRunTheActiveTagForms() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        List<String> forms = List.of("tag --tagSetActive 'REL-1' --tagUser 'jdoe'",
            "tag --tagRemoveActive --tagUser 'jdoe'");
        forms.forEach(form -> cli.expect(Pattern.compile(Pattern.quote(form))).replying("tag_ok"));
        TagAdministrationPort adapter = adapter(cli);

        adapter.setActiveTag("jdoe", "REL-1", PERMIT);
        adapter.removeActiveTag("jdoe", PERMIT);

        cli.verifyComplete();
        assertThat(cli.execCommands()).containsExactlyElementsOf(forms);
    }

    @Test
    void theActiveTagIsReadWithUser() {
        FakeProcessLauncher session = FakeProcessLauncher.of("", "", 0).responding(stdin ->
            new Answer("JAVA_HOME is set\nPassword: \n"
                + "CLI|jdoe@inubit-dev-1.example.test> user 'jdoe'\n"
                + "User name    jdoe       \r\nUser group   OPS        \r\n"
                + "Super group  headoffice \r\nActive tag   REL-1      \r\n"
                + "CLI|jdoe@inubit-dev-1.example.test> exit\n", 0));

        assertThat(adapter(session).activeTag("jdoe")).contains("REL-1");
    }
}
