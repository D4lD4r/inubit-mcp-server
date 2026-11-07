package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeSummary;
import de.dadecker.inubit.mcp.domain.model.ProbedClient;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ClientSelectionPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Feature 017 (FR-017, research D-7): {@code list_nodes} reports the StartCLI client chosen for
 * the node's version as of the call: {@code cliAvailable}, the client block and the workspace
 * export follow that client, not one probed for another version.
 */
class NodeOverviewClientKeyTest {

    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z");
    private static final ClientKey KEY_81 = new ClientKey("/opt/acme/startcli-8.1", "");
    private static final ClientKey KEY_90 = new ClientKey("/opt/acme/startcli-9.0", "");
    private static final InubitVersion V8_1_17 = version("8.1.17");
    private static final ClientVersion CLIENT_81 = new ClientVersion(V8_1_17,
        Optional.of(version("4.0.1")), Optional.of(V8_1_17));

    private final NodeVersions versions = new NodeVersions(new MutableClock(NOW),
        node -> VersionSetting.AUTO, node -> Duration.ofMinutes(10));
    private final NodeSummary node = TestNodeConfig.node().versionLine(VersionSetting.AUTO)
        .build().summary(path -> false, false);
    /** Each call of the configured capabilities: whether the current client is installed. */
    private final List<Boolean> withClient = new CopyOnWriteArrayList<>();

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }

    /** 8.x nodes use the installed 8.1 client, 9.x nodes the 9.0 client, which is missing. */
    private final ClientSelectionPort selection = new ClientSelectionPort() {
        @Override
        public Optional<ClientKey> current(NodeId id, Optional<InubitVersion> version) {
            return version.map(v -> v.major() >= 9 ? KEY_90 : KEY_81);
        }

        @Override
        public boolean available(NodeId id, Optional<InubitVersion> version) {
            return current(id, version).filter(KEY_81::equals).isPresent();
        }

        @Override
        public boolean anyCandidate(NodeId id) {
            return true;
        }

        @Override
        public Optional<String> warning(NodeId id, Optional<InubitVersion> version) {
            return Optional.empty();
        }
    };

    private NodeSummary listed() {
        Set<Capability> exports = EnumSet.of(Capability.INVENTORY, Capability.WORKSPACE_EXPORT);
        return new NodeOverview(List.of(node), versions, CapabilityMatrix.production(),
            (id, installed) -> {
                withClient.add(installed);
                return installed ? exports : EnumSet.of(Capability.INVENTORY);
            }, selection).nodes().getFirst();
    }

    @Test
    void theInstalledClientOfTheKnownVersionIsListed() {
        versions.observed(node.id(), V8_1_17);
        versions.clientProbed(node.id(), new ProbedClient(KEY_81, CLIENT_81));

        NodeSummary listed = listed();

        assertThat(listed.cliAvailable()).isTrue();
        assertThat(withClient).containsExactly(true);
        NodeSummary.Version version = listed.version().orElseThrow();
        assertThat(version.client().version()).contains("8.1.17");
        assertThat(version.capabilities().usable()).contains("workspace_export");
    }

    @Test
    void afterAnUpgradeTheMissingClientOfTheNewVersionIsListed() {
        versions.observed(node.id(), V8_1_17);
        versions.clientProbed(node.id(), new ProbedClient(KEY_81, CLIENT_81));
        versions.observed(node.id(), version("9.0.7"));

        NodeSummary listed = listed();

        assertThat(listed.cliAvailable()).isFalse();
        assertThat(withClient).containsExactly(false);
        NodeSummary.Version version = listed.version().orElseThrow();
        assertThat(version.client().version()).isEmpty();
        assertThat(version.client().state()).contains("not_available");
        assertThat(version.capabilities().usable()).doesNotContain("workspace_export");
        assertThat(version.warnings().orElse(List.of()))
            .noneMatch(warning -> warning.contains("8.1.17"));
    }

    @Test
    void anInstalledClientNotProbedForTheVersionIsNotYetDetected() {
        versions.clientProbed(node.id(), new ProbedClient(KEY_90, CLIENT_81));
        versions.observed(node.id(), V8_1_17);

        NodeSummary.Version version = listed().version().orElseThrow();

        assertThat(version.client().version()).isEmpty();
        assertThat(version.client().state()).contains("not_yet_detected");
    }
}
