package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T014 (feature 005, US2, research D-3): the target is one group of the chain, the source is
 * never an input, the tag and owner can be passed to StartCLI — every refusal is audited and
 * happens before anything is launched.
 */
class DeployGuardTest {

    @TempDir
    Path temp;

    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();

    private static StageChain chain() {
        Map<GroupId, StageChain.ChainLink> targets = new LinkedHashMap<>();
        targets.put(new GroupId("int"), new StageChain.ChainLink(new GroupId("dev"),
            DeployMode.EXECUTE, List.of()));
        targets.put(new GroupId("prod"), new StageChain.ChainLink(new GroupId("int"),
            DeployMode.PACKAGE_ONLY, List.of()));
        return new StageChain(targets);
    }

    private DeployGuard guard(Map<GroupId, String> owners) {
        List<NodeId> nodes = List.of(NodeId.parse("dev/node1"), NodeId.parse("dev/node2"),
            NodeId.parse("int/node1"), NodeId.parse("int/node2"), NodeId.parse("prod/node1"));
        return new DeployGuard(chain(), new TargetResolver(nodes),
            group -> Optional.ofNullable(owners.get(group)), audit::add, "acme",
            new MutableClock(Instant.parse("2026-10-07T10:00:00Z")), UUID::randomUUID);
    }

    private DeployGuard guard() {
        return guard(Map.of(new GroupId("int"), "jdoe", new GroupId("prod"), "jdoe"));
    }

    private ToolErrorException refused(DeployGuard guard, String target, String tag,
        Optional<String> owner) {
        try {
            guard.admit(new DeployGuard.Request(target, tag, owner, Optional.empty(),
                Optional.of("client/1.0")));
        } catch (ToolErrorException e) {
            return e;
        }
        throw new AssertionError("not refused");
    }

    @Test
    void aChainedTargetIsAdmittedWithItsSourceNodesAndOwner() {
        DeployGuard.Admitted admitted = guard().admit(new DeployGuard.Request("int", "REL-1",
            Optional.empty(), Optional.empty(), Optional.empty()));

        assertThat(admitted.target()).isEqualTo(new GroupId("int"));
        assertThat(admitted.source()).isEqualTo(new GroupId("dev"));
        assertThat(admitted.mode()).isEqualTo(DeployMode.EXECUTE);
        assertThat(admitted.targetNodes()).extracting(NodeId::value)
            .containsExactly("int/node1", "int/node2");
        assertThat(admitted.sourceNodes()).extracting(NodeId::value)
            .containsExactly("dev/node1", "dev/node2");
        assertThat(admitted.owner()).isEqualTo("jdoe");
        assertThat(admitted.tag()).isEqualTo("REL-1");
        assertThat(guard().admit(new DeployGuard.Request("prod", "REL-1", Optional.of("OWNERS"),
            Optional.empty(), Optional.empty())).owner()).isEqualTo("OWNERS");
        assertThat(audit).isEmpty();
    }

    @Test
    void aGroupWithoutDeployIsAChainViolationNamingTheChain() {
        ToolErrorException e = refused(guard(), "dev", "REL-1", Optional.empty());

        assertThat(e.error().code()).isEqualTo(ErrorCode.CHAIN_VIOLATION);
        assertThat(e.error().message()).contains("dev", "receives no deployments");
        assertThat(e.error().nextStep()).contains("int (from dev)",
            "prod (from int, package only)");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.capability()).isEqualTo("deploy_release");
            assertThat(record.node()).isEqualTo("dev");
            assertThat(record.group()).contains("dev");
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.inputs()).containsEntry("tag", "REL-1");
            assertThat(record.reason()).get().asString().startsWith("CHAIN_VIOLATION");
            assertThat(record.mcpClient()).contains("client/1.0");
        });
    }

    @Test
    void aNodeIdOrAnUnknownGroupIsRefused() {
        assertThat(refused(guard(), "int/node1", "REL-1", Optional.empty()).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused(guard(), "qa", "REL-1", Optional.empty()).error().code())
            .isEqualTo(ErrorCode.TARGET_UNKNOWN);
        assertThat(refused(guard(), "INT!", "REL-1", Optional.empty()).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit).hasSize(3).allMatch(r -> r.outcome() == AuditOutcome.REFUSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "REL*", "REL?", "a'b", "-rf", "REL/1"})
    void aTagStartCliCannotTakeIsInvalid(String tag) {
        ToolErrorException e = refused(guard(), "int", tag, Optional.empty());

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(e.error().message()).contains("tag");
        assertThat(audit).hasSize(1);
    }

    @Test
    void theOwnerIsTheTargetsInventoryOwnerOrAValidGivenOne() {
        assertThat(refused(guard(Map.of()), "int", "REL-1", Optional.empty()).error().code())
            .isEqualTo(ErrorCode.NOT_CONFIGURED);
        assertThat(refused(guard(), "int", "REL-1", Optional.of("it's")).error().code())
            .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void aConfirmationCodeOfTheWrongShapeIsInvalidAndAuditedAsExecute() {
        ToolErrorException e;
        try {
            guard().admit(new DeployGuard.Request("int", "REL-1", Optional.empty(),
                Optional.of("short"), Optional.empty()));
            throw new AssertionError("not refused");
        } catch (ToolErrorException refused) {
            e = refused;
        }

        assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(audit).singleElement().satisfies(record ->
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE));
    }

    @Test
    void refusalsLaunchNothing() throws IOException {
        DeployHarness harness = new DeployHarness(temp);

        refused(guard(), "dev", "REL-1", Optional.empty());
        refused(guard(), "int", "REL*", Optional.empty());

        assertThat(harness.launches()).isEmpty();
        harness.verifyComplete();
    }
}
