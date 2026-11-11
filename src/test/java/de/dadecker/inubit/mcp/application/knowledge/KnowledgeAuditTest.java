package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Feature 019 T029 (research D-13): the audit records of one {@code record_knowledge} call - one
 * {@code REFUSED} record before the {@code PENDING} one, otherwise {@code PENDING} then the final
 * record with the same {@code auditId}; node {@code -}; inputs name (masked, or {@code ***}),
 * mode and sizes only.
 */
class KnowledgeAuditTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000019");
    private static final Instant NOW = Instant.parse("2026-11-10T10:15:00Z");
    private static final ToolError INVALID = ToolError.of(ErrorCode.INVALID_INPUT,
        "content must not be empty", "The call gave an empty content", "Give the text");

    private final RecordingAuditPort port = new RecordingAuditPort();

    private KnowledgeAudit audit(String mode) {
        return new KnowledgeAudit(new KnowledgeAudit.Dependencies(port,
            Clock.fixed(NOW, ZoneOffset.UTC), "default", () -> ID), mode,
            Optional.of("claude-code/2.0"));
    }

    @Test
    void aRefusalBeforeTheNameIsCheckedIsOneRefusedRecordWithTheNameHidden() {
        KnowledgeAudit audit = audit("create");

        ToolErrorException refused = audit.refused(INVALID);

        assertThat(refused.error()).isEqualTo(INVALID);
        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.auditId()).isEqualTo(ID);
            assertThat(record.timestamp()).isEqualTo(NOW);
            assertThat(record.profile()).isEqualTo("default");
            assertThat(record.node()).isEqualTo("-");
            assertThat(record.group()).isEmpty();
            assertThat(record.account()).isEmpty();
            assertThat(record.capability()).isEqualTo("record_knowledge");
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.reason()).contains("INVALID_INPUT: content must not be empty");
            assertThat(record.mcpClient()).contains("claude-code/2.0");
            assertThat(record.inputs()).containsExactly(entry("name", "***"),
                entry("mode", "create"));
        });
        assertThat(audit.auditId()).isEqualTo(ID);
    }

    @Test
    void pendingThenExecutedShareTheIdAndTheFinalRecordCarriesTheSizes() {
        KnowledgeAudit audit = audit("append");
        audit.name("partners/acme");

        audit.pending();
        audit.sizes(120, 180);
        Optional<String> warning = audit.outcome(AuditOutcome.EXECUTED, "written");

        assertThat(warning).isEmpty();
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records.get(0).inputs()).containsExactly(entry("name", "partners/acme"),
            entry("mode", "append"));
        assertThat(port.records.get(0).reason()).isEmpty();
        assertThat(port.records.get(1).inputs()).containsExactly(
            entry("name", "partners/acme"), entry("mode", "append"), entry("sizeBefore", "120"),
            entry("sizeAfter", "180"));
        assertThat(port.records.get(1).reason()).contains("written");
    }

    @Test
    void aSizeBeforeAloneIsAuditedWithoutASizeAfter() {
        KnowledgeAudit audit = audit("replace");
        audit.name("a");
        audit.pending();
        audit.sizeBefore(42);

        audit.outcome(AuditOutcome.REFUSED, "CONFLICT: the note changed");

        assertThat(port.records.get(1).inputs()).containsExactly(entry("name", "a"),
            entry("mode", "replace"), entry("sizeBefore", "42"));
    }

    @Test
    void theNameIsAuditedAsOneLineCutAt120CodePoints() {
        KnowledgeAudit audit = audit("create");
        audit.name("a\nb" + "x".repeat(200));

        audit.refused(INVALID);

        String name = port.records.get(0).inputs().get("name");
        assertThat(name).startsWith("a bxx").endsWith("…").doesNotContain("\n");
        assertThat(name.codePointCount(0, name.length())).isEqualTo(120);
    }

    @Test
    void aRefusalWhoseRecordCannotBeWrittenBecomesInternal() {
        port.failing.add(AuditOutcome.REFUSED);

        ToolErrorException refused = audit("create").refused(INVALID);

        assertThat(refused.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(refused.error().message()).isEqualTo("The audit record could not be"
            + " written; the call was refused anyway (INVALID_INPUT: content must not be empty);"
            + " nothing was written");
        assertThat(port.records).isEmpty();
    }

    @Test
    void aPendingRecordThatCannotBeWrittenBecomesInternalAndNothingMayBeWritten() {
        port.failing.add(AuditOutcome.PENDING);
        KnowledgeAudit audit = audit("create");
        audit.name("a");

        assertThatThrownBy(audit::pending).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(e.error().message())
                .isEqualTo("The audit record could not be written; nothing was written");
        });
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.FAILED, "x"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFinalRecordThatCannotBeWrittenBecomesAWarningNamingThePendingRecord() {
        port.failing.add(AuditOutcome.EXECUTED);
        KnowledgeAudit audit = audit("create");
        audit.name("a");
        audit.pending();

        Optional<String> warning = audit.outcome(AuditOutcome.EXECUTED, "written");

        assertThat(warning).contains("The final audit record could not be written (only the"
            + " pending record " + ID + " exists); check the audit directory (auditDirectory in"
            + " the configuration)");
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING);
    }

    @Test
    void theOrderOfTheRecordsIsEnforced() {
        KnowledgeAudit audit = audit("create");
        audit.name("a");
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.EXECUTED, "x"))
            .isInstanceOf(IllegalStateException.class);
        audit.pending();
        assertThatThrownBy(() -> audit.refused(INVALID)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.PENDING, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.PARTIALLY_COMPLETED, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        audit.outcome(AuditOutcome.FAILED, "INTERNAL: x");
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.EXECUTED, "x"))
            .isInstanceOf(IllegalStateException.class);
    }
}
