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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Feature 021 T020 (FR-015, research D-18): the audit records of one {@code download_server_log}
 * call.
 */
class ServerLogAuditTest {

    private static final NodeId PROD = NodeId.parse("prod/node1");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000021");
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final Path FILE = Path.of("/logs/prod-node1-webservice.log");
    private static final ToolError NOT_CONFIGURED = ToolError.of(ErrorCode.NOT_CONFIGURED,
        "download_server_log for the web service log is not enabled for group prod:"
            + " unsupported.serverLogRead is not set", "cause", "next");
    private static final ToolError UNSUPPORTED = ToolError.of(ErrorCode.UNSUPPORTED_VERSION,
        "webservice_log_read is not supported on INUBIT 6.1", "cause", "next");

    private final RecordingAudit port = new RecordingAudit();

    @Test
    void refusalBeforePendingIsOneRefusedRecord() {
        ServerLogAudit audit = audit("webservice", OptionalInt.of(50), OptionalInt.of(5000), true);
        audit.node(PROD, Optional.of("jdoe"));

        ToolErrorException refused = audit.refused(NOT_CONFIGURED);

        assertThat(refused.error()).isEqualTo(NOT_CONFIGURED);
        assertThat(port.records).hasSize(1);
        AuditRecord record = port.records.get(0);
        assertThat(record.auditId()).isEqualTo(ID);
        assertThat(audit.auditId()).isEqualTo(ID);
        assertThat(record.capability()).isEqualTo("download_server_log");
        assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.reason()).contains("NOT_CONFIGURED: download_server_log for the web"
            + " service log is not enabled for group prod: unsupported.serverLogRead is not set");
        assertThat(record.timestamp()).isEqualTo(NOW);
        assertThat(record.profile()).isEqualTo("default");
        assertThat(record.node()).isEqualTo("prod/node1");
        assertThat(record.group()).contains("prod");
        assertThat(record.account()).contains("jdoe");
        assertThat(record.mcpClient()).contains("claude-code/2.0");
        assertThat(record.inputs()).containsExactly(
            entry("log", "webservice"),
            entry("targetFolder", "/logs"),
            entry("tailLines", "50"),
            entry("maxChars", "5000"),
            entry("containsGiven", "true"));
        assertThat(record.details()).isEmpty();
    }

    @Test
    void refusalOfAnInvalidRequestRecordsTheInputsAsRequested() {
        ServerLogAudit audit = new ServerLogAudit(dependencies(), "Unknown Target!", "syslog",
            "relative", OptionalInt.of(0), OptionalInt.of(200_000), false, Optional.empty());

        audit.refused(ToolError.of(ErrorCode.INVALID_INPUT, "Invalid tailLines 0 (1–2000)",
            "cause", "next"));

        AuditRecord record = port.records.get(0);
        assertThat(record.node()).isEqualTo("Unknown Target!");
        assertThat(record.group()).isEmpty();
        assertThat(record.account()).isEmpty();
        assertThat(record.mcpClient()).isEmpty();
        assertThat(record.inputs()).containsExactly(
            entry("log", "syslog"),
            entry("targetFolder", "relative"),
            entry("tailLines", "0"),
            entry("maxChars", "200000"),
            entry("containsGiven", "false"));
    }

    @Test
    void refusalOfAGroupTargetCarriesTheGroup() {
        ServerLogAudit audit = new ServerLogAudit(dependencies(), "prod", "trace", "/logs",
            OptionalInt.empty(), OptionalInt.empty(), false, Optional.empty());

        audit.refused(ToolError.of(ErrorCode.INVALID_INPUT, "Not one server", "cause", "next"));

        assertThat(port.records.get(0).node()).isEqualTo("prod");
        assertThat(port.records.get(0).group()).contains("prod");
    }

    @Test
    void absentBoundsAreRecordedWithTheirDefaults() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);

        audit.refused(NOT_CONFIGURED);

        assertThat(port.records.get(0).inputs()).containsExactly(
            entry("log", "trace"),
            entry("targetFolder", "/logs"),
            entry("tailLines", "200"),
            entry("maxChars", "20000"),
            entry("containsGiven", "false"));
    }

    @ParameterizedTest
    @EnumSource(value = AuditOutcome.class, names = {"EXECUTED", "FAILED"})
    void pendingThenOutcomeAppendTwoRecordsWithTheSameAuditId(AuditOutcome outcome) {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        audit.node(PROD, Optional.of("jdoe"));

        audit.pending();
        Optional<String> warning = audit.outcome(outcome, "TIMEOUT: the download took longer");

        assertThat(warning).isEmpty();
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, outcome);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records).extracting(AuditRecord::capability)
            .containsOnly("download_server_log");
        assertThat(port.records).extracting(AuditRecord::step)
            .containsOnly(AuditRecord.Step.EXECUTE);
        assertThat(port.records.get(0).reason()).isEmpty();
        assertThat(port.records.get(1).reason()).contains("TIMEOUT: the download took longer");
        assertThat(port.records).extracting(AuditRecord::account).containsOnly(Optional.of("jdoe"));
        assertThat(port.records).extracting(AuditRecord::node).containsOnly("prod/node1");
    }

    @Test
    void aVersionGateRefusalAfterPendingClosesTheSameAuditId() {
        ServerLogAudit audit = audit("webservice", OptionalInt.empty(), OptionalInt.empty(),
            false);
        audit.node(PROD, Optional.empty());
        audit.pending();

        Optional<String> warning = audit.outcome(AuditOutcome.REFUSED,
            UNSUPPORTED.code() + ": " + UNSUPPORTED.message());

        assertThat(warning).isEmpty();
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records.get(1).reason())
            .contains("UNSUPPORTED_VERSION: webservice_log_read is not supported on INUBIT 6.1");
        assertThatThrownBy(() -> audit.refused(UNSUPPORTED))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theExecutedRecordCarriesFileSizeAndInubitNodeAsDetails() {
        ServerLogAudit audit = audit("webservice", OptionalInt.of(10), OptionalInt.empty(), true);
        audit.node(PROD, Optional.of("jdoe"));
        audit.pending();
        audit.downloaded(123_456_789_012L, Optional.of("node-a"));
        audit.file(FILE);
        audit.outcome(AuditOutcome.EXECUTED, "written");

        assertThat(port.records.get(0).details()).isEmpty();
        assertThat(port.records.get(1).details()).containsExactly(
            Map.entry("file", FILE.toString()),
            Map.entry("sizeBytes", 123_456_789_012L),
            Map.entry("inubitNode", "node-a"));
        assertThat(port.records.get(1).inputs()).containsExactly(
            entry("log", "webservice"),
            entry("targetFolder", "/logs"),
            entry("tailLines", "10"),
            entry("maxChars", "20000"),
            entry("containsGiven", "true"));
    }

    @Test
    void aTraceDownloadHasNoInubitNodeAndAFailureBeforePlacementNoFile() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        audit.node(PROD, Optional.empty());
        audit.pending();
        audit.downloaded(42L, Optional.empty());
        audit.outcome(AuditOutcome.FAILED, "FILE_EXISTS: no free name");

        assertThat(port.records.get(1).details()).containsExactly(
            Map.entry("sizeBytes", 42L));
    }

    @Test
    void outcomeOnlyAcceptsFinalOutcomesOfThisTool() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        audit.pending();

        assertThatThrownBy(() -> audit.outcome(AuditOutcome.PENDING, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.PARTIALLY_COMPLETED, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.CHALLENGE_ISSUED, "x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTextValueIsCutTo500Chars() {
        String longValue = "x".repeat(600);
        ServerLogAudit audit = new ServerLogAudit(dependencies(), longValue, longValue,
            longValue, OptionalInt.empty(), OptionalInt.empty(), false, Optional.empty());
        audit.pending();
        audit.downloaded(1L, Optional.of(longValue));
        audit.file(Path.of("/" + longValue));
        audit.outcome(AuditOutcome.EXECUTED, "written");

        AuditRecord record = port.records.get(1);
        assertThat(record.node()).hasSize(500);
        assertThat(record.inputs().get("log")).hasSize(500);
        assertThat(record.inputs().get("targetFolder")).hasSize(500);
        assertThat((String) record.details().get("file")).hasSize(500);
        assertThat((String) record.details().get("inubitNode")).hasSize(500);
    }

    @Test
    void refusedReturnsInternalWhenTheRecordCannotBeWritten() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        port.failing = true;

        ToolErrorException refused = audit.refused(NOT_CONFIGURED);

        ToolError error = refused.error();
        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("NOT_CONFIGURED")
            .contains("unsupported.serverLogRead is not set")
            .contains("nothing was downloaded or written");
        assertThat(error.nextStep()).contains("auditDirectory");
        assertThat(port.records).isEmpty();
    }

    @Test
    void pendingThrowsInternalWhenTheRecordCannotBeWritten() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        audit.node(PROD, Optional.empty());
        port.failing = true;

        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            audit::pending);

        assertThat(thrown).isNotNull();
        assertThat(thrown.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(thrown.error().message())
            .startsWith("The audit record could not be written; nothing was downloaded");
        assertThat(thrown.error().nextStep()).contains("auditDirectory");
        assertThat(thrown.error().node()).contains(PROD);
    }

    @Test
    void anUnwritableFinalRecordBecomesAWarningNamingTheAuditId() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        audit.pending();
        port.failing = true;

        Optional<String> warning = audit.outcome(AuditOutcome.EXECUTED, "written");

        assertThat(warning).hasValueSatisfying(text -> assertThat(text)
            .contains(ID.toString()).contains("auditDirectory"));
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING);
    }

    @Test
    void theCallOrderIsEnforced() {
        ServerLogAudit audit = audit("trace", OptionalInt.empty(), OptionalInt.empty(), false);
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.EXECUTED, "written"))
            .isInstanceOf(IllegalStateException.class);
        audit.pending();
        assertThatThrownBy(audit::pending).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> audit.refused(NOT_CONFIGURED))
            .isInstanceOf(IllegalStateException.class);
        audit.outcome(AuditOutcome.EXECUTED, "written");
        assertThatThrownBy(() -> audit.outcome(AuditOutcome.FAILED, "INTERNAL"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
    }

    @Test
    void neitherTheSearchTextNorLogContentReachesAnyRecord() {
        Optional<String> contains = Optional.of("s3cr3t-guess");
        String logLine = "2026-11-12 10:00:00 INFO password=Hunter2-in-the-log";

        ServerLogAudit refusedCall = audit("webservice", OptionalInt.empty(),
            OptionalInt.empty(), contains.isPresent());
        refusedCall.refused(NOT_CONFIGURED);

        ServerLogAudit executedCall = audit("webservice", OptionalInt.empty(),
            OptionalInt.empty(), contains.isPresent());
        executedCall.node(PROD, Optional.of("jdoe"));
        executedCall.pending();
        executedCall.downloaded(logLine.length(), Optional.of("node-a"));
        executedCall.file(FILE);
        executedCall.outcome(AuditOutcome.EXECUTED, "written");

        ServerLogAudit failedCall = audit("webservice", OptionalInt.empty(),
            OptionalInt.empty(), contains.isPresent());
        failedCall.pending();
        failedCall.outcome(AuditOutcome.FAILED, "TLS_ERROR: handshake failed");

        assertThat(port.records).hasSize(5);
        assertThat(port.records).allSatisfy(record -> {
            assertThat(record.toString()).doesNotContain("s3cr3t-guess")
                .doesNotContain("Hunter2");
            assertThat(record.inputs()).containsEntry("containsGiven", "true")
                .doesNotContainKey("contains");
        });
    }

    private ServerLogAudit audit(String log, OptionalInt tailLines, OptionalInt maxChars,
        boolean containsGiven) {
        return new ServerLogAudit(dependencies(), "prod/node1", log, "/logs", tailLines,
            maxChars, containsGiven, Optional.of("claude-code/2.0"));
    }

    private ServerLogAudit.Dependencies dependencies() {
        List<UUID> ids = new ArrayList<>(List.of(ID));
        return new ServerLogAudit.Dependencies(port, new MutableClock(NOW), "default",
            () -> ids.remove(0));
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return Map.entry(key, value);
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
