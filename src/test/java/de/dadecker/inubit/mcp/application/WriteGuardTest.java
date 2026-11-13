package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProcessAction;
import de.dadecker.inubit.mcp.domain.model.SupportState;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionRange;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** T101: one test per branch of the write decision flow (contracts/mcp-tools.md §7–8). */
class WriteGuardTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST = NodeId.parse("test/inubit01");
    private static final NodeId PROD = NodeId.parse("prod/inubit01");
    private static final NodeId PROD_OPT_IN = NodeId.parse("prod/inubit02");

    private final FakeProcessControl gateways = new FakeProcessControl();
    private Map<NodeId, WritePolicy> policies = Map.of(
        DEV, policy(DEV, false, true, false),
        TEST, policy(TEST, false, false, false),
        PROD, policy(PROD, true, true, false),
        PROD_OPT_IN, policy(PROD_OPT_IN, true, true, true));

    private static WritePolicy policy(NodeId server, boolean production, boolean enabled,
        boolean optIn) {
        return new WritePolicy(server, production, enabled, optIn, Confirmation.SERVER,
            Duration.ofMinutes(5), Optional.of("jdoe"));
    }

    private WriteGuard guard() {
        return new WriteGuard(new TargetResolver(List.of(DEV, TEST, PROD, PROD_OPT_IN)),
            policies::get, gateways, FakeVersionDetection.pinned81());
    }

    private ToolError refusal(String server) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> guard().admit(server, ProcessAction.RESTART));
        assertThat(exception).as("expected a refusal for " + server).isNotNull();
        return exception.error();
    }

    @Test
    void aWriteEnabledServerIsAdmittedAfterTheCliCheck() {
        WritePolicy admitted = guard().admit("dev/node1", ProcessAction.RESTART);

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(gateways.calls).containsExactly("check dev/node1");
    }

    @ParameterizedTest
    @EnumSource(ProcessAction.class)
    void theAdmittedPolicyCarriesAProcessControlPermitOfTheAdmittedVersion(
        ProcessAction action) {
        // feature 017, T042 (FR-018, research D-24)
        WritePolicy admitted = guard().admit("dev/node1", action);

        assertThat(admitted.permit()).isEqualTo(new WritePermit(DEV, Capability.PROCESS_CONTROL,
            InubitVersion.parse("8.1").orElseThrow()));
        assertThat(admitted.withWarnings(List.of("w")).permit()).isEqualTo(admitted.permit());
        assertThatThrownBy(() -> policy(DEV, false, true, false).permit())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStageIsInvalidInput() {
        ToolError error = refusal("dev");

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("is the id of one group", "exactly one node");
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void anUnknownServerIsEnvironmentUnknown() {
        ToolError error = refusal("dev/inubit9");

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(error.message()).contains("dev/node1");
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void aMalformedIdIsInvalidInput() {
        assertThat(refusal("DEV/x y").code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void writeDisabledIsRefusedWithAHintHowToEnableIt() {
        ToolError error = refusal("test/inubit01");

        assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(error.node()).contains(TEST);
        assertThat(error.message()).contains("test/inubit01");
        assertThat(error.nextStep()).contains("write.enabled: true");
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void productionWithoutOptInIsProtectedEvenWithWriteEnabled() {
        ToolError error = refusal("prod/inubit01");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.node()).contains(PROD);
        assertThat(error.likelyCause()).contains("production").contains("productionOptIn");
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void productionWithoutOptInIsProtectedRegardlessOfWriteEnabled() {
        policies = Map.of(PROD, policy(PROD, true, false, false));

        assertThat(refusal("prod/inubit01").code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
    }

    @Test
    void productionWithOptInButWriteDisabledIsWriteDisabled() {
        policies = Map.of(PROD_OPT_IN, policy(PROD_OPT_IN, true, false, true));

        assertThat(refusal("prod/inubit02").code()).isEqualTo(ErrorCode.WRITE_DISABLED);
    }

    @Test
    void productionWithOptInAndWriteEnabledIsAdmitted() {
        assertThat(guard().admit("prod/inubit02", ProcessAction.RESTART).node())
            .isEqualTo(PROD_OPT_IN);
    }

    @Test
    void clientConfirmationOnProductionIsProtectedEvenWithOptIn() {
        // Phase 6 review W4: defense in depth; ConfigValidator already rejects this at startup
        policies = Map.of(PROD_OPT_IN, new WritePolicy(PROD_OPT_IN, true, true, true,
            Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe")));

        ToolError error = refusal("prod/inubit02");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.likelyCause()).contains("CLIENT");
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void withoutCliTheServerIsCliUnavailable() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        ToolError error = refusal("dev/node1");

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void aServerWithoutPolicyIsNeverAdmitted() {
        policies = Map.of();

        assertThat(refusal("dev/node1").code()).isEqualTo(ErrorCode.WRITE_DISABLED);
    }

    // --- feature 024, T047 (FR-040, FR-044, research D-18): the texts of restart and kill ------

    /** The refusals of restart and kill stay byte-identical now that the guard is shared. */
    @Test
    void theRestartAndKillRefusalsKeepTheirTexts() {
        ToolError production = refusal("prod/inubit01");
        assertThat(production.message()).isEqualTo("prod/inubit01 belongs to the production"
            + " group prod; restart and kill are refused there");
        assertThat(production.likelyCause()).isEqualTo("The group is classified production:"
            + " true and write.productionOptIn is not set for this node (write.enabled alone"
            + " never unlocks production)");
        assertThat(production.nextStep()).isEqualTo("Perform the action in the INUBIT"
            + " Workbench; only if this node must allow it, set write.productionOptIn: true and"
            + " write.enabled: true for it and restart the MCP client");

        ToolError disabled = refusal("test/inubit01");
        assertThat(disabled.message())
            .isEqualTo("Write access is disabled for test/inubit01; nothing was changed");
        assertThat(disabled.likelyCause())
            .isEqualTo("The node is read-only: write.enabled is false (the default)");
        assertThat(disabled.nextStep()).isEqualTo("To allow restart_process and kill_process on"
            + " this node, set write.enabled: true for its group or node in the configuration"
            + " file and restart the MCP client (see docs/setup.md, write settings)");

        policies = Map.of(PROD_OPT_IN, new WritePolicy(PROD_OPT_IN, true, true, true,
            Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe")));
        ToolError client = refusal("prod/inubit02");
        assertThat(client.message()).isEqualTo("prod/inubit02 belongs to the production group"
            + " prod and is configured with client-side confirmation; restart and kill are"
            + " refused there");
        assertThat(client.likelyCause())
            .isEqualTo("write.confirmation: CLIENT is not allowed on production groups (FR-022)");
        assertThat(client.nextStep()).isEqualTo("Remove write.confirmation: CLIENT for this node"
            + " (confirmation by the MCP server is the default) and restart the MCP client");
        assertThat(gateways.calls).isEmpty();
    }

    // --- feature 024, T047: the log-level tools (FR-040, FR-044, research D-15, D-18) ---------

    private static final String SET = "set_temporary_log_level";
    private static final String RESET = "reset_temporary_log_level";
    private static final String OPT_IN_KEY = "unsupported.logLevelWrite";
    private static final Set<Channel> CLI = Set.of(Channel.CLI);
    /** The production matrix with the log-level change recorded on 8.1. */
    private static final CapabilityMatrix LOG_LEVEL_ON_81 = new CapabilityMatrix(List.of(
        new CapabilityMatrix.Entry(new VersionRange(8, 1, 8, 1),
            Map.of(Capability.LOG_LEVEL_WRITE, SupportState.SUPPORTED), Map.of(), false)),
        Map.of());

    /** The servers with {@code unsupported.logLevelWrite}. */
    private Set<NodeId> logLevelWrite = Set.of(DEV, TEST, PROD, PROD_OPT_IN);
    /** The version setting of each server; unlisted servers are pinned to 8.1. */
    private Map<NodeId, VersionSetting> pins = Map.of();
    /** Every version setting the gate looked up: the gate was consulted. */
    private final List<NodeId> gateLookups = new CopyOnWriteArrayList<>();
    private FakeVersionDetection detection;

    private WriteGuard logLevelGuard(CapabilityMatrix matrix) {
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> {
            gateLookups.add(node);
            return pins.getOrDefault(node, VersionSetting.V8_1);
        }, node -> Duration.ofMinutes(10));
        detection = new FakeVersionDetection(versions);
        return new WriteGuard(new TargetResolver(List.of(DEV, TEST, PROD, PROD_OPT_IN)),
            policies::get, gateways,
            FakeVersionDetection.gateWithoutClients(versions, detection, matrix));
    }

    private WritePolicy admitSet(String server) {
        return logLevelGuard(LOG_LEVEL_ON_81).admit(server, SET, Capability.LOG_LEVEL_WRITE, CLI,
            logLevelWrite::contains, OPT_IN_KEY);
    }

    private ToolError setRefusal(String server) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> admitSet(server));
        assertThat(exception).as("expected a refusal for " + server).isNotNull();
        return exception.error();
    }

    private ToolError forgetRefusal(String server) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> logLevelGuard(LOG_LEVEL_ON_81).admitWithoutContact(server, RESET,
                logLevelWrite::contains, OPT_IN_KEY));
        assertThat(exception).as("expected a refusal for " + server).isNotNull();
        return exception.error();
    }

    private void assertNothingContacted() {
        assertThat(gateways.calls).as("gateway calls").isEmpty();
        assertThat(gateLookups).as("gate calls").isEmpty();
        assertThat(detection.requests()).as("detections").isEmpty();
    }

    @Test
    void anOptedInServerIsAdmittedForTheToolAfterTheGateAndTheClientCheck() {
        WritePolicy admitted = admitSet("dev/node1");

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(admitted.permit()).isEqualTo(new WritePermit(DEV, Capability.LOG_LEVEL_WRITE,
            InubitVersion.parse("8.1").orElseThrow()));
        assertThat(gateLookups).containsOnly(DEV);
        assertThat(gateways.calls).containsExactly("check dev/node1");
    }

    @Test
    void theToolOverloadChecksTheTargetFirst() {
        assertThat(setRefusal("dev").code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(setRefusal("dev/inubit9").code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertNothingContacted();
    }

    @Test
    void productionWithoutOptInIsProtectedNamingTheTool() {
        ToolError error = setRefusal("prod/inubit01");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.node()).contains(PROD);
        assertThat(error.message()).isEqualTo("prod/inubit01 belongs to the production group"
            + " prod; set_temporary_log_level is refused there");
        assertThat(error.likelyCause()).isEqualTo("The group is classified production: true and"
            + " write.productionOptIn is not set for this node (write.enabled alone never unlocks"
            + " production)");
        assertNothingContacted();
    }

    @Test
    void clientConfirmationOnProductionIsProtectedNamingTheTool() {
        policies = Map.of(PROD_OPT_IN, new WritePolicy(PROD_OPT_IN, true, true, true,
            Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe")));

        ToolError error = setRefusal("prod/inubit02");

        assertThat(error.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(error.message()).isEqualTo("prod/inubit02 belongs to the production group"
            + " prod and is configured with client-side confirmation; set_temporary_log_level"
            + " is refused there");
        assertNothingContacted();
    }

    @Test
    void writeDisabledIsRefusedNamingTheTool() {
        ToolError error = setRefusal("test/inubit01");

        assertThat(error.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(error.node()).contains(TEST);
        assertThat(error.message())
            .isEqualTo("Write access is disabled for test/inubit01; nothing was changed");
        assertThat(error.nextStep()).isEqualTo("To allow set_temporary_log_level on this node,"
            + " set write.enabled: true for its group or node in the configuration file and"
            + " restart the MCP client (see docs/setup.md, write settings)");
        assertNothingContacted();
    }

    @Test
    void theWriteChecksComeBeforeTheMissingOptIn() {
        logLevelWrite = Set.of();

        assertThat(setRefusal("prod/inubit01").code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(setRefusal("test/inubit01").code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        policies = Map.of();
        assertThat(setRefusal("dev/node1").code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertNothingContacted();
    }

    @Test
    void withoutTheOptInAWriteEnabledServerIsNotConfigured() {
        logLevelWrite = Set.of(PROD_OPT_IN);

        ToolError error = setRefusal("dev/node1");

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(error.node()).contains(DEV);
        assertThat(error.message()).isEqualTo("set_temporary_log_level is not enabled for group"
            + " dev: unsupported.logLevelWrite is not set");
        assertThat(error.likelyCause()).isEqualTo("set_temporary_log_level uses an unsupported,"
            + " undocumented INUBIT interface and is off unless the group opts in");
        assertThat(error.nextStep()).isEqualTo("Set unsupported.logLevelWrite: true on group dev"
            + " and restart the MCP client (unsupported INUBIT functionality, see docs/setup.md"
            + " \"Unsupported functions\")");
        assertNothingContacted();
    }

    @Test
    void theGateRefusesAfterTheOptInAndBeforeTheClientCheck() {
        // the production matrix: the log-level change is not yet verified on 8.1
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> logLevelGuard(CapabilityMatrix.production()).admit("dev/node1", SET,
                Capability.LOG_LEVEL_WRITE, CLI, logLevelWrite::contains, OPT_IN_KEY));

        assertThat(exception.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(exception.error().message()).startsWith("set_temporary_log_level: ");
        assertThat(gateLookups).containsOnly(DEV);
        assertThat(gateways.calls).isEmpty();
    }

    @Test
    void theClientCheckRefusesLast() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        assertThat(setRefusal("dev/node1").code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(gateways.calls).containsExactly("check dev/node1");
    }

    @Test
    void withoutContactTheConfigurationChecksStillRefuse() {
        assertThat(forgetRefusal("dev").code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(forgetRefusal("dev/inubit9").code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);

        ToolError production = forgetRefusal("prod/inubit01");
        assertThat(production.code()).isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertThat(production.message()).isEqualTo("prod/inubit01 belongs to the production"
            + " group prod; reset_temporary_log_level is refused there");

        ToolError disabled = forgetRefusal("test/inubit01");
        assertThat(disabled.code()).isEqualTo(ErrorCode.WRITE_DISABLED);
        assertThat(disabled.nextStep()).startsWith("To allow reset_temporary_log_level on this"
            + " node, set write.enabled: true");

        logLevelWrite = Set.of();
        ToolError notConfigured = forgetRefusal("dev/node1");
        assertThat(notConfigured.code()).isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(notConfigured.message()).isEqualTo("reset_temporary_log_level is not enabled"
            + " for group dev: unsupported.logLevelWrite is not set");

        policies = Map.of(PROD_OPT_IN, new WritePolicy(PROD_OPT_IN, true, true, true,
            Confirmation.CLIENT, Duration.ofMinutes(5), Optional.of("jdoe")));
        assertThat(forgetRefusal("prod/inubit02").code())
            .isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
        assertNothingContacted();
    }

    @Test
    void withoutContactANodeThatTheGateWouldRefuseIsAdmittedWithoutAsking() {
        pins = Map.of(DEV, VersionSetting.V6_1);
        WriteGuard guard = logLevelGuard(CapabilityMatrix.production());

        WritePolicy admitted = guard.admitWithoutContact("dev/node1", RESET,
            logLevelWrite::contains, OPT_IN_KEY);

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(admitted.confirmation()).isEqualTo(Confirmation.SERVER);
        assertNothingContacted();
        // the same node through the gate: refused, so the gate was not merely lenient
        ToolErrorException gated = catchThrowableOfType(ToolErrorException.class,
            () -> guard.admit("dev/node1", RESET, Capability.LOG_LEVEL_WRITE, CLI,
                logLevelWrite::contains, OPT_IN_KEY));
        assertThat(gated.error().code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
    }

    @Test
    void withoutContactANodeWithoutClientHomeIsAdmittedWithoutAsking() {
        gateways.unavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        WritePolicy admitted = logLevelGuard(LOG_LEVEL_ON_81).admitWithoutContact("dev/node1",
            RESET, logLevelWrite::contains, OPT_IN_KEY);

        assertThat(admitted.node()).isEqualTo(DEV);
        assertNothingContacted();
    }
}
