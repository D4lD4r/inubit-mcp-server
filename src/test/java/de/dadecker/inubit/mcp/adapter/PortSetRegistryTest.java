package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61PortSet;
import de.dadecker.inubit.mcp.config.CredentialResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeAccess;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 011 T025: the 6.1 registration (research D-4). The registry is built as {@code Wiring}
 * builds it: the 8.1 port set as default, the 6.1 port set and its StartCLI health port for
 * {@link V61PortSet#RANGE}, CLI-only.
 */
class PortSetRegistryTest {

    private final PortSetRegistry registry = PortSetRegistry.v81Default()
        .register(V61PortSet.RANGE, V61PortSet::create, V61PortSet::cliHealth);

    private static Optional<InubitVersion> effective(VersionSetting setting) {
        return NodeVersionState.initial(setting).effectiveVersion();
    }

    @Test
    void sixOneIsSelectedForTheV6_1LineAndThePinned610() {
        for (VersionSetting setting : List.of(VersionSetting.V6_1,
            VersionSetting.parse("6.1.0").orElseThrow())) {
            PortSetRegistry.Selection selection = registry.select(effective(setting));

            assertThat(selection.range()).as(setting.toString()).contains(V61PortSet.RANGE);
            assertThat(selection.access()).as(setting.toString()).isEqualTo(NodeAccess.CLI_ONLY);
            assertThat(selection.cliHealth()).as(setting.toString()).isPresent();
        }
    }

    @Test
    void every81And9xAndUnknownVersionKeepsTheDefaultWithRestAndCli() {
        PortSetRegistry.Factory defaultFactory = registry.select(Optional.empty()).factory();
        for (String version : List.of("6.0.4", "6.2", "8.1.17", "9.0.7", "10.2")) {
            PortSetRegistry.Selection selection = registry.select(InubitVersion.parse(version));

            assertThat(selection.range()).as(version).isEmpty();
            assertThat(selection.factory()).as(version).isSameAs(defaultFactory);
            assertThat(selection.access()).as(version).isEqualTo(NodeAccess.REST_AND_CLI);
            assertThat(selection.cliHealth()).as(version).isEmpty();
        }
        assertThat(registry.select(Optional.empty()).access()).isEqualTo(NodeAccess.REST_AND_CLI);
    }

    @Test
    void aRangeRegisteredWithoutCliHealthFactoryIsRestAndCli() {
        VersionRange v79 = new VersionRange(7, 9, 7, 9);
        PortSetRegistry.Factory factory = node -> {
            throw new AssertionError("not built");
        };
        PortSetRegistry.Selection selection = registry.register(v79, factory)
            .select(InubitVersion.parse("7.9.3"));

        assertThat(selection.range()).contains(v79);
        assertThat(selection.factory()).isSameAs(factory);
        assertThat(selection.access()).isEqualTo(NodeAccess.REST_AND_CLI);
        assertThat(selection.cliHealth()).isEmpty();
    }

    @Test
    void theSixOnePortSetIsBuiltWithoutRestClientAndRefusesEveryPort() {
        PortSetRegistry.NodeResources node = sixOneResources();

        PortSet ports = registry.select(effective(VersionSetting.V6_1)).factory().create(node);

        assertThat(List.of(ports.processes(), ports.logs(), ports.inventory(),
            ports.processControl(), ports.artifacts(), ports.imports(), ports.tags()))
            .allSatisfy(port -> assertThat(port).isInstanceOf(RefusingPorts.class));
        assertThatThrownBy(() -> ports.logs().query(null))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
    }

    @Test
    void theSixOneHealthPortRefusesUntilItsStartCliAdapterExists() {
        PortSetRegistry.NodeResources node = sixOneResources();

        CliHealthPort health = registry.select(effective(VersionSetting.V6_1)).cliHealth()
            .orElseThrow().create(node);

        assertThatThrownBy(() -> health.snapshot(Duration.ofSeconds(15)))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
                assertThat(e.error().node()).contains(node.server().id());
            });
    }

    /** The resources of a 6.1 node as {@code AdapterGatewayFactory} passes them: no client. */
    private static PortSetRegistry.NodeResources sixOneResources() {
        SecretScrubber scrubber = new SecretScrubber();
        EffectiveNodeConfig server = TestNodeConfig.node().versionLine(VersionSetting.V6_1)
            .build();
        NodeCredentials credentials = new CredentialResolver(
            Map.of("INUBIT_DEV_USERNAME", "jdoe", "INUBIT_DEV_PASSWORD", "registry-test-pw"),
            scrubber, "INUBIT").resolve(List.of(server.id())).credentials(server.id());
        return new PortSetRegistry.NodeResources(server, credentials,
            new CredentialGuard(server.secretLabels(), Clock.systemUTC()), null,
            new CliRunner(spec -> {
                throw new AssertionError("no StartCLI run expected: " + spec.command());
            }, Map.of(), false, new CliResources("acme")), scrubber);
    }
}
