package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.XmlNormalizer;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.WriteOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * INUBIT keeps the variables of a workflow in hash order (observed on 8.1): a variable added at
 * the end of {@code Variables} comes back behind another one. The verify of the import accepts
 * that order, and only that: another content of the variable is still a mismatch that is rolled
 * back.
 */
class ImportVariableOrderTest {

    private static final String EMPTY = "<Variables"
        + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" requireVariableFiltering=\"true\"/>";
    private static final String EXISTING = "<Variable name=\"var.existing\" type=\"xs:string\"/>";
    private static final String ADDED = "<Variable isInputVariable=\"true\" name=\"var.added\""
        + " type=\"xs:boolean\"/>";

    @TempDir
    Path temp;

    private ImportHarness harness;
    private String workflow;

    @BeforeEach
    void setUp() throws IOException {
        harness = ImportHarness.grpA(temp);
        workflow = harness.workflow("Workflow-0001");
        // the existing variable first, the new one appended at the end
        harness.edit(workflow, EMPTY, EMPTY.replace("/>", ">") + EXISTING + ADDED
            + "</Variables>");
        assertThat(harness.read(workflow)).containsSubsequence("var.existing", "var.added");
    }

    private WriteOutcome run() {
        ImportService.Response response = harness.service().importArtifacts(
            harness.group("New input variable"));
        harness.cli.verifyComplete();
        assertThat(response).isInstanceOf(ImportService.Response.Completed.class);
        return ((ImportService.Response.Completed) response).outcome();
    }

    /** The two variables, one after the other, as the fake INUBIT stores them (normalized). */
    private String stored(String first, String second) {
        Matcher line = Pattern.compile("(?m)^( *)<Variables ").matcher(new String(
            XmlNormalizer.normalize(harness.inubit.workflowXml().getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8));
        assertThat(line.find()).isTrue();
        return first + "\n" + line.group(1) + "  " + second;
    }

    @Test
    void aNewVariableThatInubitStoresInAnotherPositionIsVerified() {
        harness.inubit.tamperNextImport = new String[] {stored(EXISTING, ADDED),
            stored(ADDED, EXISTING)};
        harness.exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.failure()).as(outcome.toString()).isEmpty();
        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.EXECUTED);
        assertThat(outcome.rollback()).isEmpty();
        assertThat(harness.inubit.workflowXml()).as("the server stored the other order")
            .containsSubsequence("var.added", "var.existing");
    }

    @Test
    void aVariableThatInubitStoresWithAnotherContentIsStillAMismatch() {
        harness.inubit.tamperNextImport = new String[] {stored(EXISTING, ADDED),
            stored(ADDED.replace("xs:boolean", "xs:string"), EXISTING)};
        harness.exportGroup().importApplied().exportGroup().importApplied().exportGroup();

        WriteOutcome outcome = run();

        assertThat(outcome.outcome()).isEqualTo(WriteOutcome.Outcome.FAILED);
        assertThat(outcome.failure().orElseThrow().code()).isEqualTo(ErrorCode.VERIFY_MISMATCH);
        assertThat(outcome.rollback()).contains(WriteOutcome.Rollback.SUCCEEDED);
    }
}
