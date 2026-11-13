package de.dadecker.inubit.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliClientProbe;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.cli.node.CliClusterNodeAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61HealthAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61PortSet;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61ProcessQueryAdapter;
import de.dadecker.inubit.mcp.adapter.cli.v61.V61QueryRules;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.MaintenanceProbe;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81PartnerManagementAdapter;
import de.dadecker.inubit.mcp.adapter.rest.v81.V81QueryRules;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeAccess;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.domain.port.CliHealthPort;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Feature 012 T025: the 6.1 registration (research D-4). The registry is built as {@code Wiring}
 * builds it: the 8.1 port set as default, the 6.1 port set and its StartCLI health port for
 * {@link V61PortSet#RANGE}, CLI-only; feature 017 (T006, research D-14): the 8.1 port set also
 * registered for {@link PortSetRegistry#V90_RANGE}. Feature 018 (T013, research D-4): the
 * partner management port is the 8.1 REST adapter on 9.0 and refuses on 6.1.
 */
class PortSetRegistryTest {

    private static final CliClientProbe PROBE = new CliClientProbe(noStartCli(), id -> {
        throw new AssertionError("no client probe expected: " + id);
    }, CapabilityMatrix.production(), Clock.systemUTC());
    private static final CliConcurrencyLimiter LIMITER =
        new CliConcurrencyLimiter(CapabilityMatrix.production());

    private final PortSetRegistry registry = PortSetRegistry.v81Default().withV90()
        .register(V61PortSet.RANGE, V61PortSet.factory(PROBE, LIMITER), V61QueryRules.INSTANCE,
            V61PortSet.cliHealth(PROBE, LIMITER));

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
        for (String version : List.of("6.0.4", "6.2", "8.1.17", "9.1.0", "10.2")) {
            PortSetRegistry.Selection selection = registry.select(InubitVersion.parse(version));

            assertThat(selection.range()).as(version).isEmpty();
            assertThat(selection.factory()).as(version).isSameAs(defaultFactory);
            assertThat(selection.access()).as(version).isEqualTo(NodeAccess.REST_AND_CLI);
            assertThat(selection.cliHealth()).as(version).isEmpty();
        }
        assertThat(registry.select(Optional.empty()).access()).isEqualTo(NodeAccess.REST_AND_CLI);
    }

    @Test
    void nineZeroSelectsItsOwnRangeServedByThe81PortSetWithRestAndCli() {
        PortSetRegistry.Selection fallback = registry.select(Optional.empty());
        for (VersionSetting setting : List.of(VersionSetting.V9_0,
            VersionSetting.parse("9.0.7").orElseThrow(),
            VersionSetting.parse("9.0.0-HF1").orElseThrow())) {
            PortSetRegistry.Selection selection = registry.select(effective(setting));

            assertThat(selection.range()).as(setting.text()).contains(PortSetRegistry.V90_RANGE);
            assertThat(selection.factory()).as(setting.text()).isSameAs(fallback.factory());
            assertThat(selection.rules()).as(setting.text()).isSameAs(V81QueryRules.INSTANCE);
            assertThat(selection.access()).as(setting.text()).isEqualTo(NodeAccess.REST_AND_CLI);
            assertThat(selection.cliHealth()).as(setting.text()).isEmpty();
        }
        assertThat(PortSetRegistry.V90_RANGE).isEqualTo(new VersionRange(9, 0, 9, 0));
        assertThat(registry.select(effective(VersionSetting.V9_X)).range())
            .as("V9_X gives no major.minor").isEmpty();
    }

    @Test
    void theQueueEntryDefaultServesNineZeroWithItsOwnFactory() {
        // feature 020 T022 (research D-17): withV90 registers the 8.1 factory with the helper
        try (CliResources resources = new CliResources("acme", PrivateFiles.forHost())) {
            HelperInstallation.Host host = new HelperInstallation.Host(false, Map.of(),
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, Path.of("helper-root").toAbsolutePath());
            ProcessRun processRun = new ProcessRun(spec -> {
                throw new AssertionError("nothing is launched: " + spec.command());
            }, resources);
            PortSetRegistry withHelper = PortSetRegistry.v81Default(host, processRun, LIMITER)
                .withV90();
            PortSetRegistry.Factory helperDefault = withHelper.select(Optional.empty()).factory();

            assertThat(withHelper.select(effective(VersionSetting.V9_0)).factory())
                .isSameAs(helperDefault);
            assertThat(helperDefault).as("not the plain 8.1 port set")
                .isNotSameAs(registry.select(Optional.empty()).factory());
            assertThat(registry.select(effective(VersionSetting.V9_0)).factory())
                .as("the plain registry keeps the plain 8.1 port set for 9.0")
                .isSameAs(registry.select(Optional.empty()).factory());
            assertThatNullPointerException().isThrownBy(() ->
                PortSetRegistry.v81Default(null, processRun, LIMITER));
            assertThatNullPointerException().isThrownBy(() ->
                PortSetRegistry.v81Default(host, null, LIMITER));
            assertThatNullPointerException().isThrownBy(() ->
                PortSetRegistry.v81Default(host, processRun, null));
        }
    }

    @Test
    void theHelperPortSetListsTheClusterNodesOnEightOneAndNineZero() {
        // feature 024 T023 (research D-24): the 8.1 port set with the helper also serves 9.0 (the
        // helper picks its variant by version); the other node tools are not wired yet
        SecretScrubber scrubber = new SecretScrubber();
        try (CliResources resources = new CliResources("acme", PrivateFiles.forHost())) {
            HelperInstallation.Host host = new HelperInstallation.Host(false, Map.of(),
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, Path.of("helper-root").toAbsolutePath());
            ProcessRun processRun = new ProcessRun(spec -> {
                throw new AssertionError("nothing is launched: " + spec.command());
            }, resources);
            PortSetRegistry withHelper = PortSetRegistry.v81Default(host, processRun, LIMITER)
                .withV90();
            for (VersionSetting setting : List.of(VersionSetting.V8_1, VersionSetting.V9_0)) {
                EffectiveNodeConfig server = TestNodeConfig.node().versionLine(setting).build();
                try (InubitHttpClient client = new InubitHttpClient(server, Optional.empty(),
                    Optional.empty(), MaintenanceProbe.NONE, scrubber)) {
                    PortSet ports = withHelper.select(effective(setting)).factory().create(
                        new PortSetRegistry.NodeResources(server, credentials(server),
                            new CredentialGuard(server.secretLabels(), Clock.systemUTC()), client,
                            noStartCli(), scrubber));

                    assertThat(ports.clusterNodes()).as(setting.text())
                        .isInstanceOf(CliClusterNodeAdapter.class);
                    assertThatThrownBy(() -> ports.nodeConfig().values(
                        List.of(NodeConfigName.CLUSTER_NAME), effective(setting).orElseThrow(),
                        System.nanoTime())).as(setting.text())
                        .isInstanceOfSatisfying(ToolErrorException.class, e ->
                            assertThat(e.error().code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE));
                }
            }
        }
    }

    @Test
    void aRangeRegisteredWithoutCliHealthFactoryIsRestAndCli() {
        VersionRange v79 = new VersionRange(7, 9, 7, 9);
        PortSetRegistry.Factory factory = node -> {
            throw new AssertionError("not built");
        };
        PortSetRegistry.Selection selection = registry.register(v79, factory, V81QueryRules.INSTANCE)
            .select(InubitVersion.parse("7.9.3"));

        assertThat(selection.range()).contains(v79);
        assertThat(selection.factory()).isSameAs(factory);
        assertThat(selection.access()).isEqualTo(NodeAccess.REST_AND_CLI);
        assertThat(selection.cliHealth()).isEmpty();
    }

    @Test
    void theSixOnePortSetIsBuiltWithoutRestClientAndRefusesEveryPortButTheProcessQuery() {
        // the runner of sixOneResources fails on any launch
        PortSetRegistry.NodeResources node = sixOneResources();

        PortSet ports = registry.select(effective(VersionSetting.V6_1)).factory().create(node);

        assertThat(ports.processes()).isInstanceOf(V61ProcessQueryAdapter.class);
        assertThat(List.of(ports.logs(), ports.inventory(), ports.processControl(),
            ports.artifacts(), ports.imports(), ports.tags(), ports.partners()))
            .allSatisfy(port -> assertThat(port).isInstanceOf(RefusingPorts.class));
        assertThatThrownBy(() -> ports.logs().query(null))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
        assertThatThrownBy(() -> ports.partners().read("OWNERS", List.of()))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION));
    }

    @Test
    void theNineZeroPortSetReadsPartnerManagementsWithThe81RestAdapter() {
        SecretScrubber scrubber = new SecretScrubber();
        EffectiveNodeConfig server = TestNodeConfig.node().versionLine(VersionSetting.V9_0)
            .build();
        try (InubitHttpClient client = new InubitHttpClient(server, Optional.empty(),
            Optional.empty(), MaintenanceProbe.NONE, scrubber)) {
            PortSetRegistry.NodeResources node = new PortSetRegistry.NodeResources(server,
                credentials(server), new CredentialGuard(server.secretLabels(),
                    Clock.systemUTC()), client, noStartCli(), scrubber);

            PortSet ports = registry.select(effective(VersionSetting.V9_0)).factory()
                .create(node);

            assertThat(ports.partners()).isInstanceOf(V81PartnerManagementAdapter.class);
        }
    }

    @Test
    void theSixOneHealthPortIsTheStartCliAdapterAndBuildingItLaunchesNothing() {
        // the runner of sixOneResources fails on any launch
        CliHealthPort health = registry.select(effective(VersionSetting.V6_1)).cliHealth()
            .orElseThrow().create(sixOneResources());

        assertThat(health).isInstanceOf(V61HealthAdapter.class);
    }

    /** The resources of a 6.1 node as {@code AdapterGatewayFactory} passes them: no client. */
    private static PortSetRegistry.NodeResources sixOneResources() {
        SecretScrubber scrubber = new SecretScrubber();
        EffectiveNodeConfig server = TestNodeConfig.node().versionLine(VersionSetting.V6_1)
            .build();
        return new PortSetRegistry.NodeResources(server, credentials(server),
            new CredentialGuard(server.secretLabels(), Clock.systemUTC()), null, noStartCli(),
            scrubber);
    }

    private static NodeCredentials credentials(EffectiveNodeConfig server) {
        return new NodeCredentials(server.id(),
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of("registry-test-pw"), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** A runner that fails on any StartCLI launch. */
    private static CliRunner noStartCli() {
        return TestCliRunners.forHost(spec -> {
            throw new AssertionError("no StartCLI run expected: " + spec.command());
        }, Map.of(), new CliResources("acme", PrivateFiles.forHost()));
    }
}
