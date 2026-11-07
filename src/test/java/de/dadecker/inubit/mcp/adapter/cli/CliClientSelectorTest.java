package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Feature 017 (T019, research D-7, D-19): {@link CliClientSelector} tells which StartCLI client a
 * node uses at a version, whether it exists and whether any client can serve the node, without
 * contacting the node, launching a process or reading the node's version.
 */
class CliClientSelectorTest {

    private static final NodeId ID = NodeId.parse("dev/node1");
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final String SERVLET_URL = BASE_URL + EffectiveNodeConfig.DEFAULT_CLI_PATH;
    private static final Path CLIENT_81 = Path.of("/opt/acme/startcli-8.1");
    private static final Path CLIENT_90 = Path.of("/opt/acme/startcli-9.0");
    private static final Path CLIENT_DEFAULT = Path.of("/opt/acme/startcli");
    private static final Path JDK_17 = Path.of("/opt/jdk-17");
    private static final Path JDK_21 = Path.of("/opt/jdk-21");
    private static final Optional<InubitVersion> V8_1_17 =
        Optional.of(new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17"));
    private static final Optional<InubitVersion> V9_0_7 =
        Optional.of(new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7"));

    private final FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
    private final AtomicInteger versionReads = new AtomicInteger();
    private final Set<Path> existing = new HashSet<>();

    private static TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl(BASE_URL).versionLine(VersionSetting.AUTO)
            .cliClient("8.1", CLIENT_81, Optional.of(JDK_17))
            .cliClient("9.0", CLIENT_90, Optional.of(JDK_21));
    }

    private ClientSelectionPort selector(EffectiveNodeConfig server, boolean windows) {
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of(),
            new CliResources("acme", PrivateFiles.forHost()), id -> {
                versionReads.incrementAndGet();
                return Optional.empty();
            });
        return new CliClientSelector(runner, id -> server, existing::contains, windows);
    }

    private ClientSelectionPort selector(EffectiveNodeConfig server) {
        return selector(server, false);
    }

    private void install(Path home) {
        existing.add(home.resolve("bin").resolve("startcli.sh"));
    }

    @Test
    void theCurrentClientIsTheOneChosenForTheGivenVersion() {
        ClientSelectionPort selector = selector(node().build());

        assertThat(selector.current(ID, V8_1_17))
            .contains(new ClientKey(CLIENT_81.toString(), JDK_17.toString()));
        assertThat(selector.current(ID, V9_0_7))
            .contains(new ClientKey(CLIENT_90.toString(), JDK_21.toString()));
    }

    @Test
    void withoutAnyClientForTheVersionThereIsNoCurrentClient() {
        assertThat(selector(node().build()).current(ID, Optional.empty())).isEmpty();
        assertThat(selector(node().defaultCliHome(CLIENT_DEFAULT).build())
            .current(ID, Optional.empty()))
            .contains(new ClientKey(CLIENT_DEFAULT.toString(), ""));
    }

    @Test
    void aClientIsAvailableOnlyIfItsStartCliExists() {
        install(CLIENT_81);
        ClientSelectionPort selector = selector(node().build());

        assertThat(selector.available(ID, V8_1_17)).isTrue();
        assertThat(selector.available(ID, V9_0_7)).isFalse();
        assertThat(selector.available(ID, Optional.empty())).isFalse();
    }

    @Test
    void onWindowsTheBatchFileCounts() {
        existing.add(CLIENT_90.resolve("bin").resolve("startcli.bat"));

        assertThat(selector(node().build(), true).available(ID, V9_0_7)).isTrue();
        assertThat(selector(node().build(), false).available(ID, V9_0_7)).isFalse();
    }

    /** As {@link #node()}, without {@code cliClients} entries. */
    private static TestNodeConfig bare() {
        return TestNodeConfig.node().baseUrl(BASE_URL).versionLine(VersionSetting.AUTO);
    }

    @Test
    void anAutoNodeHasACandidateIfAnyEntryOrAVersionlessHomeIsConfigured() {
        // a configured home counts, as cli.home did for the announcement in 0.8.0 (FR-009)
        assertThat(selector(bare().build()).anyCandidate(ID)).isFalse();
        assertThat(selector(bare().cliClient("9.0", CLIENT_90, Optional.of(JDK_21)).build())
            .anyCandidate(ID)).isTrue();
        assertThat(selector(bare().defaultCliHome(CLIENT_DEFAULT).build()).anyCandidate(ID))
            .isTrue();
        assertThat(selector(bare().cliHome(CLIENT_DEFAULT).build()).anyCandidate(ID)).isTrue();
        assertThat(existing).isEmpty();
    }

    @Test
    void aNodePinnedWithMinorConsidersOnlyItsOwnChoice() {
        TestNodeConfig only90 = bare().cliClient("9.0", CLIENT_90, Optional.of(JDK_21));

        assertThat(selector(only90.versionLine(VersionSetting.V8_1).build()).anyCandidate(ID))
            .isFalse();
        assertThat(selector(only90.versionLine(VersionSetting.parse("8.1.17").orElseThrow())
            .build()).anyCandidate(ID)).isFalse();

        assertThat(selector(only90.versionLine(VersionSetting.V8_1)
            .cliClient("8.1", CLIENT_81, Optional.of(JDK_17)).build()).anyCandidate(ID))
            .isTrue();
        assertThat(selector(bare().cliClient("9.0", CLIENT_90, Optional.of(JDK_21))
            .versionLine(VersionSetting.parse("8.1.17").orElseThrow())
            .defaultCliHome(CLIENT_DEFAULT).build()).anyCandidate(ID)).isTrue();
    }

    @Test
    void aNodePinnedWithoutMinorConsidersOnlyTheVersionlessSources() {
        EffectiveNodeConfig wholeLine = node().versionLine(VersionSetting.V9_X).build();

        assertThat(selector(wholeLine).anyCandidate(ID)).isFalse();
        assertThat(selector(node().versionLine(VersionSetting.V9_X)
            .defaultCliHome(CLIENT_DEFAULT).build()).anyCandidate(ID)).isTrue();
    }

    @Test
    void theNodesOwnHomeOverridesEveryEntry() {
        EffectiveNodeConfig ownHome = node().versionLine(VersionSetting.V9_X)
            .cliHome(CLIENT_DEFAULT).build();

        assertThat(selector(ownHome).anyCandidate(ID)).isTrue();
        assertThat(selector(ownHome).current(ID, V9_0_7))
            .contains(new ClientKey(CLIENT_DEFAULT.toString(), ""));
    }

    @Test
    void anExplicitServletAddressWarnsFromNineOn() {
        ClientSelectionPort selector = selector(node().cliUrl(SERVLET_URL).build());

        assertThat(selector.warning(ID, V9_0_7)).contains("cli.url of dev/node1 uses"
            + " /ibis/servlet/IBISSoapServlet, which INUBIT 9.0.2 and later removed; remove"
            + " cli.url or set it to the base URL");
        assertThat(selector.warning(ID, V8_1_17)).isEmpty();
        assertThat(selector(node().build()).warning(ID, V9_0_7)).isEmpty();
    }

    @Test
    void nothingIsLaunchedAndNoVersionIsRead() {
        install(CLIENT_81);
        install(CLIENT_90);
        ClientSelectionPort selector = selector(node().cliUrl(SERVLET_URL).build());

        selector.current(ID, V9_0_7);
        selector.available(ID, V8_1_17);
        selector.anyCandidate(ID);
        selector.warning(ID, V9_0_7);

        assertThat(launcher.launchCount()).isZero();
        assertThat(versionReads).hasValue(0);
    }

    // --- T026: list_nodes.startCli (research D-18) ---------------------------------------------

    @Test
    void theStartCliOfAKnownVersionNamesItsEntryAndAddressAndIsFinal() {
        ClientSelectionPort selector = selector(node().build());

        assertThat(selector.startCli(ID, V9_0_7)).isEqualTo(new NodeSummary.StartCli(
            Optional.of(CLIENT_90.toString()), "version_entry", Optional.of("9.0"), "base_url",
            true));
        assertThat(selector.startCli(ID, V8_1_17)).isEqualTo(new NodeSummary.StartCli(
            Optional.of(CLIENT_81.toString()), "version_entry", Optional.of("8.1"),
            "servlet_path", true));
        assertThat(versionReads).as("the caller's version, never re-read").hasValue(0);
    }

    @Test
    void anAutoNodeOfUnknownVersionHasTheVersionLessChoiceWhichIsNotFinal() {
        assertThat(selector(node().defaultCliHome(CLIENT_DEFAULT).build())
            .startCli(ID, Optional.empty())).isEqualTo(new NodeSummary.StartCli(
                Optional.of(CLIENT_DEFAULT.toString()), "defaults", Optional.empty(),
                "servlet_path", false));
        assertThat(selector(node().build()).startCli(ID, Optional.empty()))
            .isEqualTo(new NodeSummary.StartCli(Optional.empty(), "none", Optional.empty(),
                "servlet_path", false));
    }

    @Test
    void aNodeHomeAndAConfiguredAddressAreNamedWithoutTheUrl() {
        NodeSummary.StartCli startCli = selector(node().cliHome(CLIENT_DEFAULT)
            .cliUrl(SERVLET_URL).versionLine(VersionSetting.parse("9.0.7").orElseThrow())
            .build()).startCli(ID, V9_0_7);

        assertThat(startCli).isEqualTo(new NodeSummary.StartCli(
            Optional.of(CLIENT_DEFAULT.toString()), "node", Optional.empty(), "configured",
            true));
        assertThat(startCli.toString()).doesNotContain("://", "example.test");
    }

    @Test
    void aPinnedOrLinedNodeIsFinalEvenWithoutAVersion() {
        assertThat(selector(node().versionLine(VersionSetting.V9_X).build())
            .startCli(ID, Optional.empty()).finalChoice()).isTrue();
    }
}
