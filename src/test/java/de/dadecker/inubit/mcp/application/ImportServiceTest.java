package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.domain.model.ImportScope;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 004, US1, research D-4 – D-13, D-25): the import sequence with the real 8.1
 * adapters against a fake INUBIT — fresh export and conflict check, backup, archive with only
 * the change set, StartCLI import with protocol matching, verification by re-export, write-back
 * of the change-set files and the commit with the server-state trailer.
 */
@Timeout(120)
class ImportServiceTest {

    @TempDir
    Path temp;

    private static WriteOutcome completed(ImportService.Response response) {
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    @Test
    void aChangedWorkflowIsImportedVerifiedAndCommitted() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Move the converter")));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001");
        assertThat(outcome.created()).isEmpty();
        assertThat(outcome.rollback()).isEmpty();
        assertThat(outcome.backupRef()).contains(outcome.auditId().toString());
        assertThat(outcome.commit()).isPresent();
        Map<String, byte[]> sent = ArtifactFixtures.entries(harness.inubit.imported.get(0));
        assertThat(sent.keySet()).containsExactly("archive.properties", "workflow/workflow.xml",
            "module/module.xml");
        String workflow = new String(sent.get("workflow/workflow.xml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("<WorkflowName>Workflow-0001</WorkflowName>")
            .doesNotContain("Workflow-0002")
            .contains("DefaultCommitCommentImport###Move the converter###@@@Deploying User:"
                + " jdoe@@@Server: inubit-dev-1.example.test@@@");
        assertThat(harness.inubit.workflowXml()).contains("xPos=\"140\"");
        assertThat(harness.exports.log().get(0)).isEqualTo("import dev/node1: diagram group"
            + " GRP-01 (1 artifacts) [" + outcome.auditId() + "]");
        assertThat(harness.exports.git("log", "-1",
            "--format=%(trailers:key=Server-State,valueonly)").strip()).isEqualTo("dev");
        assertThat(harness.exports.history.status()).isEmpty();
        assertThat(harness.read(harness.workflow("Workflow-0001"))).contains("xPos=\"140\"")
            .contains("DefaultCommitCommentImport###Move the converter###");
    }

    @Test
    void aChangedModuleOfAChangedWorkflowGoesWithItAndOtherChangesStay() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        String module = harness.moduleDirectory("Module-0003") + "/module.xml";
        harness.write(module, harness.read(module).replace("</Properties>",
            "<Property name=\"x.added\">1</Property></Properties>"));
        harness.write(harness.workflow("Workflow-0001").replace("GRP-01", "GRP-09"),
            "<Workflow/>\n");
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Add a property")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Workflow-0001", "Module-0003");
        assertThat(outcome.notImported()).containsExactly(
            harness.workflow("Workflow-0001").replace("GRP-01", "GRP-09"));
        Map<String, byte[]> sent = ArtifactFixtures.entries(harness.inubit.imported.get(0));
        assertThat(sent.keySet()).containsExactly("archive.properties", "workflow/workflow.xml",
            "module/module.xml", "module/module-0003.xml");
        assertThat(harness.read(harness.workflow("Workflow-0001").replace("GRP-01", "GRP-09")))
            .isEqualTo("<Workflow/>\n");
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void anUnchangedWorkspaceSendsNothing() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        int entries = harness.exports.log().size();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Nothing")));

        assertThat(harness.cli.launches()).isEmpty();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).isEmpty();
        assertThat(outcome.modified()).isEmpty();
        assertThat(outcome.commit()).isEmpty();
        assertThat(outcome.backupRef()).isEmpty();
        assertThat(outcome.warnings()).anyMatch(w -> w.contains("nothing was sent"));
        assertThat(harness.exports.log()).hasSize(entries);
    }

    @Test
    void aModuleImportSendsAModuleOnlyArchive() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String directory = harness.moduleDirectory("Module-0003");
        String pluginType = directory.split("/")[3];
        harness.write(directory + "/module.xml", harness.read(directory + "/module.xml")
            .replace("</Properties>", "<Property name=\"x.added\">1</Property></Properties>"));
        harness.exportModule(pluginType, "Module-0003")
            .importApplied("--importModule --importUser 'jdoe' --returnProtocol")
            .exportModule(pluginType, "Module-0003");

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            new ImportService.ImportRequest("dev/node1", Optional.of("jdoe"), Optional.empty(),
                List.of(new ImportScope.Module("Module-0003", Optional.empty())), "Module fix",
                Optional.empty(), Optional.empty())));

        harness.cli.verifyComplete();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).containsExactly("Module-0003");
        assertThat(ArtifactFixtures.entries(harness.inubit.imported.get(0)).keySet())
            .containsExactly("archive.properties", "module/module.xml",
                "module/module-0003.xml", "Repository.zip");
        assertThat(harness.exports.log().get(0)).startsWith("import dev/node1: modules"
            + " Module-0003 (1 artifacts)");
    }

    @Test
    void newArtifactsAreCreatedInactiveWithTheirOwnIndexEntry() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String source = harness.moduleDirectory("Module-0003");
        String target = source.replace("Module-0003", "Module-0100");
        harness.write(target + "/module.xml", harness.read(source + "/module.xml"));
        harness.write(target + "/index.xml", harness.read(source + "/index.xml")
            .replace("Module-0003", "Module-0100"));
        harness.write(harness.workflow("Workflow-0100"), harness.read(harness.workflow(
            "Workflow-0001")).replace("Workflow-0001", "Workflow-0100")
            .replace("Module-0003", "Module-0100"));
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("New workflow")));

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.created()).containsExactly("Workflow-0100", "Module-0100");
        assertThat(harness.inubit.workflowXml()).contains("Workflow-0100");
        assertThat(harness.exports.history.status()).isEmpty();
    }

    @Test
    void theBackupHoldsTheRawExportAndTheManifestTheOutcome() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        byte[] before = harness.inubit.exportWorkflowGroup();
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Backup check")));

        String ref = outcome.backupRef().orElseThrow();
        BackupStore.Manifest manifest = harness.backups.find(ref).orElseThrow();
        assertThat(harness.backups.exports(ref)).singleElement().isEqualTo(before);
        assertThat(manifest.node()).isEqualTo(ImportHarness.DEV);
        assertThat(manifest.owner()).isEqualTo("jdoe");
        assertThat(manifest.scope()).isEqualTo("diagram group GRP-01");
        assertThat(manifest.changeSet()).containsExactly("Workflow-0001");
        assertThat(manifest.outcome()).isEqualTo("EXECUTED");
        assertThat(manifest.intendedState()).containsKey("Workflow-0001");
    }

    @Test
    void oldBackupsAreRemovedAtTheStartAndTheRemovalIsAudited() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String old = UUID.randomUUID().toString();
        harness.backups.write(new BackupStore.Manifest(old, ImportHarness.DEV, "jdoe",
            "diagram group GRP-01", List.of(), List.of(), Map.of(), "EXECUTED",
            harness.clock.instant().minus(Duration.ofDays(40)), List.of()), List.of(new byte[1]));
        harness.backups.write(new BackupStore.Manifest(UUID.randomUUID().toString(),
            ImportHarness.DEV, "jdoe", "diagram group GRP-01", List.of(), List.of(), Map.of(),
            "EXECUTED", harness.clock.instant().minus(Duration.ofDays(1)), List.of()),
            List.of(new byte[1]));

        harness.service().importArtifacts(harness.group("Nothing"));

        assertThat(harness.backups.find(old)).isEmpty();
        assertThat(harness.audit("backup_retention")).singleElement().satisfies(record ->
            assertThat(record.inputs()).containsEntry("backupRef", old));
    }

    @Test
    void anImportOf20WorkflowsAnd100ModulesTakesLessThanTwoMinutes() throws IOException {
        // SC-007, with StartCLI answering at once
        ImportHarness harness = new ImportHarness(temp, ExportHarness.large(20, 100), "jdoe",
            "GRP-01");
        for (int i = 0; i < 20; i++) {
            harness.edit(harness.workflow("Workflow-%04d".formatted(2000 + i)), "xPos=\"",
                "xPos=\"1");
        }
        for (int j = 0; j < 80; j++) {
            String module = harness.moduleDirectory("Module-%04d".formatted(1000 + j))
                + "/module.xml";
            harness.write(module, harness.read(module).replace("</Properties>",
                "<Property name=\"x.added\">1</Property></Properties>"));
        }
        harness.exportGroup().importApplied().exportGroup();
        long start = System.nanoTime();

        WriteOutcome outcome = completed(harness.service().importArtifacts(
            harness.group("Bulk move")));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(
            Duration.ofMinutes(2));
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.modified()).hasSize(100);
    }
}
