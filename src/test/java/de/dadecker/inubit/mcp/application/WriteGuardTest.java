package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
            policies::get, gateways);
    }

    private ToolError refusal(String server) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> guard().admit(server));
        assertThat(exception).as("expected a refusal for " + server).isNotNull();
        return exception.error();
    }

    @Test
    void aWriteEnabledServerIsAdmittedAfterTheCliCheck() {
        WritePolicy admitted = guard().admit("dev/node1");

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(gateways.calls).containsExactly("check dev/node1");
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
        assertThat(guard().admit("prod/inubit02").node()).isEqualTo(PROD_OPT_IN);
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
}
