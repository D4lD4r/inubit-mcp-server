package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 008 (T009, research D-1, D-2): the effective {@code development.repository}. It is
 * {@code WRITE} only if it resolves to {@code WRITE} (node, group, {@code defaults}, built-in)
 * and the node is a development stage ({@code enabled} and not {@code production}).
 *
 * <p>Feature 009 (T005, research D-2): the effective {@code versionLine}, node → group →
 * {@code AUTO}, and its configured text in the {@code list_nodes} summary.
 *
 * <p>Feature 015 (T016, research R-15): {@code archivesProductionOptIn()} is the group's
 * {@code archives.productionOptIn}, built-in {@code false}.
 *
 * <p>Feature 014 (T006): the effective TLS of a stage marked with {@code tls.pinPerServer}.
 */
class EffectiveNodeConfigTest {

    private static final DevelopmentConfig WRITE = repository(RepositoryMode.WRITE);
    private static final DevelopmentConfig READ_ONLY = repository(RepositoryMode.READ_ONLY);
    private static final DevelopmentConfig ENABLED =
        new DevelopmentConfig(Optional.of(true), Optional.empty(), Optional.empty());

    private static DevelopmentConfig repository(RepositoryMode mode) {
        return new DevelopmentConfig(Optional.empty(), Optional.empty(), Optional.of(mode));
    }

    private static DevelopmentConfig enabledWith(RepositoryMode mode) {
        return new DevelopmentConfig(Optional.of(true), Optional.empty(), Optional.of(mode));
    }

    private static Defaults defaults(DevelopmentConfig development) {
        return new Defaults(null, null, null, null, null, null, null, null, development, null,
            null);
    }

    private static NodeConfig node(DevelopmentConfig development) {
        return new NodeConfig("node1", Optional.of(URI.create("https://inubit.example.test:8443")),
            false, null, null, null, null, null, null, null, null, null, null, development, null,
            null, null);
    }

    private static GroupConfig group(boolean production, DevelopmentConfig development,
        NodeConfig node) {
        return new GroupConfig("dev", production, List.of(node), null, null, null, null, null,
            null, null, null, null, null, development, null, null, null, null, null);
    }

    /** Resolves without any {@code credentials} section (prefix {@code INUBIT}). */
    private static EffectiveNodeConfig resolve(Defaults defaults, GroupConfig group,
        NodeConfig node) {
        return EffectiveNodeConfig.resolve(defaults, group, node, "INUBIT",
            SecretPlan.resolve(CredentialsConfig.EMPTY, "INUBIT", group, node));
    }

    private static boolean repositoryWrite(Defaults defaults, GroupConfig group) {
        EffectiveNodeConfig server =
            resolve(defaults, group, group.nodes().get(0));
        return server.development().repositoryWrite();
    }

    @Test
    void theBuiltInIsReadOnly() {
        assertThat(repositoryWrite(Defaults.EMPTY, group(false, ENABLED, node(null)))).isFalse();
        assertThat(Defaults.BUILTIN_DEVELOPMENT_REPOSITORY).isEqualTo(RepositoryMode.READ_ONLY);
        assertThat(EffectiveNodeConfig.NO_DEVELOPMENT.repositoryWrite()).isFalse();
    }

    @Test
    void writeTakesEffectOnADevelopmentStageFromEveryLevel() {
        assertThat(repositoryWrite(Defaults.EMPTY, group(false, ENABLED, node(WRITE)))).isTrue();
        assertThat(repositoryWrite(Defaults.EMPTY,
            group(false, enabledWith(RepositoryMode.WRITE), node(null)))).isTrue();
        assertThat(repositoryWrite(defaults(WRITE), group(false, ENABLED, node(null)))).isTrue();
    }

    @Test
    void theNodeWinsOverTheGroupAndTheGroupOverTheDefaults() {
        assertThat(repositoryWrite(Defaults.EMPTY,
            group(false, enabledWith(RepositoryMode.WRITE), node(READ_ONLY)))).isFalse();
        assertThat(repositoryWrite(defaults(WRITE),
            group(false, enabledWith(RepositoryMode.READ_ONLY), node(null)))).isFalse();
        assertThat(repositoryWrite(defaults(READ_ONLY),
            group(false, enabledWith(RepositoryMode.READ_ONLY), node(WRITE)))).isTrue();
    }

    @Test
    void writeHasNoEffectOnANodeThatIsNoDevelopmentStage() {
        assertThat(repositoryWrite(defaults(WRITE), group(false, null, node(null)))).isFalse();
        assertThat(repositoryWrite(Defaults.EMPTY, group(false, WRITE, node(WRITE)))).isFalse();
        assertThat(repositoryWrite(Defaults.EMPTY,
            group(false, ENABLED, node(new DevelopmentConfig(Optional.of(false),
                Optional.empty(), Optional.of(RepositoryMode.WRITE)))))).isFalse();
    }

    @Test
    void writeHasNoEffectOnAProductionGroup() {
        assertThat(repositoryWrite(defaults(WRITE), group(true, ENABLED, node(WRITE)))).isFalse();
    }

    @Test
    void theDevelopmentPolicyAndWithTimeoutKeepTheEffectiveSwitch() {
        GroupConfig group = group(false, ENABLED, node(WRITE));
        EffectiveNodeConfig server =
            resolve(Defaults.EMPTY, group, group.nodes().get(0));

        assertThat(server.developmentPolicy().repositoryWrite()).isTrue();
        assertThat(server.withTimeout(Duration.ofSeconds(1)).development().repositoryWrite())
            .isTrue();
    }

    private static final VersionSetting PINNED = new VersionSetting.Pinned(
        new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7-HF1"));

    private static VersionSetting versionLine(VersionSetting groupValue,
        VersionSetting nodeValue) {
        NodeConfig node = new NodeConfig("node1",
            Optional.of(URI.create("https://inubit.example.test:8443")), false,
            Optional.ofNullable(nodeValue), null, null, null, null, null, null, null, null, null,
            null, null, null, null);
        GroupConfig group = new GroupConfig("dev", false, List.of(node), null, null, null, null,
            Optional.ofNullable(groupValue), null, null, null, null, null, null, null, null, null,
            null, null);
        return resolve(Defaults.EMPTY, group, node).versionLine();
    }

    @Test
    void theVersionLineDefaultsToAuto() {
        assertThat(versionLine(null, null)).isEqualTo(VersionSetting.AUTO);
        assertThat(Defaults.BUILTIN_VERSION_LINE).isEqualTo(VersionSetting.AUTO);
    }

    @Test
    void theNodeVersionLineWinsOverTheGroup() {
        assertThat(versionLine(VersionSetting.V9_X, null)).isEqualTo(VersionSetting.V9_X);
        assertThat(versionLine(VersionSetting.V9_X, PINNED)).isEqualTo(PINNED);
        assertThat(versionLine(PINNED, VersionSetting.V8_1)).isEqualTo(VersionSetting.V8_1);
        assertThat(versionLine(null, VersionSetting.AUTO)).isEqualTo(VersionSetting.AUTO);
    }

    @Test
    void theSummaryShowsTheConfiguredText() {
        for (VersionSetting setting : List.of(VersionSetting.AUTO, VersionSetting.V8_1,
            VersionSetting.V9_X, PINNED)) {
            NodeConfig node = new NodeConfig("node1",
                Optional.of(URI.create("https://inubit.example.test:8443")), false,
                Optional.of(setting), null, null, null, null, null, null, null, null, null, null,
                null, null, null);
            GroupConfig group = group(false, null, node);
            EffectiveNodeConfig server =
                resolve(Defaults.EMPTY, group, node);

            assertThat(server.summary(path -> false, false).versionLine())
                .isEqualTo(setting.text());
        }
        assertThat(PINNED.text()).isEqualTo("9.0.7-HF1");
    }

    private static boolean archivesProductionOptIn(boolean production, ArchivesConfig archives) {
        NodeConfig node = node(null);
        GroupConfig group = new GroupConfig("prod", production, List.of(node), null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, archives);
        return resolve(Defaults.EMPTY, group, node).archivesProductionOptIn();
    }

    @Test
    void theArchivesOptInIsTheGroupValueAndFalseByDefault() {
        assertThat(archivesProductionOptIn(true, null)).isFalse();
        assertThat(archivesProductionOptIn(true, ArchivesConfig.EMPTY)).isFalse();
        assertThat(archivesProductionOptIn(true, new ArchivesConfig(Optional.of(false))))
            .isFalse();
        assertThat(archivesProductionOptIn(true, new ArchivesConfig(Optional.of(true))))
            .isTrue();
        assertThat(archivesProductionOptIn(false, new ArchivesConfig(Optional.of(true))))
            .as("the value as configured; the service decides by production")
            .isTrue();
    }

    @Test
    void theArchivesOptInIsIndependentOfTheWriteSettingsAndKeptByWithTimeout() {
        NodeConfig node = node(null);
        GroupConfig group = new GroupConfig("prod", true, List.of(node),
            new WriteConfig(Optional.of(true), Optional.of(true), Optional.empty()), null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
        EffectiveNodeConfig withWrite = resolve(Defaults.EMPTY, group, node);
        GroupConfig optedIn = new GroupConfig("prod", true, List.of(node),
            new WriteConfig(Optional.of(false), Optional.empty(), Optional.empty()), null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            new ArchivesConfig(Optional.of(true)));
        EffectiveNodeConfig archives = resolve(Defaults.EMPTY, optedIn, node);

        assertThat(withWrite.effectiveWriteEnabled()).isTrue();
        assertThat(withWrite.archivesProductionOptIn()).isFalse();
        assertThat(archives.effectiveWriteEnabled()).isFalse();
        assertThat(archives.archivesProductionOptIn()).isTrue();
        assertThat(archives.withTimeout(Duration.ofSeconds(1)).archivesProductionOptIn())
            .isTrue();
    }

    private static final String PIN_A =
        "BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:BB:22:F0:03";
    private static final String PIN_B =
        "CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:CC:33:0E:04";

    /**
     * Feature 014 (T006, FR-002, FR-003, research R-4), regression: in a stage marked with
     * {@code tls.pinPerServer} every server keeps the stage's one trust store and checks only its
     * own pin. The existing node-before-group resolution already does this.
     */
    @Test
    void aMarkedStageGivesEachServerTheStageTrustStoreAndItsOwnPin(@TempDir Path home)
        throws URISyntaxException {
        Path file = Path.of(
            EffectiveNodeConfigTest.class.getResource("/config/pin-per-server.yaml").toURI());
        ProfileConfig config = new ConfigLoader(Map.of(), home, false).loadFile(file).config();
        GroupConfig stage = config.groups().stream()
            .filter(g -> g.name().equals("int"))
            .findFirst()
            .orElseThrow();
        Map<String, Optional<String>> effectivePins = new HashMap<>();

        assertThat(stage.tls().trustStore()).isPresent();
        assertThat(stage.tls().pinnedCertificateSha256()).isEmpty();
        for (NodeConfig node : stage.nodes()) {
            EffectiveNodeConfig.Tls tls = config.effectiveNodes().stream()
                .filter(s -> s.id().equals(NodeId.of("int", node.name())))
                .findFirst()
                .orElseThrow()
                .tls();

            assertThat(tls.trustStore()).isEqualTo(stage.tls().trustStore());
            effectivePins.put(node.name(), tls.pinnedCertificateSha256());
        }
        // Each server has exactly its own pin, so none has the other server's pin.
        assertThat(effectivePins)
            .isEqualTo(Map.of("a", Optional.of(PIN_A), "b", Optional.of(PIN_B)));
    }

    /**
     * Feature 014 (T012, FR-005a, research R-15): the effective {@code Tls.pinPerServer} is the
     * stage's {@code tls.pinPerServer}; it decides whether StartCLI calls are probed first.
     */
    @Test
    void pinPerServerIsTrueForTheServersOfAMarkedStageOnly(@TempDir Path home)
        throws URISyntaxException {
        Path file = Path.of(
            EffectiveNodeConfigTest.class.getResource("/config/pin-per-server.yaml").toURI());
        ProfileConfig config = new ConfigLoader(Map.of(), home, false).loadFile(file).config();
        Map<NodeId, Boolean> pinPerServer = new HashMap<>();

        for (EffectiveNodeConfig server : config.effectiveNodes()) {
            pinPerServer.put(server.id(), server.tls().pinPerServer());
        }

        assertThat(pinPerServer).isEqualTo(Map.of(NodeId.parse("dev/node1"), false,
            NodeId.parse("int/a"), true, NodeId.parse("int/b"), true));
    }

    @Test
    void theThreeArgumentTlsConstructorIsNotPinPerServer() {
        EffectiveNodeConfig.Tls tls = new EffectiveNodeConfig.Tls(Optional.empty(), true,
            Optional.of(PIN_A));

        assertThat(tls.pinPerServer()).isFalse();
        assertThat(tls.disableHostnameVerification()).isTrue();
        assertThat(tls.pinnedCertificateSha256()).contains(PIN_A);
    }
}
