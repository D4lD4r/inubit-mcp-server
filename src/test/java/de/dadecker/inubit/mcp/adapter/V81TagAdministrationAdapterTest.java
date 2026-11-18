package de.dadecker.inubit.mcp.adapter;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.V81ReleaseArchives;
import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
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
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion.Kind;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort.Removal;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort.Scope;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort.TaggedObjects;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestPermits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * runs. The versions carrying a tag in a diagram group come from the group's history export,
 * parsed as {@code tag_artifacts} parses it; the objects outside the group from the owner's
 * export by the tag (the recorded {@code export_release.zip}: {@code GRP-01}, tag
 * {@code TAG-01}, two workflows and nine modules). The active tag and the tag forms run on the
 * {@link CliTagAdminRunner}; their answers are RECONSTRUCTED as in {@code CliTagAdminRunnerTest}.
 */
@Timeout(30)
class V81TagAdministrationAdapterTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.TAG_ADMINISTRATION);
    private static final String EXPORTED = "JAVA_HOME is set\nPassword: \n"
        + "1-OK: Workflow group exported successfully.\n";
    private static final String HISTORY_OF_GRP_01 = "export --exportWorkflowUser 'jdoe'"
        + " --exportWorkflowType 'technical' --exportWorkflowGroup 'GRP-01' --includeHistory";
    private static final String EXPORT_BY_TAG = "export --exportWorkflowUser 'jdoe'"
        + " --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportTag 'TAG-01'";
    /** GRP-01: an older untagged version, a version with a second tag, an untagged module. */
    private static final String HISTORY = "<VersionInformation><Workflows>"
        + "<WorkflowGroup Name=\"GRP-01\">"
        + "<Workflow Name=\"Workflow-0001\" Type=\"technical\">"
        + "<Version><versionNode>1</versionNode><Tags><Tag>TAG-01</Tag></Tags></Version>"
        + "<Version><versionNode>2</versionNode></Version></Workflow>"
        + "<Workflow Name=\"Workflow-0002\" Type=\"technical\">"
        + "<Version><versionNode>10</versionNode><Tags><Tag>OTHER</Tag></Tags></Version>"
        + "<Version><versionNode>11</versionNode><Tags><Tag>OTHER</Tag><Tag>TAG-01</Tag></Tags>"
        + "</Version></Workflow></WorkflowGroup></Workflows>"
        + "<Modules><Module Name=\"Module-0003\"><Version><versionNode>1</versionNode><Tags>"
        + "<Tag>TAG-01</Tag></Tags></Version></Module>"
        + "<Module Name=\"Module-0004\"><Version><versionNode>2</versionNode></Version></Module>"
        + "</Modules></VersionInformation>";

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
        return new V81TagAdministrationAdapter(DEV,
            new CliTagAdminRunner(server, credentials, guard, runner, classifier),
            new CliExportRunner(server, credentials, guard, runner, classifier),
            new V81ReleaseArchives());
    }

    private static void exporting(ScriptedProcessLauncher cli, String command, byte[] zip) {
        cli.expect(Pattern.compile(Pattern.quote(command) + " --exportFile .*"))
            .writingExportFile(zip).replying(EXPORTED, "", 0);
    }

    private static byte[] history(String xml) {
        return ArtifactFixtures.zip(Map.of("versionHistory.xml",
            xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theTaggedVersionsOfAGroupComeFromItsHistoryAndTheRestFromTheExportByTheTag() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        exporting(cli, HISTORY_OF_GRP_01, history(HISTORY));
        exporting(cli, EXPORT_BY_TAG, FakeProcessLauncher.fixture("export_release.zip"));

        TaggedObjects tagged = adapter(cli).tagged("TAG-01", "jdoe",
            new Scope.DiagramGroup("GRP-01", "technical"));

        cli.verifyComplete();
        assertThat(tagged.inScope()).containsExactly(
            new TaggedVersion(Kind.WORKFLOW, "Workflow-0001", 1),
            new TaggedVersion(Kind.WORKFLOW, "Workflow-0002", 11),
            new TaggedVersion(Kind.MODULE, "Module-0003", 1));
        assertThat(tagged.outsideScopeCount()).as("Module-0001, -0002 and -0004 to -0009")
            .isEqualTo(8);
    }

    @Test
    void aTagOnNoDiagramGroupOfTheOwnerLeavesNothingOutsideTheScope() {
        // the export by tag without workflows is NOT_FOUND in CliExportRunner (feature 005 D-4)
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        exporting(cli, HISTORY_OF_GRP_01, history("<VersionInformation><Workflows>"
            + "<WorkflowGroup Name=\"GRP-01\"><Workflow Name=\"Workflow-0001\" Type=\"technical\">"
            + "<Version><versionNode>1</versionNode></Version></Workflow></WorkflowGroup>"
            + "</Workflows></VersionInformation>"));
        exporting(cli, EXPORT_BY_TAG, ArtifactFixtures.zip(Map.of("module/module.xml",
            "<IBISWorkflow/>".getBytes(StandardCharsets.UTF_8))));

        TaggedObjects tagged = adapter(cli).tagged("TAG-01", "jdoe",
            new Scope.DiagramGroup("GRP-01", "technical"));

        cli.verifyComplete();
        assertThat(tagged).isEqualTo(new TaggedObjects(List.of(), 0));
    }

    @Test
    void aRepositoryPathHasNoReadAndLaunchesNothing() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

        assertThatThrownBy(() -> adapter(cli).tagged("TAG-01", "jdoe",
            new Scope.RepositoryPath("/Root/jdoe/xsd")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("repository");
            });
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void aDiagramGroupOfAnotherTypeIsRefusedAndLaunchesNothing() {
        // the export by tag reads only technical workflows (CliExportRunner#exportRelease), so
        // another type would count technical workflows outside the scope
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();

        assertThatThrownBy(() -> adapter(cli).tagged("TAG-01", "jdoe",
            new Scope.DiagramGroup("GRP-01", "business")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("business").contains("technical");
            });
        assertThat(cli.launches()).isEmpty();
    }

    @Test
    void theChangesRunTheTagFormsOfTheirScope() {
        ScriptedProcessLauncher cli = new ScriptedProcessLauncher();
        List<String> forms = List.of("tag --tagSetActive 'REL-1' --tagUser 'jdoe'",
            "tag --tagRemoveActive --tagUser 'jdoe'",
            "tag --tagRemove 'REL-1' --tagWorkflowGroup 'GRP-01' --tagWorkflowType 'technical'",
            "tag --tagDelete 'REL-1' --tagRepositoryPath '/Root/jdoe/xsd'");
        forms.forEach(form -> cli.expect(Pattern.compile(Pattern.quote(form))).replying("tag_ok"));
        TagAdministrationPort adapter = adapter(cli);

        adapter.setActiveTag("jdoe", "REL-1", PERMIT);
        adapter.removeActiveTag("jdoe", PERMIT);
        adapter.removeTag(Removal.REMOVE, "REL-1", new Scope.DiagramGroup("GRP-01", "technical"),
            PERMIT);
        adapter.removeTag(Removal.DELETE, "REL-1", new Scope.RepositoryPath("/Root/jdoe/xsd"),
            PERMIT);

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
