package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.RecordingAuditPort;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 022 (T052, data-model.md → AuditRecord, FR-023): the audit records of a change, their
 * cleaned inputs, and the fail-closed {@code PENDING} record.
 */
class ChangeAuditTest {

    private static final String TOOL = "operate_server";
    private static final String NODE = "dev/node1";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000052");

    private final RecordingAuditPort port = new RecordingAuditPort();
    private final ChangeAudit audit = new ChangeAudit(port, Clock.fixed(NOW, ZoneOffset.UTC),
        "acme", () -> ID);

    private ChangeAudit.Call call(Map<String, Object> input) {
        ChangeAudit.Call call = audit.call(TOOL, NODE, "setMaintenance", input,
            Optional.of("client/1.0"));
        call.account(Optional.of("jdoe"));
        return call;
    }

    private static Map<String, Object> input(Object... keysAndValues) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            input.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return input;
    }

    // --- the records of one call ------------------------------------------------------------

    @Test
    void aPreviewIsOneChallengeIssuedRecordWithTheIssuedCode() {
        ChangeAudit.Call call = call(input("enabled", true, "reason", "night window"));

        call.challengeIssued("ISSUED-CODE", "Preview of setMaintenance: off -> on");

        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.auditId()).isEqualTo(ID).isEqualTo(call.auditId());
            assertThat(record.timestamp()).isEqualTo(NOW);
            assertThat(record.profile()).isEqualTo("acme");
            assertThat(record.node()).isEqualTo(NODE);
            assertThat(record.group()).contains("dev");
            assertThat(record.capability()).isEqualTo(TOOL);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.PREVIEW);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.CHALLENGE_ISSUED);
            assertThat(record.inputs()).containsExactly(entry("action", "setMaintenance"),
                entry("enabled", "true"), entry("reason", "night window"),
                entry(AuditRecord.ISSUED_CONFIRMATION_CODE, "ISSUED-CODE"));
            assertThat(record.account()).contains("jdoe");
            assertThat(record.reason()).contains("Preview of setMaintenance: off -> on");
            assertThat(record.mcpClient()).contains("client/1.0");
            assertThat(record.details()).isEmpty();
        });
    }

    @Test
    void anExecutionIsPendingThenExecutedWithTheSameAuditId() {
        ChangeAudit.Call call = call(input(AuditRecord.CONFIRMATION_CODE, "CODE"));

        call.pending("About to switch maintenance on");
        assertThat(call.executed("Maintenance is on")).isEmpty();

        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
        assertThat(port.records).extracting(AuditRecord::step)
            .containsOnly(AuditRecord.Step.EXECUTE);
        assertThat(port.records.get(0).inputs())
            .containsEntry(AuditRecord.CONFIRMATION_CODE, "CODE");
        assertThat(port.records.get(1).reason()).contains("Maintenance is on");
    }

    @Test
    void aFailedExecutionIsPendingThenFailedWithTheSameAuditId() {
        ChangeAudit.Call call = call(input());

        call.pending("About to switch maintenance on");
        assertThat(call.failed("CLI_FAILED: maintenance on exited with 1")).isEmpty();

        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(port.records).extracting(AuditRecord::auditId).containsOnly(ID);
    }

    @Test
    void aRefusalIsOneRefusedRecordInTheGivenStep() {
        ToolError error = ToolError.of(ErrorCode.WRITE_DISABLED, "Write access is off",
            "write.enabled is false", "Set write.enabled");

        ToolErrorException refused = call(input()).refused(AuditRecord.Step.EXECUTE, error);

        assertThat(refused.error()).isSameAs(error);
        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.reason()).contains("WRITE_DISABLED: Write access is off");
        });
    }

    /** Spec edge case "nothing to do": the call is audited although nothing is sent. */
    @Test
    void nothingToDoIsOneExecutedRecordWithTheNothingToDoDetail() {
        call(input()).nothingToDo("No process is in state ERROR");

        assertThat(port.records).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
            assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
            assertThat(record.details()).containsExactly(entry("nothing-to-do", true));
            assertThat(record.reason()).contains("No process is in state ERROR");
        });
    }

    @Test
    void aCallWritesOneFirstRecordAndClosesOnlyAPendingOne() {
        ChangeAudit.Call previewed = call(input());
        previewed.challengeIssued("CODE", "Preview");

        assertThatThrownBy(() -> previewed.pending("again"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> call(input()).executed("no pending record"))
            .isInstanceOf(IllegalStateException.class);
        ChangeAudit.Call executed = call(input());
        executed.pending("About to");
        executed.executed("done");
        assertThatThrownBy(() -> executed.failed("twice"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executed.nothingToDo("after pending"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aServerIdThatIsNoServerIsRecordedAsRequestedWithoutGroup() {
        audit.call(TOOL, "no server id", "setMaintenance", input(), Optional.empty())
            .refused(AuditRecord.Step.PREVIEW, ToolError.of(ErrorCode.INVALID_INPUT, "Invalid",
                "cause", "next"));

        assertThat(port.records.get(0).node()).isEqualTo("no server id");
        assertThat(port.records.get(0).group()).isEmpty();
        assertThat(port.records.get(0).account()).isEmpty();
        assertThat(port.records.get(0).mcpClient()).isEmpty();
    }

    // --- fail closed ------------------------------------------------------------------------

    @Test
    void aPendingRecordThatCannotBeWrittenIsInternalAndNothingIsSent() {
        port.failing.add(AuditOutcome.PENDING);
        ChangeAudit.Call call = call(input());

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> call.pending("About to switch maintenance on")).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("nothing was sent to INUBIT");
        assertThat(port.records).isEmpty();
        assertThatThrownBy(() -> call.executed("must not be sent"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPreviewRecordThatCannotBeWrittenIsInternal() {
        port.failing.add(AuditOutcome.CHALLENGE_ISSUED);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> call(input()).challengeIssued("CODE", "Preview")).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("the preview was not issued");
    }

    @Test
    void aRefusalWhoseRecordCannotBeWrittenIsInternalNamingTheRefusal() {
        port.failing.add(AuditOutcome.REFUSED);

        ToolError error = call(input()).refused(AuditRecord.Step.PREVIEW,
            ToolError.of(ErrorCode.WRITE_DISABLED, "Write access is off", "cause", "next"))
            .error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("WRITE_DISABLED: Write access is off")
            .contains("nothing was changed");
    }

    @Test
    void aNothingToDoRecordThatCannotBeWrittenIsInternal() {
        port.failing.add(AuditOutcome.EXECUTED);

        ToolError error = catchThrowableOfType(ToolErrorException.class,
            () -> call(input()).nothingToDo("empty set")).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("nothing was changed");
    }

    @Test
    void aFinalRecordThatCannotBeWrittenGivesAWarningNamingThePendingRecord() {
        port.failing.add(AuditOutcome.EXECUTED);
        ChangeAudit.Call call = call(input());
        call.pending("About to");

        assertThat(call.executed("done")).hasValueSatisfying(warning ->
            assertThat(warning).contains(ID.toString()));
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.PENDING);
    }

    // --- cleaned inputs ---------------------------------------------------------------------

    @Test
    void inputsAndTheReasonAreCutTo500Characters() {
        String longValue = "w".repeat(501);
        ChangeAudit.Call call = call(input("workflow", longValue, "groups", List.of(longValue)));

        call.refused(AuditRecord.Step.PREVIEW,
            ToolError.of(ErrorCode.NOT_FOUND, "x".repeat(600), "cause", "next"));

        AuditRecord record = port.records.get(0);
        assertThat(record.inputs().get("workflow")).isEqualTo("w".repeat(500));
        assertThat(record.inputs().get("groups")).hasSize(500);
        assertThat(record.reason()).hasValueSatisfying(reason -> assertThat(reason).hasSize(500));
    }

    @Test
    void theMessageTextIsCutTo200Characters() {
        call(input("recipients", List.of("jdoe"), "subject", "Restart", "text",
            "t".repeat(1_000))).challengeIssued("CODE", "Preview");

        assertThat(port.records.get(0).inputs().get("text")).isEqualTo("t".repeat(200));
        assertThat(port.records.get(0).inputs()).containsEntry("subject", "Restart");
    }

    @Test
    void aSecretEntryIsRecordedByItsName() {
        call(input("login", "jdoe", "secretEntry", "NODE1_JDOE_PASSWORD"))
            .challengeIssued("CODE", "Preview");

        assertThat(port.records.get(0).inputs())
            .containsEntry("secretEntry", "NODE1_JDOE_PASSWORD");
    }

    @Test
    void anUploadIsRecordedByPathSizeAndSha256(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("lib.jar"), "library content");
        ChangeAudit.Call call = call(input("file", "lib.jar", "libraryType", "java"));

        call.upload("file", new ChangeAudit.Upload(file, Files.size(file), sha256(file)));
        call.challengeIssued("CODE", "Preview");

        assertThat(port.records.get(0).inputs()).containsExactly(
            entry("action", "setMaintenance"),
            entry("file", file + " (15 bytes, sha256:" + sha256(file) + ")"),
            entry("libraryType", "java"),
            entry(AuditRecord.ISSUED_CONFIRMATION_CODE, "CODE"));
    }

    /**
     * Leak check (SC-005): a secret entry's value, an upload's content and the secrets of an
     * INUBIT answer appear in no record and no error.
     */
    @Test
    void noRecordHoldsASecretValueUploadContentOrASecretOfTheAnswer(@TempDir Path folder)
        throws IOException {
        Path file = Files.writeString(folder.resolve("keys.p12"),
            TestSecrets.CONFIGURED_PASSWORD + " " + TestSecrets.CREDENTIAL_PAYLOAD_VALUE);
        ChangeAudit.Call call = call(input("file", "keys.p12", "secretEntry", "NODE1_KEY"));
        call.upload("file", new ChangeAudit.Upload(file, Files.size(file), sha256(file)));
        call.pending("About to deploy keys");
        call.executed("INUBIT answered: " + TestSecrets.FREE_TEXT_SECRETS);

        ChangeAudit.Call refused = call(input("secretEntry", "NODE1_KEY"));
        port.failing.add(AuditOutcome.REFUSED);
        ToolError error = refused.refused(AuditRecord.Step.EXECUTE, ToolError.of(
            ErrorCode.UNEXPECTED_RESPONSE, "INUBIT answered: " + TestSecrets.FREE_TEXT_SECRETS, "cause",
            "next")).error();

        assertThat(port.records).hasSize(2);
        port.records.forEach(record -> TestSecrets.assertNoLeak(record.toString()));
        TestSecrets.assertNoLeak(error.toString());
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readString(file).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
