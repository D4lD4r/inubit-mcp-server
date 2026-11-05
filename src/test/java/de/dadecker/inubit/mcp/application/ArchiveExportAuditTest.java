package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Feature 015 T006 (research R-12, R-13): the audit records of one {@code export_archives} call.
 */
class ArchiveExportAuditTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000015");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final ToolError INVALID = ToolError.of(ErrorCode.INVALID_INPUT,
        "acknowledgeClearTextSecrets must be true", "The archives contain secrets in clear text",
        "Set acknowledgeClearTextSecrets to true");

    private final RecordingAudit port = new RecordingAudit();

    @Test
    void refusedAppendsOneRefusedRecord() {
        ArchiveExportAudit audit = audit(List.of("Workflow-0001"), Optional.of("handover"));
        audit.node(DEV, Optional.of("jdoe"));

        ToolErrorException refused = audit.refused(INVALID);

        assertThat(refused.error()).isEqualTo(INVALID);
        assertThat(port.records).hasSize(1);
        AuditRecord record = port.records.get(0);
        assertThat(record.auditId()).isEqualTo(ID);
        assertThat(audit.auditId()).isEqualTo(ID);
        assertThat(record.capability()).isEqualTo("export_archives");
        assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.reason()).contains(
            "INVALID_INPUT: acknowledgeClearTextSecrets must be true");
        assertThat(record.timestamp()).isEqualTo(NOW);
        assertThat(record.profile()).isEqualTo("default");
        assertThat(record.node()).isEqualTo("dev/node1");
        assertThat(record.group()).contains("dev");
        assertThat(record.account()).contains("jdoe");
        assertThat(record.mcpClient()).contains("claude-code/2.0");
    }

    @Test
    void refusalOfAnUnresolvedTargetNamesTheTargetAsRequested() {
        ArchiveExportAudit audit = new ArchiveExportAudit(dependencies(), "Unknown Target!",
            Optional.empty(), "GRP-01", "/archives", List.of(), false, Optional.empty(),
            Optional.empty());

        audit.refused(ToolError.of(ErrorCode.TARGET_UNKNOWN, "Unknown target", "cause", "next"));

        AuditRecord record = port.records.get(0);
        assertThat(record.node()).isEqualTo("Unknown Target!");
        assertThat(record.group()).isEmpty();
        assertThat(record.account()).isEmpty();
        assertThat(record.mcpClient()).isEmpty();
        assertThat(record.inputs()).containsExactly(
            entry("diagramGroup", "GRP-01"),
            entry("targetFolder", "/archives"),
            entry("acknowledgeClearTextSecrets", "false"));
    }

    @Test
    void refusalOfAGroupTargetCarriesTheGroup() {
        ArchiveExportAudit audit = new ArchiveExportAudit(dependencies(), "dev",
            Optional.empty(), "GRP-01", "/archives", List.of(), true, Optional.empty(),
            Optional.empty());

        audit.refused(INVALID);

        assertThat(port.records.get(0).node()).isEqualTo("dev");
        assertThat(port.records.get(0).group()).contains("dev");
    }

    @ParameterizedTest
    @EnumSource(value = AuditOutcome.class,
        names = {"EXECUTED", "REFUSED", "FAILED", "PARTIALLY_COMPLETED"})
    void pendingThenOutcomeAppendTwoRecordsWithTheSameAuditId(AuditOutcome outcome) {
        ArchiveExportAudit audit = audit(List.of(), Optional.of("handover"));
        audit.node(DEV, Optional.of("jdoe"));

        audit.pending();
        Optional<String> warning = audit.outcome(outcome, "2 written, 0 failed");

        assertThat(warning).isEmpty();
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, outcome);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records).extracting(AuditRecord::capability)
            .containsOnly("export_archives");
        assertThat(port.records).extracting(AuditRecord::step)
            .containsOnly(AuditRecord.Step.EXECUTE);
        assertThat(port.records.get(0).reason()).isEmpty();
        assertThat(port.records.get(1).reason()).contains("2 written, 0 failed");
        assertThat(port.records).extracting(AuditRecord::account).containsOnly(Optional.of("jdoe"));
        assertThat(port.records).extracting(AuditRecord::mcpClient)
            .containsOnly(Optional.of("claude-code/2.0"));
    }

    @Test
    void outcomeOnlyAcceptsFinalOutcomes() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        audit.pending();

        assertThatThrownBy(() -> audit.outcome(AuditOutcome.PENDING, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.CHALLENGE_ISSUED, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputsAreInTheFixedOrderWithWorkflowsAndFilesNumbered() {
        ArchiveExportAudit audit = audit(List.of("Workflow-0001", "Workflow-0002"),
            Optional.of("handover"));
        audit.node(DEV, Optional.empty());
        audit.owner("OWNERS");
        audit.pending();
        audit.files(List.of("Workflow-0001.zip", "Workflow-0002.zip"));
        audit.outcome(AuditOutcome.EXECUTED, "2 archives written");

        assertThat(port.records.get(0).inputs()).containsExactly(
            entry("owner", "OWNERS"),
            entry("diagramGroup", "GRP-01"),
            entry("targetFolder", "/archives"),
            entry("workflow.1", "Workflow-0001"),
            entry("workflow.2", "Workflow-0002"),
            entry("acknowledgeClearTextSecrets", "true"),
            entry("reason", "handover"));
        assertThat(port.records.get(1).inputs()).containsExactly(
            entry("owner", "OWNERS"),
            entry("diagramGroup", "GRP-01"),
            entry("targetFolder", "/archives"),
            entry("workflow.1", "Workflow-0001"),
            entry("workflow.2", "Workflow-0002"),
            entry("file.1", "Workflow-0001.zip"),
            entry("file.2", "Workflow-0002.zip"),
            entry("acknowledgeClearTextSecrets", "true"),
            entry("reason", "handover"));
    }

    @Test
    void selectedWorkflowsReplaceTheEmptyRequest() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        audit.pending();
        audit.workflows(List.of("Workflow-0004", "Workflow-0005", "Workflow-0006"));
        audit.outcome(AuditOutcome.EXECUTED, "3 archives written");

        assertThat(port.records.get(0).inputs()).doesNotContainKey("workflow.1");
        assertThat(port.records.get(1).inputs()).containsEntry("workflow.1", "Workflow-0004")
            .containsEntry("workflow.3", "Workflow-0006").doesNotContainKey("workflow.4")
            .containsEntry("owner", "OWNERS");
    }

    @Test
    void everyValueIsCutTo500Chars() {
        String longValue = "x".repeat(600);
        ArchiveExportAudit audit = new ArchiveExportAudit(dependencies(), "dev/node1",
            Optional.of(longValue), longValue, longValue, List.of(longValue), true,
            Optional.of(longValue), Optional.of("claude-code/2.0"));
        audit.files(List.of(longValue));

        audit.refused(INVALID);

        assertThat(port.records.get(0).inputs()).hasSize(7)
            .allSatisfy((key, value) -> assertThat(value.length()).isLessThanOrEqualTo(500));
        assertThat(port.records.get(0).inputs().get("owner")).hasSize(500);
        assertThat(port.records.get(0).inputs().get("file.1")).hasSize(500);
    }

    @Test
    void refusedReturnsInternalWhenTheRecordCannotBeWritten() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        port.failing = true;

        ToolErrorException refused = audit.refused(INVALID);

        ToolError error = refused.error();
        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("INVALID_INPUT")
            .contains("acknowledgeClearTextSecrets must be true")
            .contains("nothing was exported or written");
        assertThat(error.nextStep()).contains("auditDirectory");
        assertThat(port.records).isEmpty();
    }

    @Test
    void pendingThrowsInternalWhenTheRecordCannotBeWritten() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        audit.node(DEV, Optional.empty());
        port.failing = true;

        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            audit::pending);

        assertThat(thrown).isNotNull();
        assertThat(thrown.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(thrown.error().message())
            .startsWith("The audit record could not be written; nothing was exported");
        assertThat(thrown.error().nextStep()).contains("auditDirectory");
        assertThat(thrown.error().node()).contains(DEV);
    }

    @Test
    void aFailingOutcomeReturnsAWarningNamingTheAuditId() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        audit.pending();
        port.failing = true;

        Optional<String> warning = audit.outcome(AuditOutcome.PARTIALLY_COMPLETED,
            "1 written, 1 failed (INTERNAL), 1 not written");

        assertThat(warning).hasValueSatisfying(text -> assertThat(text)
            .contains(ID.toString()).contains("auditDirectory"));
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING);
    }

    @Test
    void theCallOrderIsEnforced() {
        ArchiveExportAudit audit = audit(List.of(), Optional.empty());
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.EXECUTED, "done"))
            .isInstanceOf(IllegalStateException.class);
        audit.pending();
        assertThatThrownBy(audit::pending).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit.refused(INVALID))
            .isInstanceOf(IllegalStateException.class);
        audit.outcome(AuditOutcome.EXECUTED, "done");
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.EXECUTED, "done"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    private ArchiveExportAudit audit(List<String> workflows, Optional<String> reason) {
        return new ArchiveExportAudit(dependencies(), "dev/node1", Optional.of("OWNERS"),
            "GRP-01", "/archives", workflows, true, reason, Optional.of("claude-code/2.0"));
    }

    private ArchiveExportAudit.Dependencies dependencies() {
        List<UUID> ids = new ArrayList<>(List.of(ID));
        return new ArchiveExportAudit.Dependencies(port, new MutableClock(NOW), "default",
            () -> ids.remove(0));
    }

    private static java.util.Map.Entry<String, String> entry(String key, String value) {
        return java.util.Map.entry(key, value);
    }

    /** Records every appended audit record; fails every append while {@code failing}. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new java.io.IOException("disk full"));
            }
            records.add(record);
        }
    }
}
