package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 004, SC-002, FR-015, FR-016, research D-9, D-10, D-25 M2, M6): once something
 * was sent, a failure is a result — the state is re-exported, the backup is re-imported with the
 * target's current secrets if anything changed, verified, and the result names what failed, at
 * which step, the rollback state and the created artifacts that cannot be removed.
 */
@Timeout(60)
class ImportRollbackTest {

    @TempDir
    Path temp;

    private ImportHarness harness;
    private String before;

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        before = harness.inubit.workflowXml();
    }

    private WriteOutcome run() {
        ImportService.Response response = harness.service().importArtifacts(
            harness.group("Risky change"));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.commit()).isEmpty();
        assertThat(harness.backups.find(outcome.backupRef().orElseThrow())).isPresent();
        assertThat(harness.exports.log().get(0)).startsWith("local changes");
        return outcome;
    }

    /** The {@code Workflow} element of {@code Workflow-0001} in a workflow file. */
    private static String workflow0001(String xml) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "(?s)<WorkflowName>Workflow-0001</WorkflowName>.*?</Workflow>").matcher(xml);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    /** Workflow-0001 on the fake server is back at its original layout. */
    private void restored() {
        assertThat(workflow0001(harness.inubit.workflowXml())).contains("xPos=\"120\"")
            .doesNotContain("xPos=\"140\"").doesNotContain("xPos=\"999\"");
        assertThat(workflow0001(before)).contains("xPos=\"120\"");
    }

    @Test
    void aRefusedImportThatChangedNothingNeedsNoRollback() {
        harness.exportGroup().importRefused().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("import");
            assertThat(failure.message()).contains("Import failed.");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.NOT_NEEDED);
        assertThat(harness.inubit.workflowXml()).isEqualTo(before);
    }

    @Test
    void aPartlyAppliedImportIsRolledBackFromTheBackup() {
        harness.exportGroup();
        harness.cli.expect("import ").then(spec -> {
            harness.inubit.importArchive(ImportHarness.read(
                de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher.importFile(spec)));
        }).replying("import_nok");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.IMPORT_FAILED);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        restored();
        String rollback = new String(ArtifactFixtures.entries(harness.inubit.imported.get(1))
            .get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(rollback).contains("xPos=\"120\"").contains("Rollback of");
    }

    @Test
    void aProtocolThatNamesOtherArtifactsIsAFailureAndRolledBack() {
        harness.inubit.extraProtocolRow = "Module-0777";
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("protocol");
            assertThat(failure.message()).contains("Module-0777");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        restored();
    }

    @Test
    void aDifferingReExportIsAVerifyMismatchWithADifferenceFile() {
        harness.inubit.tamperNextImport = new String[] {"xPos=\"140\"", "xPos=\"999\""};
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(outcome.reports()).anyMatch(r -> r.startsWith(".reports/verify-"));
        assertThat(harness.root.resolve(outcome.reports().get(0))).isRegularFile();
        restored();
    }

    @Test
    void aCheckinCommentWithAnotherReasonIsAVerifyMismatch() {
        // review m2: the person-written segment must be exactly the reason
        harness.inubit.rewriteNextComments = comment -> comment.replace("###Risky change###",
            "###Risky change and more###");
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
    }

    @Test
    void aModuleWhoseCheckinCommentLostTheReasonIsAVerifyMismatch() throws IOException {
        // review m2: module index entries carry the reason as well
        String directory = harness.moduleDirectory("Module-0003");
        String pluginType = directory.split("/")[3];
        harness.write(directory + "/module.xml", harness.read(directory + "/module.xml")
            .replace("</Properties>", "<Property name=\"x.added\">1</Property></Properties>"));
        harness.inubit.rewriteNextComments = comment -> comment.replace("###Module fix###",
            "###");
        harness.exportModule(pluginType, "Module-0003")
            .importApplied("--importModule --importUser 'jdoe' --returnProtocol")
            .exportModule(pluginType, "Module-0003")
            .importApplied("--importModule --importUser 'jdoe' --returnProtocol")
            .exportModule(pluginType, "Module-0003");

        ImportService.Response response = harness.service().importArtifacts(
            new ImportService.ImportRequest("dev/node1", java.util.Optional.of("jdoe"),
                java.util.Optional.empty(), java.util.List.of(new de.dadecker.inubit.mcp.domain
                    .model.ImportScope.Module("Module-0003", java.util.Optional.empty())),
                "Module fix", java.util.Optional.empty(), java.util.Optional.empty()));

        harness.cli.verifyComplete();
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code())
            .isEqualTo(ErrorCode.VERIFY_MISMATCH);
    }

    @Test
    void aFailingReExportIsAFailureToo() {
        harness.exportGroup().importApplied().exportFails().exportGroup().importApplied()
            .exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
            assertThat(failure.step()).isEqualTo("verify");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
    }

    @Test
    void createdArtifactsAreNotRemovedAndListed() {
        harness.write(harness.workflow("Workflow-0100"), harness.read(harness.workflow(
            "Workflow-0001")).replace("Workflow-0001", "Workflow-0100"));
        harness.inubit.tamperNextImport = new String[] {"xPos=\"140\"", "xPos=\"999\""};
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.created()).containsExactly("Workflow-0100");
        assertThat(outcome.createdNotRemoved()).containsExactly("Workflow-0100");
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
        assertThat(harness.inubit.workflowXml()).contains("Workflow-0100");
    }

    @Test
    void aTimeoutIsDecidedByTheReExport() {
        harness.exportGroup();
        harness.cli.expect("import ").then(spec -> harness.inubit.importArchive(
            ImportHarness.read(de.dadecker.inubit.mcp.adapter.cli.ScriptedProcessLauncher
                .importFile(spec)))).replying("import_timeout").hanging();
        harness.exportGroup();

        ImportService.Response response = harness.service().importArtifacts(
            harness.group("Slow server"));

        harness.cli.verifyComplete();
        WriteOutcome outcome = ((ImportService.Response.Completed) response).outcome();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("timed out"));
        assertThat(outcome.commit()).isPresent();
    }

    @Test
    void aFailedRollbackKeepsAndNamesTheBackup() {
        harness.inubit.tamperNextImport = new String[] {"xPos=\"140\"", "xPos=\"999\""};
        harness.exportGroup().importApplied().exportGroup().importRefused().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.FAILED);
        assertThat(outcome.failure().orElseThrow().message())
            .contains(outcome.backupRef().orElseThrow());
    }

    @Test
    void anUnexpectedFailureAfterTheImportIsAFailedResultWithRollbackState() {
        // review m6: once anything was sent the call returns a result, never a tool error
        harness.archives = port -> new de.dadecker.inubit.mcp.domain.port.ImportArchivePort() {
            @Override
            public Archive assemble(Build build) {
                return port.assemble(build);
            }

            @Override
            public boolean equivalent(String path, byte[] expected, byte[] actual) {
                // 0.4.2: the change set and the conflict check compare too; fail after sending
                if (harness.inubit.imported.isEmpty()) {
                    return port.equivalent(path, expected, actual);
                }
                throw new IllegalStateException("boom");
            }

            @Override
            public java.util.Optional<String> checkinComment(byte[] file) {
                return port.checkinComment(file);
            }

            @Override
            public java.util.Optional<Boolean> active(byte[] file) {
                return port.active(file);
            }

            @Override
            public byte[] withActive(byte[] file, boolean active) {
                return port.withActive(file, active);
            }
        };
        harness.exportGroup().importApplied().exportGroup().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(ErrorCode.IMPORT_FAILED);
            assertThat(failure.step()).isEqualTo("verify");
            assertThat(failure.message()).contains("unexpected", "IllegalStateException");
        });
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.FAILED);
        assertThat(outcome.backupRef()).contains(outcome.auditId().toString());
        assertThat(harness.audit("import_artifacts")).extracting(r -> r.outcome())
            .containsExactly(de.dadecker.inubit.mcp.domain.model.AuditOutcome.PENDING,
                de.dadecker.inubit.mcp.domain.model.AuditOutcome.FAILED);
    }
}
