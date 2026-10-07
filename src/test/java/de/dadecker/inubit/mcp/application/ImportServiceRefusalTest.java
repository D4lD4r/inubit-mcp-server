package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T016 (feature 004, SC-001, FR-007 – FR-014, FR-027, research D-25): every refusal before the
 * import is a tool error and nothing is sent — the scripted StartCLI fails the test on any
 * unscripted call, so only the scripted exports can have run.
 */
@Timeout(60)
class ImportServiceRefusalTest {

    @TempDir
    Path temp;

    private ToolError refusal(ImportHarness harness, ImportService.ImportRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> harness.service().importArtifacts(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        harness.cli.verifyComplete();
        assertThat(harness.inubit.imported).as("nothing was imported").isEmpty();
        assertThat(harness.audit("import_artifacts")).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    private ImportHarness edited(ImportHarness harness) {
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        return harness;
    }

    @Test
    void aCheckErrorRefusesBeforeAnythingIsExported() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "moduleOutId=\"2\"",
            "moduleOutId=\"77\"");

        ToolError error = refusal(harness, harness.group("Broken edge"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("EDGE_TARGET_MISSING", ".reports/");
        assertThat(harness.cli.launches()).isEmpty();
        String report = error.message().replaceAll("(?s).*(\\.reports/[^ ;)]+).*", "$1");
        assertThat(harness.root.resolve(report)).isRegularFile();
    }

    @Test
    void aChangeOnTheServerIsAConflictAfterTheExportOnly() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.inubit.changeWorkflows(xml -> xml.replace("xPos=\"520\" yPos=\"130\"",
            "xPos=\"530\" yPos=\"130\""));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Conflicting"));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains(".reports/conflict-");
    }

    @Test
    void aWorkflowInEditModeIsAConflict() throws IOException {
        ImportHarness harness = new ImportHarness(temp, de.dadecker.inubit.mcp.adapter.archive
            .v81.ArtifactFixtures.bytes("grp-a.zip"), "jdoe", "GRP-01");
        harness.edit(harness.workflow("Workflow-0002"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Edit mode"));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("edit mode", "jdoe");
    }

    @Test
    void aPlaceholderWithoutValueOnTheTargetIsUnresolved() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        String module = harness.moduleDirectory("Module-0003") + "/module.xml";
        harness.edit(module, "</Properties>",
            "<Property name=\"Password\" type=\"Password\">${secret:Password}</Property>"
                + "</Properties>");
        harness.edit(harness.workflow("Workflow-0001"), "xPos=\"120\"", "xPos=\"140\"");
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Secret"));

        assertThat(error.code()).isEqualTo(ErrorCode.SECRET_UNRESOLVED);
        assertThat(error.message()).contains("Module-0003", "Password");
    }

    @Test
    void aUserGroupOwnerIsRefusedAsNotYetVerified() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.owners.put("jdoe", OwnerKind.USER_GROUP);

        ToolError error = refusal(harness, harness.group("Group"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("not yet verified");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void anOwnerOfUnknownKindIsRefused() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        harness.users.clear();

        ToolError error = refusal(harness, harness.group("Unknown"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.nextStep()).contains("owners.jdoe");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aReasonThatWouldBreakTheCheckinCommentIsInvalid() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));
        for (String reason : List.of("a###b", "a@@@b", "", "line\nbreak", "x".repeat(501))) {
            ToolError error = refusal(harness, harness.group(reason));
            assertThat(error.code()).as(reason).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aNodeThatIsNoDevelopmentStageIsRefused() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));

        ToolError error = refusal(harness, new ImportService.ImportRequest("test/node1",
            Optional.of("jdoe"), Optional.of("GRP-01"), List.of(), "Wrong node",
            Optional.empty(), Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.NOT_DEVELOPMENT);
    }

    @Test
    void aReferencedModuleThatExistsNeitherInTheArchiveNorOnTheTargetIsRefused()
        throws IOException {
        // the spike's broken-group case (D-25 H6)
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.edit(harness.workflow("Workflow-0001"), "<ModuleName>Module-0004</ModuleName>",
            "<ModuleName>Module-0999</ModuleName>");
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Missing module"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("Module-0999");
    }

    @Test
    void aCopiedWorkflowOfAnotherOwnerIsRefused() throws IOException {
        // review I1: the owner of the request, never the one a copied file names
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.write(harness.workflow("Workflow-0100"), harness.read(harness.workflow(
            "Workflow-0001")).replace("Workflow-0001", "Workflow-0100")
            .replace("<UserOrUserGroupName>jdoe<", "<UserOrUserGroupName>OWNERS<"));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Copied"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("Workflow-0100", "OWNERS");
    }

    @Test
    void aNewWorkflowThatIsActiveInItsFileIsRefusedBeforeAnythingIsSent() throws IOException {
        // review I2: INUBIT would create it inactive and the verification would fail
        ImportHarness harness = ImportHarness.grpA(temp);
        harness.write(harness.workflow("Workflow-0100"), harness.read(harness.workflow(
            "Workflow-0001")).replace("Workflow-0001", "Workflow-0100")
            .replace("<IsActive>false</IsActive>", "<IsActive>true</IsActive>"));
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Active copy"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.message()).contains("Workflow-0100", "IsActive");
        assertThat(error.nextStep()).contains("set_active");
    }

    @Test
    void aNewModuleThatExistsOnTheTargetIsAConflict() throws IOException {
        // review I3: existence of a new module comes from the owner's module list
        ImportHarness harness = ImportHarness.grpA(temp);
        String source = harness.moduleDirectory("Module-0003");
        String pluginType = source.split("/")[3];
        String target = source.replace("Module-0003", "Module-0100");
        harness.write(target + "/module.xml", harness.read(source + "/module.xml"));
        harness.write(target + "/index.xml", harness.read(source + "/index.xml")
            .replace("Module-0003", "Module-0100"));
        harness.targetModules.add("Module-0100");

        ToolError error = refusal(harness, new ImportService.ImportRequest("dev/node1",
            Optional.of("jdoe"), Optional.empty(), List.of(new de.dadecker.inubit.mcp.domain
                .model.ImportScope.Module("Module-0100", Optional.of(pluginType))), "Exists",
            Optional.empty(), Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(error.message()).contains("Module-0100", "exists");
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aNewModuleNamedLikeOneOfTheOwnersDiagramsIsRefused() throws IOException {
        // review m5: a new module is checked against the owner's diagram names as well
        ImportHarness harness = ImportHarness.grpA(temp);
        String source = harness.moduleDirectory("Module-0004");
        String target = source.replace("Module-0004", "Module-0100");
        harness.write(target + "/module.xml", harness.read(source + "/module.xml"));
        harness.write(target + "/index.xml", harness.read(source + "/index.xml")
            .replace("Module-0004", "Module-0100"));
        harness.edit(harness.workflow("Workflow-0001"), "<ModuleName>Module-0004</ModuleName>",
            "<ModuleName>Module-0100</ModuleName>");
        harness.targetDiagrams.add("Module-0100");
        harness.exportGroup();

        ToolError error = refusal(harness, harness.group("Name taken"));

        assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(error.message()).contains("Module-0100");
    }

    @Test
    void aBusyWorkspaceIsRefusedAtOnce() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));

        try (WorkspaceLock lock = WorkspaceLock.acquire(harness.root)) {
            ToolError error = refusal(harness, harness.group("Busy"));
            assertThat(error.code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(error.message()).contains("busy");
        }
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void aDeletedWorkflowIsRefused() throws IOException {
        ImportHarness harness = ImportHarness.grpA(temp);
        Files.delete(harness.root.resolve(harness.workflow("Workflow-0001")));

        ToolError error = refusal(harness, harness.group("Delete"));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(harness.cli.launches()).isEmpty();
    }

    @Test
    void anImportWithoutScopeOrWithBothIsInvalid() throws IOException {
        ImportHarness harness = edited(ImportHarness.grpA(temp));

        ToolError neither = refusal(harness, new ImportService.ImportRequest("dev/node1",
            Optional.empty(), Optional.empty(), List.of(), "x", Optional.empty(),
            Optional.empty()));

        assertThat(neither.code()).isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
