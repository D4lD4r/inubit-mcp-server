package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.application.DevelopmentGuard.Operation;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.model.WritePolicy.Confirmation;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
    /** Feature 009: the {@code versionLine} of each node ({@code V8_1} if absent). */
    private final Map<NodeId, VersionSetting> versionLines = new HashMap<>();
    private final NodeVersions versions = new NodeVersions(
        new MutableClock(Instant.parse("2026-10-18T08:00:00Z")),
        node -> versionLines.getOrDefault(node, VersionSetting.V8_1),
        node -> Duration.ofMinutes(10));
    private final FakeVersionDetection detection = new FakeVersionDetection(versions);
    private ToolErrorException cliUnavailable;
    private Map<NodeId, DevelopmentPolicy> policies = Map.of(
        DEV, policy(DEV, false, true, E2ePolicy.FORBIDDEN),
        TEST, policy(TEST, false, false, E2ePolicy.FORBIDDEN),
        PROD, policy(PROD, true, false, E2ePolicy.FORBIDDEN));

    private static DevelopmentPolicy policy(NodeId node, boolean production, boolean enabled,
        E2ePolicy e2e) {
        return new DevelopmentPolicy(node, production, enabled, Confirmation.SERVER,
            Duration.ofMinutes(5), e2e, e2e == E2ePolicy.FORBIDDEN ? Optional.empty()
                : Optional.of(URI.create("https://inubit-dev.example.test:8443")), false);
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
            }, new VersionGate(versions, detection, CapabilityMatrix.production()),
            group -> Optional.ofNullable(deployModes.get(group)), DEPLOY_TTL);
    }

    private ToolError refusal(String node, Operation operation) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> guard().admit(node, operation));
        assertThat(exception).as("expected a refusal for " + node).isNotNull();
        return exception.error();
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = "RUN_E2E_TEST", mode = EnumSource.Mode.EXCLUDE)
    void aDevelopmentNodeIsAdmittedForTheWritingToolsAfterTheCliCheck(Operation operation) {
        DevelopmentPolicy admitted = guard().admit("dev/node1", operation);

        assertThat(admitted.node()).isEqualTo(DEV);
        assertThat(admitted.enabled()).isTrue();
        assertThat(cliChecks).containsExactly("check dev/node1");
    }

    @Test
    void theOperationsAreTheToolNames() {
        assertThat(List.of(Operation.values()).stream().map(Operation::toolName))
            .containsExactly("import_artifacts", "restore_backup", "set_active",
                "tag_artifacts", "run_e2e_test");
    }

    @Test
    void aGroupIdIsInvalidInput() {
        ToolError error = refusal("dev", Operation.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("exactly one node");
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void anUnknownNodeIsTargetUnknown() {
        ToolError error = refusal("dev/node9", Operation.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void aNodeThatIsNoDevelopmentStageIsNotDevelopment() {
        ToolError error = refusal("test/node1", Operation.TAG_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(error.node()).contains(TEST);
        assertThat(error.message()).contains("test/node1", "tag_artifacts");
        assertThat(error.nextStep()).contains("development.enabled: true");
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void aProductionNodeWithoutDevelopmentIsNotDevelopmentWithItsOwnNextStep() {
        // review M1: never advise development.enabled for a production node
        ToolError error = refusal("prod/node1", Operation.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(error.likelyCause()).contains("production");
        assertThat(error.nextStep()).doesNotContain("development.enabled")
            .contains("development {node}".replace(" {node}", " node"));
    }

    @Test
    void aNodeWithoutPolicyIsNeverAdmitted() {
        policies = Map.of();

        assertThat(refusal("dev/node1", Operation.IMPORT_ARTIFACTS).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void developmentOnProductionIsProtectedAsDefenceInDepth() {
        // ConfigValidator rejects this at startup; the guard refuses it anyway
        policies = Map.of(PROD, policy(PROD, true, true, E2ePolicy.FREE));

        for (Operation operation : Operation.values()) {
            ToolError error = refusal("prod/node1", operation);

            assertThat(error.code()).as(operation.toolName())
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

        ToolError error = refusal("dev/node1", Operation.IMPORT_ARTIFACTS);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.node()).contains(DEV);
    }

    @Test
    void endToEndTestsAreForbiddenByDefault() {
        ToolError error = refusal("dev/node1", Operation.RUN_E2E_TEST);

        assertThat(error.code()).isEqualTo(ErrorCode.E2E_FORBIDDEN);
        assertThat(error.node()).contains(DEV);
        assertThat(error.nextStep()).contains("e2eTests");
        assertThat(cliChecks).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = E2ePolicy.class, names = {"FREE", "CONFIRM"})
    void endToEndTestsAreAdmittedWhereAllowedWithoutACliCheck(E2ePolicy e2e) {
        policies = Map.of(DEV, policy(DEV, false, true, e2e));

        DevelopmentPolicy admitted = guard().admit("dev/node1", Operation.RUN_E2E_TEST);

        assertThat(admitted.e2eTests()).isEqualTo(e2e);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void endToEndTestsNeedADevelopmentNodeFirst() {
        policies = Map.of(TEST, policy(TEST, false, false, E2ePolicy.FREE));
        deployModes = Map.of(); // and test receives no deployments

        assertThat(refusal("test/node1", Operation.RUN_E2E_TEST).code())
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
            Duration.ofMinutes(5), E2ePolicy.FORBIDDEN, Optional.empty(), false));

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
        assertThat(refusal("test", Operation.RESTORE_BACKUP).code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(cliChecks).isEmpty();
    }

    // --- feature 008, T013: a write of repository files is always confirmed on the server ------

    @ParameterizedTest
    @EnumSource(Confirmation.class)
    void aRepositoryWriteIsAlwaysConfirmedOnTheServerAndKeepsTheRest(Confirmation configured) {
        DevelopmentPolicy policy = new DevelopmentPolicy(DEV, false, true, configured,
            Duration.ofMinutes(7), E2ePolicy.CONFIRM,
            Optional.of(URI.create("https://inubit-dev.example.test:8443")), true);

        DevelopmentPolicy write = guard().forRepositoryWrite(policy);

        assertThat(write).isEqualTo(new DevelopmentPolicy(DEV, false, true, Confirmation.SERVER,
            Duration.ofMinutes(7), E2ePolicy.CONFIRM,
            Optional.of(URI.create("https://inubit-dev.example.test:8443")), true));
        assertThat(cliChecks).isEmpty();
    }

    // --- feature 005, T027: end-to-end tests on the nodes of a target group -------------------

    @ParameterizedTest
    @EnumSource(value = E2ePolicy.class, names = {"FREE", "CONFIRM"})
    void endToEndTestsRunOnANodeOfAGroupThatReceivesDeployments(E2ePolicy e2e) {
        policies = Map.of(TEST, policy(TEST, false, false, e2e));

        DevelopmentPolicy admitted = guard().admit("test/node1", Operation.RUN_E2E_TEST);

        assertThat(admitted.node()).isEqualTo(TEST);
        assertThat(admitted.e2eTests()).isEqualTo(e2e);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void onATargetNodeOnlyTheEndToEndTestIsAdmitted() {
        policies = Map.of(TEST, policy(TEST, false, false, E2ePolicy.FREE));

        for (Operation operation : List.of(Operation.IMPORT_ARTIFACTS,
            Operation.SET_ACTIVE, Operation.TAG_ARTIFACTS, Operation.RESTORE_BACKUP)) {
            assertThat(refusal("test/node1", operation).code()).as(operation.toolName())
                .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        }
    }

    @Test
    void endToEndTestsOnATargetNodeFollowItsE2eTestsSetting() {
        assertThat(refusal("test/node1", Operation.RUN_E2E_TEST).code())
            .isEqualTo(ErrorCode.E2E_FORBIDDEN);
    }

    @Test
    void endToEndTestsNeverRunOnAProductionTarget() {
        // the configuration check rejects e2eTests on production; the guard refuses anyway
        policies = Map.of(PROD, policy(PROD, true, false, E2ePolicy.FREE));
        deployModes = Map.of(PROD.group(), DeployMode.EXECUTE);

        assertThat(refusal("prod/node1", Operation.RUN_E2E_TEST).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
    }

    // --- feature 009, T009: the version gate after the configuration checks ------------------

    @Test
    void theConfigurationIsCheckedBeforeTheVersionIsDetected() {
        versionLines.put(TEST, VersionSetting.AUTO);
        versionLines.put(PROD, VersionSetting.AUTO);

        assertThat(refusal("test/node1", Operation.IMPORT_ARTIFACTS).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(refusal("prod/node1", Operation.RUN_E2E_TEST).code())
            .isEqualTo(ErrorCode.NOT_DEVELOPMENT);
        assertThat(detection.requests()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void anUnsupportedVersionIsRefusedBeforeTheCliCheckNamingTheTool(Operation operation) {
        versionLines.put(DEV, VersionSetting.V9_X);
        policies = Map.of(DEV, policy(DEV, false, true, E2ePolicy.FREE));

        ToolError error = refusal("dev/node1", operation);

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(error.message()).startsWith(operation.toolName() + ": INUBIT 9.x (versionLine"
            + " V9_X) on dev/node1 does not support " + (operation == Operation.RUN_E2E_TEST
                ? "end-to-end test" : "development writes"));
        assertThat(error.node()).contains(DEV);
        assertThat(cliChecks).isEmpty();
    }

    @Test
    void theAdmissionWarningsTravelInThePolicyAndSurviveARepositoryWrite() {
        versionLines.put(DEV, VersionSetting.AUTO);
        detection.answer(DEV, "8.1.18");
        versions.clientProbed(DEV, new ClientVersion(version("8.1.17"),
            Optional.of(version("4.0.1")), Optional.of(version("8.1.17"))));
        String mismatch = "StartCLI client 8.1.17 and INUBIT 8.1.18 on dev/node1 differ;"
            + " StartCLI results may be unreliable";

        DevelopmentPolicy admitted = guard().admit("dev/node1", Operation.IMPORT_ARTIFACTS);

        assertThat(admitted.warnings()).containsExactly(mismatch);
        assertThat(guard().forRepositoryWrite(admitted).warnings()).containsExactly(mismatch);
        assertThat(guard().forDeploymentRestore(admitted).warnings()).containsExactly(mismatch);
        assertThat(cliChecks).containsExactly("check dev/node1");
    }

    @Test
    void theEndToEndTestIsNoStartCliCallSoAnExcludingClientDoesNotMatter() {
        versionLines.put(DEV, VersionSetting.AUTO);
        detection.answer(DEV, "8.1.18");
        versions.clientProbed(DEV, new ClientVersion(version("8.0.9"),
            Optional.of(version("4.0.1")), Optional.of(version("8.0.9"))));
        policies = Map.of(DEV, policy(DEV, false, true, E2ePolicy.FREE));

        assertThat(guard().admit("dev/node1", Operation.RUN_E2E_TEST).warnings()).isEmpty();
        assertThat(refusal("dev/node1", Operation.IMPORT_ARTIFACTS).message())
            .startsWith("import_artifacts: StartCLI client 8.0.9 of dev/node1 supports INUBIT");
    }

    private static InubitVersion version(String raw) {
        return InubitVersion.parse(raw).orElseThrow();
    }
}
