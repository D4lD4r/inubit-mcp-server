package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InterfaceCoverage;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Feature 022 (T049, research D-12): the admission of a change, one test per step and per code,
 * the order of the steps, and the {@code REFUSED} audit record of every refusal.
 */
class ChangeGuardTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    /** Pinned to 9.0, where {@code maintenance on|off} is still {@code planned}. */
    private static final NodeId NEW = NodeId.parse("dev/node2");
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final NodeId PROD_OFF = NodeId.parse("prod/node2");
    private static final NodeId PROD_CLIENT = NodeId.parse("prod/node3");
    private static final NodeId PROD_OPT_IN = NodeId.parse("prod/node4");

    private static final String TOOL = "operate_server";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000049");

    private static final CoverageKey SWITCH = new CoverageKey(Channel.CLI, "maintenance on|off");
    private static final CoverageKey QUERY =
        new CoverageKey(Channel.CLI, "maintenance -eq true|false");
    private static final CoverageKey READY = new CoverageKey(Channel.REST, "POST /ready/{status}");

    private static final String LIST = """
        # delivered-stories:
        channel\tentry\tkind\tarea\tstory\tv8_1\tv9_0\twaits
        CLI\tmaintenance on|off\tWRITE\tA\tUS4\treachable:operate_server/maintenance\tplanned\t-
        CLI\tmaintenance -eq true|false\tWRITE\tA\tUS4\treachable:operate_server/maintenance\treachable:operate_server/maintenance\t-
        REST\tPOST /ready/{status}\tWRITE\tA\tUS4\treachable:operate_server/ready\treachable:operate_server/ready\t-
        """;

    /** Two variants: {@code enabled} switches, otherwise the call sets with {@code -eq}. */
    private static final ActionSpec MAINTENANCE = spec("maintenance", ActionSpec.Area.OPERATION,
        Set.of(SWITCH, QUERY), input -> input.containsKey("enabled") ? Set.of(SWITCH)
            : Set.of(QUERY));
    private static final ActionSpec READY_STATE = spec("ready", ActionSpec.Area.OPERATION,
        Set.of(READY), input -> Set.of(READY));

    private static ActionSpec objects(Set<CoverageKey> entries) {
        return area(ActionSpec.Area.OBJECTS, entries);
    }

    private static ActionSpec area(ActionSpec.Area area, Set<CoverageKey> entries) {
        return spec("delete", area, entries, input -> entries);
    }

    private static ActionSpec spec(String action, ActionSpec.Area area,
        Set<CoverageKey> entries, Function<Map<String, Object>,
            Set<CoverageKey>> variant) {
        return new ActionSpec(TOOL, action, Capability.PROCESS_CONTROL, area,
            ActionSpec.Kind.CHANGE, entries, variant, Set.of(), false, Optional.empty(),
            ActionSpec.RestartNotice.NONE);
    }

    private static WritePolicy policy(NodeId node, boolean production, boolean enabled,
        boolean optIn, Confirmation confirmation) {
        return new WritePolicy(node, production, enabled, optIn, confirmation,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    private final Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, false, true, false, Confirmation.SERVER),
        NEW, policy(NEW, false, true, false, Confirmation.SERVER),
        TEST, policy(TEST, false, false, false, Confirmation.SERVER),
        PROD, policy(PROD, true, true, false, Confirmation.SERVER),
        PROD_OFF, policy(PROD_OFF, true, false, false, Confirmation.SERVER),
        PROD_CLIENT, policy(PROD_CLIENT, true, true, true, Confirmation.CLIENT),
        PROD_OPT_IN, policy(PROD_OPT_IN, true, true, true, Confirmation.SERVER));

    private final FakeProcessControl gateways = new FakeProcessControl();
    /** Every version setting the gate looked up: the gate was consulted. */
    private final List<NodeId> gateLookups = new CopyOnWriteArrayList<>();
    /** The areas whose opt-in is set, on every node. */
    private Set<ActionSpec.Area> enabledAreas = Set.of();
    /** Every area the guard asked about. */
    private final List<ActionSpec.Area> areaLookups = new CopyOnWriteArrayList<>();
    private final List<AuditRecord> records = new CopyOnWriteArrayList<>();
    private AuditPort audit = records::add;

    private ChangeGuard guard() {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> {
            gateLookups.add(node);
            return node.equals(NEW) ? VersionSetting.V9_0 : VersionSetting.V8_1;
        }, node -> Duration.ofMinutes(10));
        VersionGate gate = FakeVersionDetection.gateWithoutClients(versions,
            new FakeVersionDetection(versions), new CapabilityMatrix(List.of(),
                Map.of(Capability.PROCESS_CONTROL, SupportState.SUPPORTED)),
            InterfaceCoverage.load(new StringReader(LIST)));
        return new ChangeGuard(new TargetResolver(List.copyOf(policies.keySet())),
            policies::get, (node, area) -> {
                areaLookups.add(area);
                return enabledAreas.contains(area);
            }, gateways, gate, audit, "acme", Clock.fixed(NOW, ZoneOffset.UTC), () -> AUDIT_ID);
    }

    private WritePolicy admit(String node, ActionSpec spec, Map<String, Object> input) {
        return guard().admit(TOOL, node, spec, input);
    }

    /** The refusal of the call, which must be audited as exactly one {@code REFUSED} record. */
    private ToolError refusal(String node, ActionSpec spec, Map<String, Object> input) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> admit(node, spec, input));
        assertThat(exception).as("expected a refusal for " + node).isNotNull();
        ToolError error = exception.error();
        assertThat(records).as("audit records").singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.capability()).isEqualTo(TOOL);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).startsWith(error.code() + ": "));
        });
        return error;
    }

    private ToolError refusal(String node) {
        return refusal(node, MAINTENANCE, Map.of("enabled", true));
    }

    private void assertNothingContacted() {
        assertThat(gateLookups).as("gate calls").isEmpty();
        assertThat(gateways.calls).as("client checks").isEmpty();
    }

    // --- admitted ---------------------------------------------------------------------------

    @Test
    void anOperationIsAdmittedWithThePermitOfTheAdmittedVersionAndNotAudited() {
        WritePolicy admitted = admit("dev/node1", MAINTENANCE, Map.of("enabled", true));

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(admitted.permit()).isEqualTo(new WritePermit(DEV, Capability.PROCESS_CONTROL,
            InubitVersion.parse("8.1").orElseThrow()));
        assertThat(gateways.calls).containsExactly("check dev/node1");
        assertThat(records).isEmpty();
    }

    @Test
    void productionWithOptInAndServerConfirmationIsAdmitted() {
        assertThat(admit("prod/node4", MAINTENANCE, Map.of()).node()).isEqualTo(PROD_OPT_IN);
    }

    // --- step 1: the node -------------------------------------------------------------------

    @Test
    void step1AGroupIdIsInvalidInput() {
        ToolError error = refusal("dev");

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertNothingContacted();
        assertThat(records.get(0).node()).isEqualTo("dev");
        assertThat(records.get(0).group()).contains("dev");
    }

    @Test
    void step1AnUnknownNodeIsTargetUnknown() {
        ToolError error = refusal("dev/node9");

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingContacted();
        assertThat(records.get(0).account()).isEmpty();
    }

    // --- step 2: production -----------------------------------------------------------------

    @Test
    void step2ProductionWithoutOptInIsProtected() {
        ToolError error = refusal("prod/node1");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.message()).contains(TOOL);
        assertNothingContacted();
    }

    @Test
    void step2ProductionWithClientConfirmationIsProtected() {
        ToolError error = refusal("prod/node3");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.message()).contains("client-side confirmation");
        assertNothingContacted();
    }

    @Test
    void theProductionRefusalWinsOverWriteOff() {
        assertThat(refusal("prod/node2").code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
    }

    // --- step 3: write enablement -----------------------------------------------------------

    @Test
    void step3WriteOffIsWriteDisabled() {
        ToolError error = refusal("test/node1");

        assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(error.nextStep()).contains("write.enabled: true", TOOL);
        assertNothingContacted();
    }

    @Test
    void writeOffWinsOverAMissingAreaOptIn() {
        assertThat(refusal("test/node1", objects(Set.of(QUERY)), Map.of()).code())
            .isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(areaLookups).isEmpty();
    }

    // --- step 4: the area opt-in ------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"OBJECTS, objects", "LIBRARIES, libraries", "SETTINGS, settings",
        "USERS, users", "RUNTIME_DATA, runtimeData"})
    void step4AMissingAreaOptInIsNotConfiguredNamingTheSetting(ActionSpec.Area area,
        String key) {
        ToolError error = refusal("dev/node1", area(area, Set.of(QUERY)), Map.of());

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).contains(TOOL, "administration." + key);
        assertThat(error.nextStep()).contains("administration." + key + ": true");
        assertThat(areaLookups).containsExactly(area);
        assertNothingContacted();
    }

    @Test
    void step4AnEnabledAreaIsAdmitted() {
        enabledAreas = Set.of(ActionSpec.Area.OBJECTS);

        assertThat(admit("dev/node1", objects(Set.of(QUERY)), Map.of()).node()).isEqualTo(DEV);
        assertThat(records).isEmpty();
    }

    @Test
    void step4IsSkippedForOperation() {
        admit("dev/node1", MAINTENANCE, Map.of());

        assertThat(areaLookups).isEmpty();
    }

    @Test
    void aMissingAreaOptInWinsOverTheVersionGate() {
        assertThat(refusal("dev/node2", objects(Set.of(SWITCH)), Map.of()).code())
            .isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertNothingContacted();
    }

    // --- step 5: the version gate with the call's entries -----------------------------------

    @Test
    void step5AVariantNotReachableOnTheVersionIsUnsupportedNamingIt() {
        ToolError error = refusal("dev/node2", MAINTENANCE, Map.of("enabled", true));

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith(TOOL + ": ")
            .contains("maintenance via CLI maintenance on|off", "INUBIT 9.0");
        assertThat(gateways.calls).as("the gate refuses before the client check").isEmpty();
    }

    @Test
    void step5TheReachableSiblingVariantIsAdmitted() {
        assertThat(admit("dev/node2", MAINTENANCE, Map.of()).node()).isEqualTo(NEW);
    }

    // --- step 6: availability ---------------------------------------------------------------

    @Test
    void step6AMissingStartCliIsCliUnavailable() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        assertThat(refusal("dev/node1").code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(gateways.calls).containsExactly("check dev/node1");
    }

    @Test
    void step6MissingCredentialsAreAuthFailed() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
            "No INUBIT credentials are configured for dev/node1", "not set", "Set them")
            .withNode(DEV));

        assertThat(refusal("dev/node1").code()).isEqualTo(ErrorCode.AUTH_FAILED);
    }

    @Test
    void step6ARestOnlyCallNeedsNoStartCli() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        assertThat(admit("dev/node1", READY_STATE, Map.of("status", "true")).node())
            .isEqualTo(DEV);
        assertThat(gateways.calls).isEmpty();
    }

    // --- the audit record of a refusal ------------------------------------------------------

    @Test
    void theRefusalRecordNamesNodeGroupAccountActionAndStep() {
        refusal("test/node1", MAINTENANCE, Map.of("enabled", true));
        AuditRecord preview = records.get(0);

        assertThat(preview.auditId()).isEqualTo(AUDIT_ID);
        assertThat(preview.timestamp()).isEqualTo(NOW);
        assertThat(preview.profile()).isEqualTo("acme");
        assertThat(preview.node()).isEqualTo("test/node1");
        assertThat(preview.group()).contains("test");
        assertThat(preview.account()).contains("jdoe");
        assertThat(preview.step()).isEqualTo(AuditRecord.Step.PREVIEW);
        assertThat(preview.inputs()).containsExactly(Map.entry("action", "maintenance"));

        records.clear();
        refusal("test/node1", MAINTENANCE, Map.of(AuditRecord.CONFIRMATION_CODE, "ABC123"));
        AuditRecord execute = records.get(0);

        assertThat(execute.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(execute.inputs()).containsEntry(AuditRecord.CONFIRMATION_CODE, "ABC123");
    }

    @Test
    void aRefusalWhoseAuditRecordCannotBeWrittenIsInternal() {
        audit = record -> {
            throw new IllegalStateException("disk full");
        };

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> admit("test/node1", MAINTENANCE, Map.of())).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("WRITE_DISABLED");
    }

    /** Leak check (SC-005): input values other than the action and the code are not audited. */
    @Test
    void neitherTheRefusalNorItsRecordRepeatAnInputValue() {
        ToolError error = refusal("test/node1", MAINTENANCE,
            Map.of("value", TestSecrets.CONFIGURED_PASSWORD));

        TestSecrets.assertNoLeak(error.toString());
        TestSecrets.assertNoLeak(records.get(0).toString());
    }
}
