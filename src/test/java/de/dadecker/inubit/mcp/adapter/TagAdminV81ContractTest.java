package de.dadecker.inubit.mcp.adapter;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.admin.CliTagAdminRunner;
import de.dadecker.inubit.mcp.application.ActionSpec;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.TagAdministrationService;
import de.dadecker.inubit.mcp.application.VersionGate;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageEntry;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.CoverageState;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.TagAdministrationPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T066 (feature 022, US4, research D-18, L-4): {@code manage_tags} against what T055 recorded on
 * an 8.1.17 server. Only the active-tag actions remain (owner decision IMC-7); they are not
 * verified on the test node (active tagging is off there: {@code --tagSetActive} answered
 * {@code TagNameWasNotFound}, run 4), so their rows stay {@code planned} and the gate refuses them
 * as "not yet verified" on 8.1 and 9.0.
 *
 * <ul>
 *   <li>The read: the recorded session of a user without an active tag
 *       ({@code fixtures/v8_1/cli/admin-tags-user-no-active-tag-session}, exit 1) through
 *       {@link V81TagAdministrationAdapter} with a {@link CliTagAdminRunner} as wired in
 *       production;
 *   <li>the rows: the active-tag pair {@code planned} with {@code waits} {@code L-4+9.0-followup},
 *       the removal forms excluded on both lines.
 * </ul>
 */
@Timeout(30)
class TagAdminV81ContractTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId DEV_90 = NodeId.parse("dev/node2");
    private static final String PASSWORD = "S3cr3t-tag-contract-pw";
    private static final MajorMinor V8_1 = new MajorMinor(8, 1);
    private static final MajorMinor V9_0 = new MajorMinor(9, 0);

    @TempDir
    Path cliHome;

    @BeforeEach
    void installFakeCli() throws IOException {
        FakeStartCli.install(cliHome);
    }

    private TagAdministrationPort adapter(FakeProcessLauncher launcher) {
        NodeCredentials credentials = new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
            new MutableClock(Instant.parse("2026-11-19T08:00:00Z")));
        return new V81TagAdministrationAdapter(new CliTagAdminRunner(TestNodeConfig.node()
            .baseUrl("https://inubit-dev-1.example.test:8443").cliHome(cliHome)
            .cliJavaHome(Path.of("/opt/jdk-17")).build(), credentials, guard,
            TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
                new CliResources("acme", PrivateFiles.forHost())),
            new CliOutputClassifier(new SecretScrubber(), storeLabels("INUBIT", DEV), false)));
    }

    /** The session exits with 1 although no command was refused (T055 run 1). */
    @Test
    void theRecordedSessionOfAUserWithoutAnActiveTagNamesNone() {
        FakeProcessLauncher launcher =
            FakeProcessLauncher.replaying("admin-tags-user-no-active-tag-session");

        assertThat(adapter(launcher).activeTag("jdoe")).isEmpty();

        assertThat(launcher.launchCount()).isEqualTo(1);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(FakeProcessLauncher.fixtureText(
                "admin-tags-user-no-active-tag-session.stdin").replace("<password>", PASSWORD));
    }

    /** {@code --tagUser} is shared with {@code tag_artifacts}, where it stays reachable. */
    @Test
    void theActiveTagRowsStayPlannedOnBothLinesWaitingForL4AndThe90FollowUp() {
        InterfaceCoverage coverage = InterfaceCoverage.production();
        Map<String, String> entries = Map.of("setActiveTag", "tag --tagSetActive",
            "removeActiveTag", "tag --tagRemoveActive");

        assertThat(TagAdministrationService.SPECS).extracting(ActionSpec::action)
            .containsExactlyInAnyOrderElementsOf(entries.keySet());
        for (ActionSpec spec : TagAdministrationService.SPECS) {
            CoverageKey key = new CoverageKey(Channel.CLI, entries.get(spec.action()));
            assertThat(spec.declaredEntries()).as(spec.action())
                .containsExactlyInAnyOrder(key, new CoverageKey(Channel.CLI, "tag --tagUser"));
            CoverageEntry row = coverage.entries().stream()
                .filter(entry -> entry.key().equals(key)).findFirst().orElseThrow();
            assertThat(row.states()).as("%s", key)
                .containsEntry(V8_1, new CoverageState.Planned())
                .containsEntry(V9_0, new CoverageState.Planned());
            assertThat(row.story()).contains("US4");
            assertThat(row.waits()).as("%s", key).containsExactly("L-4", "9.0-followup");
        }
        for (MajorMinor line : List.of(V8_1, V9_0)) {
            assertThat(coverage.state(Channel.CLI, "tag --tagUser", line))
                .contains(new CoverageState.Reachable("tag_artifacts", "default"));
        }
    }

    /** Owner decision IMC-7 (research D-18): no removal form is sent on either line. */
    @Test
    void theRemovalFormsAreExcludedOnBothLines() {
        InterfaceCoverage coverage = InterfaceCoverage.production();

        for (String entry : List.of("tag --tagDelete", "tag --tagRemove",
            "tag --tagRepositoryPath")) {
            for (MajorMinor line : List.of(V8_1, V9_0)) {
                assertThat(coverage.state(Channel.CLI, entry, line)).as("%s %s", entry, line)
                    .hasValueSatisfying(state ->
                        assertThat(state).isInstanceOf(CoverageState.Excluded.class));
            }
        }
        assertThat(coverage.state(Channel.CLI, "tag --tagDelete", V8_1))
            .contains(new CoverageState.Excluded("acts on the whole owner: deletes the tag in"
                + " every diagram group although one is named (INUBIT 8.1.17, T055)"));
    }

    @Test
    void theProductionGateRefusesBothActionsOn81And90AsNotYetVerified() {
        NodeVersions versions = new NodeVersions(new MutableClock(Instant.parse(
            "2026-11-19T08:00:00Z")), Map.of(DEV, VersionSetting.V8_1, DEV_90,
            VersionSetting.V9_0)::get, node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), CapabilityMatrix.production(),
            InterfaceCoverage.production());

        for (ActionSpec spec : TagAdministrationService.SPECS) {
            for (NodeId node : List.of(DEV, DEV_90)) {
                assertThatThrownBy(() -> gate.admitCall(TagAdministrationService.TOOL, node, spec,
                    spec.callEntries()))
                    .as("%s on %s", spec.action(), node)
                    .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                        assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                        assertThat(e.error().message()).contains("not yet verified");
                    });
            }
        }
    }
}
