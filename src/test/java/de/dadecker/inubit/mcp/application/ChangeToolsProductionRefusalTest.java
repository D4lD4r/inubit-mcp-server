package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.port.GatewayFactory;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 022 T065 (SC-003, SC-004, FR-014, research D-12): every action of every change tool in
 * the wiring's {@link ActionCatalog} is refused before anything is read from or sent to INUBIT,
 * and the refusal is audited as {@code REFUSED}:
 *
 * <ul>
 *   <li>on a production server without {@code write.productionOptIn}: {@code PRODUCTION_PROTECTED}
 *       (SC-004);
 *   <li>on a server with {@code write.enabled: false}: {@code WRITE_DISABLED} (SC-003).
 * </ul>
 *
 * <p>Everything else admits the call: every administration area is opted in, every capability is
 * supported, and every entry the action declares is {@code reachable} on 8.1 and 9.0; on a
 * non-production server with write access the same setup admits every action (the control). A
 * change tool a later story registers has no inputs in {@link #fields} and no test double in
 * {@link #ports} until it adds them, so each of its actions fails here.
 */
class ChangeToolsProductionRefusalTest {

    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId WRITE_OFF = NodeId.parse("dev/node1");
    private static final NodeId WRITE_ON = NodeId.parse("test/node1");
    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String REASON = "night window";
    private static final String HEADER = "channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits";

    /** A configuration the wiring starts with; only its {@link ActionCatalog} is read. */
    private static final String YAML = """
        profile:
          name: acme
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://node1.dev.example.test:8443
        """;

    private final Map<NodeId, WritePolicy> policies = Map.of(
        PROD, new WritePolicy(PROD, true, true, false, Confirmation.SERVER,
            Duration.ofMinutes(5), Optional.of("jdoe")),
        WRITE_OFF, new WritePolicy(WRITE_OFF, false, false, false, Confirmation.CLIENT,
            Duration.ofMinutes(5), Optional.of("jdoe")),
        WRITE_ON, new WritePolicy(WRITE_ON, false, true, false, Confirmation.CLIENT,
            Duration.ofMinutes(5), Optional.of("jdoe")));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RecordingAuditPort records = new RecordingAuditPort();
    private final ChangeAudit audit = new ChangeAudit(records, clock, "acme", UUID::randomUUID);
    private final FakeServerOperations operations = new FakeServerOperations();
    private final FakeTagAdministration tags = new FakeTagAdministration();

    /** The change actions of the wiring's catalog, named {@code tool/action}. */
    static Stream<Named<ActionSpec>> changeActions() {
        ProfileConfig config = new ConfigLoader(Map.of(), Path.of("/home/test"), false)
            .parse(YAML, Path.of("/home/test/acme.yaml")).config();
        try (TestWiring wiring = TestWiring.of(config, TestSecrets.store(Map.of(
                "INUBIT_ACME_DEV_USERNAME", "jdoe", "INUBIT_ACME_DEV_PASSWORD", "refusal-test-pw")),
            new SecretScrubber(), path -> false, false)) {
            return wiring.actionCatalog().specs().stream()
                .filter(spec -> spec.kind() == ActionSpec.Kind.CHANGE)
                .map(spec -> Named.of(spec.tool() + "/" + spec.action(), spec)).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("changeActions")
    void aProductionServerWithoutOptInRefusesTheAction(ActionSpec spec) {
        assertRefused(spec, PROD, ErrorCode.PRODUCTION_PROTECTED);
    }

    @ParameterizedTest
    @MethodSource("changeActions")
    void aServerWithWriteOffRefusesTheAction(ActionSpec spec) {
        assertRefused(spec, WRITE_OFF, ErrorCode.WRITE_DISABLED);
    }

    /** The control: on a non-production server with write access the same setup admits it. */
    @ParameterizedTest
    @MethodSource("changeActions")
    void aServerWithWriteAccessIsAdmittedForTheAction(ActionSpec spec) {
        WritePolicy admitted = guard(ports(spec), spec).admit(spec.tool(), WRITE_ON.value(), spec,
            fields(spec));

        assertThat(admitted.node()).isEqualTo(WRITE_ON);
        assertThat(records.records).isEmpty();
    }

    private void assertRefused(ActionSpec spec, NodeId node, ErrorCode code) {
        ToolErrorException refusal = catchThrowableOfType(ToolErrorException.class,
            () -> call(spec, node));

        assertThat(refusal).as("expected a refusal").isNotNull();
        assertThat(refusal.error().code()).isEqualTo(code);
        assertThat(operations.calls).as("read or sent by operate_server").isEmpty();
        assertThat(tags.calls).as("read or sent by manage_tags").isEmpty();
        assertThat(records.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.capability()).isEqualTo(spec.tool());
            assertThat(record.node()).isEqualTo(node.value());
            assertThat(record.inputs()).containsEntry("action", spec.action());
        });
    }

    /** One call of {@code spec} on {@code node} with valid inputs, through its tool's service. */
    private Object call(ActionSpec spec, NodeId node) {
        ChangeGuard guard = guard(ports(spec), spec);
        ChangeChallenges challenges = new ChangeChallenges(new WriteChallengeRegistry(clock));
        return switch (spec.tool()) {
            case ServerOperationService.TOOL -> new ServerOperationService(guard,
                new ChangeLocks(), challenges, audit, operations, clock).execute(
                    new ServerOperationService.Request(node.value(), spec.action(), fields(spec),
                        REASON, Optional.empty(), Optional.empty()));
            case TagAdministrationService.TOOL -> new TagAdministrationService(guard,
                new ChangeLocks(), challenges, audit, tags).execute(
                    new TagAdministrationService.Request(node.value(), spec.action(),
                        fields(spec), REASON, Optional.empty(), Optional.empty()));
            default -> throw unknown(spec);
        };
    }

    /** The test double behind the tool of {@code spec}. */
    private GatewayFactory ports(ActionSpec spec) {
        return switch (spec.tool()) {
            case ServerOperationService.TOOL -> operations;
            case TagAdministrationService.TOOL -> tags;
            default -> throw unknown(spec);
        };
    }

    /** Valid inputs of {@code spec}. */
    private static Map<String, Object> fields(ActionSpec spec) {
        return switch (spec.tool() + "/" + spec.action()) {
            case "operate_server/setMaintenance", "operate_server/setTrace" -> Map.of("on", true);
            case "operate_server/setReadiness" -> Map.of("ready", false);
            case "operate_server/setTraceLevel" -> Map.of("level", "debug");
            case "operate_server/triggerMidnightTask",
                "operate_server/deleteErrorProcesses" -> Map.of();
            case "manage_tags/setActiveTag" -> Map.of("user", "jdoe", "tag", "REL-2");
            case "manage_tags/removeActiveTag" -> Map.of("user", "jdoe");
            default -> throw unknown(spec);
        };
    }

    private static AssertionError unknown(ActionSpec spec) {
        return new AssertionError(spec.tool() + "/" + spec.action() + " is a change action"
            + " without a call in this test; add it to ports and fields (SC-003, SC-004)");
    }

    /**
     * The guard over {@code ports}: every area opted in, every capability supported, every entry
     * {@code spec} declares reachable on the pinned 8.1.
     */
    private ChangeGuard guard(GatewayFactory ports, ActionSpec spec) {
        NodeVersions versions = new NodeVersions(clock, node -> VersionSetting.V8_1,
            node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), new CapabilityMatrix(List.of(),
                Arrays.stream(Capability.values()).collect(Collectors.toMap(Function.identity(),
                    capability -> SupportState.SUPPORTED))),
            InterfaceCoverage.load(new StringReader(reachable(spec))));
        return new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (id, area) -> true, ports, gate, audit);
    }

    /** A coverage list with every entry {@code spec} declares reachable by it on 8.1 and 9.0. */
    private static String reachable(ActionSpec spec) {
        String state = "reachable:" + spec.tool() + "/" + spec.action();
        return "# delivered-stories:\n" + HEADER + "\n" + spec.declaredEntries().stream()
            .map(key -> String.join("\t", key.channel().name(), key.entry(), "WRITE", "A", "-",
                state, state, "-"))
            .collect(Collectors.joining("\n")) + "\n";
    }
}
