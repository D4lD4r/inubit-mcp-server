package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Capability;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T005 (feature 004, research D-1): one test per branch of the development guard, before
 * anything is read from INUBIT.
 */
class DevelopmentGuardTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final NodeId TEST = NodeId.parse("test/node1");
    private static final NodeId PROD = NodeId.parse("prod/node1");

    private final List<String> cliChecks = new CopyOnWriteArrayList<>();
    private ToolErrorException cliUnavailable;
    private Map<NodeId, DevelopmentPolicy> policies = Map.of(
        DEV, policy(DEV, false, true, E2ePolicy.FORBIDDEN),
        TEST, policy(TEST, false, false, E2ePolicy.FORBIDDEN),
        PROD, policy(PROD, true, false, E2ePolicy.FORBIDDEN));

    private static DevelopmentPolicy policy(NodeId node, boolean production, boolean enabled,
        E2ePolicy e2e) {
        return new DevelopmentPolicy(node, production, enabled, Confirmation.SERVER,
            Duration.ofMinutes(5), e2e, e2e == E2ePolicy.FORBIDDEN ? Optional.empty()
                : Optional.of(URI.create("https://inubit-dev.example.test:8443")));
    }

    /** Feature 005: test receives deployments from dev, prod packages from test. */
    private Map<GroupId, DeployMode> deployModes = Map.of(TEST.group(), DeployMode.EXECUTE,
        PROD.group(), DeployMode.PACKAGE_ONLY);
    private static final Duration DEPLOY_TTL = Duration.ofMinutes(30);

    private DevelopmentGuard guard() {
        return new DevelopmentGuard(new TargetResolver(List.of(DEV, TEST, PROD)),
            policies::get, node -> {
                cliChecks.add("check " + node);
                if (cliUnavailable != null) {
                    throw cliUnavailable;
                }
            }, group -> Optional.ofNullable(deployModes.get(group)), DEPLOY_TTL);
    }

    private ToolError refusal(String node, Capability capability) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> guard().admit(node, capability));
        assertThat(exception).as("expected a refusal for " + node).isNotNull();
        return exception.error();
    }

    @ParameterizedTest
    @EnumSource(value = Capability.class, names = "RUN_E2E_TEST", mode = EnumSource.Mode.EXCLUDE)
    void aDevelopmentNodeIsAdmittedForTheWritingToolsAfterTheCliCheck(Capability capability) {
        DevelopmentPolicy admitted = guard().admit("dev/node1", capability);

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(admitted.enabled()).isTrue();
        assertThat(cliChecks).containsExactly("check dev/node1");
    }

    @Test
    void theCapabilitiesAreTheToolNames() {
        assertThat(List.of(Capability.values()).stream().map(Capability::toolName))
            .containsExactly("import_artifacts", "restore_backup", "set_active",
                "tag_artifacts", "run_e2e_test");
    }

    @Test
    void aGroupIdIsInvalidInput() {
        ToolError error = refusal("dev", Capability.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("exactly one node");
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void anUnknownNodeIsTargetUnknown() {
        ToolError error = refusal("dev/node9", Capability.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void aNodeThatIsNoDevelopmentStageIsNotDevelopment() {
        ToolError error = refusal("test/node1", Capability.TAG_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(error.node()).contains(TEST);
        assertThat(error.message()).contains("test/node1", "tag_artifacts");
        assertThat(error.nextStep()).contains("development.enabled: true");
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void aProductionNodeWithoutDevelopmentIsNotDevelopmentWithItsOwnNextStep() {
        // review M1: never advise development.enabled for a production node
        ToolError error = refusal("prod/node1", Capability.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(error.likelyCause()).contains("production");
        assertThat(error.nextStep()).doesNotContain("development.enabled")
            .contains("development {node}".replace(" {node}", " node"));
    }

    @Test
    void aNodeWithoutPolicyIsNeverAdmitted() {
        policies = Map.of();

        assertThat(refusal("dev/node1", Capability.IMPORT_ARTIFACTS).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void developmentOnProductionIsProtectedAsDefenceInDepth() {
        // ConfigValidator rejects this at startup; the guard refuses it anyway
        policies = Map.of(PROD, policy(PROD, true, true, E2ePolicy.FREE));

        for (Capability capability : Capability.values()) {
            ToolError error = refusal("prod/node1", capability);

            assertThat(error.code()).as(capability.toolName())
                .isEqualTo(ErrorCode.PRODUCTION_PROTECTED);
            assertThat(error.node()).contains(PROD);
            assertThat(error.likelyCause()).contains("production");
        }
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void withoutCliTheWritingToolsAreCliUnavailable() {
        cliUnavailable = new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No CLI home is configured for dev/node1", "cliHome is not set", "Set cliHome")
            .withNode(DEV));

        ToolError error = refusal("dev/node1", Capability.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void endToEndTestsAreForbiddenByDefault() {
        ToolError error = refusal("dev/node1", Capability.RUN_E2E_TEST);

        assertThat(error.code()).isEqualTo(ErrorCode.E2E_FORBIDDEN);
        assertThat(error.node()).contains(DEV);
        assertThat(error.nextStep()).contains("e2eTests");
        assertThat(cliChecks).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = E2ePolicy.class, names = {"FREE", "CONFIRM"})
    void endToEndTestsAreAdmittedWhereAllowedWithoutACliCheck(E2ePolicy e2e) {
        policies = Map.of(DEV, policy(DEV, false, true, e2e));

        DevelopmentPolicy admitted = guard().admit("dev/node1", Capability.RUN_E2E_TEST);

        assertThat(admitted.e2eTests()).isEqualTo(e2e);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void endToEndTestsNeedADevelopmentNodeFirst() {
        policies = Map.of(TEST, policy(TEST, false, false, E2ePolicy.FREE));

        assertThat(refusal("test/node1", Capability.RUN_E2E_TEST).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
    }

    // --- feature 005, T026: the restore of a deployment backup ---------------------------------

    @Test
    void aNodeOfAGroupThatReceivesDeploymentsIsAdmittedForADeploymentRestore() {
        DevelopmentPolicy admitted = guard().admitDeploymentRestore("test/node1");

        assertThat(admitted.node()).isEqualTo(TEST);
        assertThat(admitted.confirmation()).isEqualTo(Confirmation.SERVER);
        assertThat(admitted.confirmationTtl()).isEqualTo(DEPLOY_TTL);
        assertThat(cliChecks).containsExactly("check test/node1");
    }

    @Test
    void aDeploymentRestoreIsAlwaysConfirmedOnTheServer() {
        policies = Map.of(TEST, new DevelopmentPolicy(TEST, false, true, Confirmation.CLIENT,
            Duration.ofMinutes(5), E2ePolicy.FORBIDDEN, Optional.empty()));

        assertThat(guard().admitDeploymentRestore("test/node1").confirmation())
            .isEqualTo(Confirmation.SERVER);
    }

    @Test
    void aPackageOnlyOrAnUnchainedGroupIsNeverAdmittedForADeploymentRestore() {
        for (String node : List.of("prod/node1", "dev/node1")) {
            ToolErrorException e = catchThrowableOfType(ToolErrorException.class,
                () -> guard().admitDeploymentRestore(node));

            assertThat(e).as(node).isNotNull();
            assertThat(e.error().code()).as(node).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
            assertThat(e.error().message()).as(node).contains("restore_backup");
        }
        assertThat(refusal("test", Capability.RESTORE_BACKUP).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cliChecks).isEmpty();
    }
}
