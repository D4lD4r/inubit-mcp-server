package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Feature 008 (T009, research D-1, D-2): the effective {@code development.repository}. It is
 * {@code WRITE} only if it resolves to {@code WRITE} (node, group, {@code defaults}, built-in)
 * and the node is a development stage ({@code enabled} and not {@code production}).
 *
 * <p>Feature 009 (T005, research D-2): the effective {@code versionLine}, node → group →
 * {@code AUTO}, and its configured text in the {@code list_nodes} summary.
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
            null, null, null, null, null, development, null, null, null, null);
    }

    private static boolean repositoryWrite(Defaults defaults, GroupConfig group) {
        EffectiveNodeConfig server =
            EffectiveNodeConfig.resolve(defaults, group, group.nodes().get(0), "INUBIT");
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
            EffectiveNodeConfig.resolve(Defaults.EMPTY, group, group.nodes().get(0), "INUBIT");

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
            null);
        return EffectiveNodeConfig.resolve(Defaults.EMPTY, group, node, "INUBIT").versionLine();
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
                EffectiveNodeConfig.resolve(Defaults.EMPTY, group, node, "INUBIT");

            assertThat(server.summary(path -> false, false).versionLine())
                .isEqualTo(setting.text());
        }
        assertThat(PINNED.text()).isEqualTo("9.0.7-HF1");
    }
}
