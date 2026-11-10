package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerFixtures;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.PartnerQuery;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import de.dadecker.inubit.mcp.infra.AuditLog;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Feature 018 T017 (FR-020, research D-8): the audit records of one {@code find_partners} call. */
class PartnerAuditTest {

    private static final NodeId QA1 = NodeId.parse("qa/node1");
    private static final NodeId QA2 = NodeId.parse("qa/node2");
    private static final UUID CALL = UUID.fromString("00000000-0000-4000-8000-000000000018");
    private static final UUID FIRST = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final ToolError INVALID = ToolError.of(ErrorCode.INVALID_INPUT,
        "search is not 1 to 200 characters without control characters", "cause", "next");

    private final RecordingAudit port = new RecordingAudit();

    @TempDir
    Path directory;

    @Test
    void refusedAppendsOneRefusedRecordNamingTheTargetAsRequested() {
        PartnerAudit audit = audit("qa");

        ToolErrorException refused = audit.refused(INVALID);

        assertThat(refused.error()).isEqualTo(INVALID);
        AuditRecord record = port.records.getFirst();
        assertThat(port.records).hasSize(1);
        assertThat(record.auditId()).isEqualTo(FIRST);
        assertThat(record.callId()).contains(CALL);
        assertThat(audit.callId()).isEqualTo(CALL);
        assertThat(record.timestamp()).isEqualTo(NOW);
        assertThat(record.profile()).isEqualTo("default");
        assertThat(record.node()).isEqualTo("qa");
        assertThat(record.group()).contains("qa");
        assertThat(record.capability()).isEqualTo("find_partners");
        assertThat(record.step()).isEqualTo(AuditRecord.Step.EXECUTE);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.reason()).contains("INVALID_INPUT: " + INVALID.message());
        assertThat(record.account()).isEmpty();
        assertThat(record.mcpClient()).contains("claude-code/2.0");
        assertThat(record.details()).isEmpty();
    }

    @Test
    void refusalOfAMalformedTargetHasNoGroup() {
        PartnerAudit audit = new PartnerAudit(dependencies(), "Unknown Target!", Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        audit.refused(INVALID);

        AuditRecord record = port.records.getFirst();
        assertThat(record.node()).isEqualTo("Unknown Target!");
        assertThat(record.group()).isEmpty();
        assertThat(record.mcpClient()).isEmpty();
        assertThat(record.inputs()).containsExactly(Map.entry("target", "Unknown Target!"));
    }

    @Test
    void perServerRecordsShareTheCallIdAndCarryTheCounts() {
        PartnerAudit audit = audit("qa");

        audit.perServer(QA1, AuditOutcome.EXECUTED, Optional.empty(), true, 2, 1, 3);
        audit.perServer(QA2, AuditOutcome.FAILED, Optional.of(ErrorCode.UNREACHABLE), false, 0,
            0, 0);

        assertThat(port.records).extracting(AuditRecord::auditId).containsExactly(FIRST, SECOND);
        assertThat(port.records).extracting(AuditRecord::callId)
            .containsOnly(Optional.of(CALL));
        assertThat(port.records).extracting(AuditRecord::node)
            .containsExactly("qa/node1", "qa/node2");
        assertThat(port.records).extracting(AuditRecord::group).containsOnly(Optional.of("qa"));
        assertThat(port.records).extracting(AuditRecord::outcome)
            .containsExactly(AuditOutcome.EXECUTED, AuditOutcome.FAILED);
        assertThat(port.records).extracting(AuditRecord::capability)
            .containsOnly("find_partners");
        assertThat(port.records).extracting(AuditRecord::step)
            .containsOnly(AuditRecord.Step.EXECUTE);
        assertThat(port.records).extracting(AuditRecord::mcpClient)
            .containsOnly(Optional.of("claude-code/2.0"));
        assertThat(port.records).extracting(AuditRecord::reason)
            .containsExactly(Optional.empty(), Optional.of("UNREACHABLE"));
        assertThat(port.records.get(0).details()).containsExactly(
            Map.entry("fromCache", true),
            Map.entry("systemDiagramsRead", 2),
            Map.entry("partnersReturned", 1),
            Map.entry("valuesMasked", 3));
        assertThat(port.records.get(1).details()).containsEntry("fromCache", false);
    }

    @ParameterizedTest
    @EnumSource(value = AuditOutcome.class, names = {"EXECUTED", "REFUSED", "FAILED"},
        mode = EnumSource.Mode.EXCLUDE)
    void perServerAcceptsOnlyExecutedRefusedOrFailed(AuditOutcome outcome) {
        PartnerAudit audit = audit("qa");

        assertThatThrownBy(() -> audit.perServer(QA1, outcome, Optional.empty(), false, 0, 0,
            0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.records).isEmpty();
    }

    @Test
    void anExecutedRecordHasNoReasonAndARefusedOrFailedOneNeedsIt() {
        PartnerAudit audit = audit("qa");

        assertThatThrownBy(() -> audit.perServer(QA1, AuditOutcome.EXECUTED,
            Optional.of(ErrorCode.UNREACHABLE), false, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.perServer(QA1, AuditOutcome.REFUSED, Optional.empty(),
            false, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> audit.perServer(QA1, AuditOutcome.FAILED, Optional.empty(),
            false, 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.records).isEmpty();
    }

    @Test
    void inputsAreTheRequestInAFixedOrderAndNeverResults() {
        PartnerAudit audit = new PartnerAudit(dependencies(), "qa", Optional.of("prod"),
            Optional.of("OWNERS"), Optional.of("acme"), Optional.of("SystemDiagram-01"),
            Optional.of("AS2"), Optional.of("Workflow-0001"), Optional.empty());

        audit.perServer(QA1, AuditOutcome.EXECUTED, Optional.empty(), false, 2, 1, 3);

        assertThat(port.records.getFirst().inputs()).containsExactly(
            Map.entry("target", "qa"),
            Map.entry("compareWith", "prod"),
            Map.entry("owner", "OWNERS"),
            Map.entry("search", "acme"),
            Map.entry("systemDiagram", "SystemDiagram-01"),
            Map.entry("protocol", "AS2"),
            Map.entry("workflow", "Workflow-0001"));
    }

    @Test
    void ofTakesTheInputsAndTheMcpClientFromTheQuery() {
        PartnerQuery query = new PartnerQuery("qa/node1", Optional.of("prod"),
            Optional.of("OWNERS"), Optional.of("acme"), Optional.empty(), Optional.of("AS2"),
            Optional.empty(), 0, PartnerQuery.DEFAULT_LIMIT, true, Optional.of("cursor/1.4"));

        PartnerAudit.of(dependencies(), query).perServer(QA1, AuditOutcome.EXECUTED,
            Optional.empty(), false, 1, 1, 0);

        AuditRecord record = port.records.getFirst();
        assertThat(record.mcpClient()).contains("cursor/1.4");
        assertThat(record.inputs()).containsExactly(
            Map.entry("target", "qa/node1"),
            Map.entry("compareWith", "prod"),
            Map.entry("owner", "OWNERS"),
            Map.entry("search", "acme"),
            Map.entry("protocol", "AS2"));
    }

    @Test
    void everyInputIsCutTo500Chars() {
        String longValue = "x".repeat(600);
        PartnerAudit audit = new PartnerAudit(dependencies(), longValue, Optional.of(longValue),
            Optional.of(longValue), Optional.of(longValue), Optional.of(longValue),
            Optional.of(longValue), Optional.of(longValue), Optional.of("claude-code/2.0"));

        audit.refused(INVALID);

        AuditRecord record = port.records.getFirst();
        assertThat(record.inputs()).hasSize(7)
            .allSatisfy((key, value) -> assertThat(value).hasSize(500));
        assertThat(record.node()).hasSize(500);
    }

    @Test
    void cuttingTo500CharsNeverSplitsASurrogatePair() {
        String emoji = "😀";
        String straddling = "x".repeat(499) + emoji + "y";
        PartnerAudit audit = new PartnerAudit(dependencies(), straddling, Optional.empty(),
            Optional.empty(), Optional.of(straddling), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        audit.refused(INVALID);

        AuditRecord record = port.records.getFirst();
        assertThat(record.node()).isEqualTo("x".repeat(499));
        assertThat(record.inputs()).containsEntry("target", "x".repeat(499))
            .containsEntry("search", "x".repeat(499));
    }

    @Test
    void aMissingTargetIsRefusedWithAnEmptyNodeAndNoTargetInput() {
        PartnerAudit audit = new PartnerAudit(dependencies(), null, Optional.empty(),
            Optional.empty(), Optional.of("acme"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolErrorException refused = audit.refused(INVALID);

        assertThat(refused.error()).isEqualTo(INVALID);
        AuditRecord record = port.records.getFirst();
        assertThat(port.records).hasSize(1);
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.node()).isEmpty();
        assertThat(record.group()).isEmpty();
        assertThat(record.inputs()).containsExactly(Map.entry("search", "acme"));
    }

    @Test
    void aBlankTargetIsRefusedWithTheTargetAsGiven() {
        PartnerAudit audit = new PartnerAudit(dependencies(), "   ", Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolErrorException refused = audit.refused(INVALID);

        assertThat(refused.error()).isEqualTo(INVALID);
        AuditRecord record = port.records.getFirst();
        assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED);
        assertThat(record.node()).isEqualTo("   ");
        assertThat(record.group()).isEmpty();
        assertThat(record.inputs()).containsExactly(Map.entry("target", "   "));
    }

    @Test
    void refusedReturnsInternalNamingTheCallIdWhenTheRecordCannotBeWritten() {
        PartnerAudit audit = audit("qa");
        port.failing = true;

        ToolError error = audit.refused(INVALID).error();

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains(CALL.toString()).contains("INVALID_INPUT")
            .contains(INVALID.message());
        assertThat(error.nextStep()).contains("auditDirectory");
        assertThat(port.records).isEmpty();
    }

    @Test
    void perServerThrowsInternalNamingTheCallIdWhenTheRecordCannotBeWritten() {
        PartnerAudit audit = audit("qa");
        audit.perServer(QA1, AuditOutcome.EXECUTED, Optional.empty(), false, 2, 1, 3);
        port.failing = true;

        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> audit.perServer(QA2, AuditOutcome.EXECUTED, Optional.empty(), true, 1, 1, 0));

        assertThat(thrown).isNotNull();
        assertThat(thrown.error().code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(thrown.error().message()).contains(CALL.toString())
            .contains("no partner data");
        assertThat(thrown.error().nextStep()).contains("auditDirectory");
        assertThat(port.records).extracting(AuditRecord::node).containsExactly("qa/node1");
    }

    @Test
    void noWrittenAuditLineShowsASecretOrAnythingDerivedFromIt() throws IOException {
        AuditLog log = new AuditLog(directory, new SecretScrubber(), PrivateFiles.forHost());
        PartnerAudit.Dependencies dependencies = new PartnerAudit.Dependencies(log,
            new MutableClock(NOW), "default", UUID::randomUUID);
        PartnerQuery query = new PartnerQuery("qa", Optional.empty(), Optional.of("OWNERS"),
            Optional.of(PartnerFixtures.SHARED_ID), Optional.of("SystemDiagram-01"),
            Optional.of("AS2"), Optional.of("Workflow-0001"), 0, PartnerQuery.DEFAULT_LIMIT,
            false, Optional.of("claude-code/2.0"));

        PartnerAudit.of(dependencies, query).refused(INVALID);
        PartnerAudit audit = PartnerAudit.of(dependencies, query);
        audit.perServer(QA1, AuditOutcome.EXECUTED, Optional.empty(), false, 3, 1, 6);
        audit.perServer(QA2, AuditOutcome.REFUSED, Optional.of(ErrorCode.NOT_CONFIGURED), true,
            0, 0, 0);

        List<String> lines = Files.readAllLines(directory.resolve("audit-2026-11.jsonl"),
            StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3).allSatisfy(line -> assertThat(line)
            .contains("\"search\":\"" + PartnerFixtures.SHARED_ID + "\"")
            .contains("\"workflow\":\"Workflow-0001\"")
            .contains("\"mcpClient\":\"claude-code/2.0\""));
        List<String> forbidden = PartnerFixtures.SECRETS.stream()
            .flatMap(PartnerAuditTest::derived).toList();
        assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain(forbidden));
    }

    /** {@code secret}, its 12-character prefix, its unkeyed SHA-256 and its Base64 form. */
    private static Stream<String> derived(String secret) {
        try {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            return Stream.of(secret, secret.substring(0, 12), sha256,
                Base64.getEncoder().encodeToString(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private PartnerAudit audit(String target) {
        return new PartnerAudit(dependencies(), target, Optional.empty(), Optional.of("OWNERS"),
            Optional.of("acme"), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of("claude-code/2.0"));
    }

    private PartnerAudit.Dependencies dependencies() {
        List<UUID> ids = new ArrayList<>(List.of(CALL, FIRST, SECOND));
        return new PartnerAudit.Dependencies(port, new MutableClock(NOW), "default",
            () -> ids.removeFirst());
    }

    /** Records every appended audit record; fails every append while {@code failing}. */
    private static final class RecordingAudit implements AuditPort {

        final List<AuditRecord> records = new ArrayList<>();
        boolean failing;

        @Override
        public void append(AuditRecord record) {
            if (failing) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
            records.add(record);
        }
    }
}
