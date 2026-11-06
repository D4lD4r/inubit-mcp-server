package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Feature 015 T007 (research R-3, R-5, R-6): the plans of the per-workflow Workbench archives,
 * on the recorded exports and the variants of {@link WorkflowArchiveFixtures}.
 */
class V81WorkflowArchivesTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String GRP_B = "GRP-02";
    private static final String MSG = "/Root/OWNERS/xsd/msg.xsd";
    private static final String CORE = "/Root/OWNERS/xsd/core.xsd";
    private static final String CLIENT_KEYS = "/Root/OWNERS/keys/fixture-client.p12";

    private static final ModuleEntry M10 = new ModuleEntry("Module-0010", "XSLT Converter");
    private static final ModuleEntry M11 = new ModuleEntry("Module-0011", "Splitter");
    private static final ModuleEntry M12 = new ModuleEntry("Module-0012", "JSON Adapter");
    private static final ModuleEntry M13 = new ModuleEntry("Module-0013", "REST Connector");
    private static final ModuleEntry M14 = new ModuleEntry("Module-0014", "Empty");
    private static final ModuleEntry M15 = new ModuleEntry("Module-0015", "Assign");
    private static final ModuleEntry M16 = new ModuleEntry("Module-0016", "Workflow Starter");
    private static final ModuleEntry M17 = new ModuleEntry("Module-0017", "Empty");
    private static final ModuleEntry M18 = new ModuleEntry("Module-0018", "JSON Validator");
    private static final ModuleEntry M19 = new ModuleEntry("Module-0019", "XSLT Converter");
    private static final ModuleEntry M20 = new ModuleEntry("Module-0020", "XSLT Converter");
    private static final ModuleEntry M21 = new ModuleEntry("Module-0021", "Joiner");
    private static final ModuleEntry M22 = new ModuleEntry("Module-0022", "FTP Connector");
    private static final ModuleEntry M23 = new ModuleEntry("Module-0023", "XSLT Converter");
    private static final ModuleEntry M24 = new ModuleEntry("Module-0024", "AS2 Connector");
    private static final ModuleEntry M25 = new ModuleEntry("Module-0025", "Empty");
    private static final ModuleEntry M26 = new ModuleEntry("Module-0026",
        "Web Services Connector");
    private static final ModuleEntry M27 = new ModuleEntry("Module-0027",
        "Web Services Connector");
    private static final ModuleEntry M28 = new ModuleEntry("Module-0028",
        "Web Services Connector");

    private final V81WorkflowArchives archives = new V81WorkflowArchives();

    private SlicedExport grpB(byte[] export) {
        return archives.slice(export, DEV, WorkflowArchiveFixtures.OWNER, GRP_B);
    }

    @Test
    void theWorkflowsAreTheTechnicalWorkflowsOfTheDiagramGroupInExportOrder() {
        assertThat(archives.slice(WorkflowArchiveFixtures.grpA(), DEV,
            WorkflowArchiveFixtures.USER, "GRP-01").workflows())
            .containsExactly("Workflow-0001", "Workflow-0002");
        assertThat(grpB(WorkflowArchiveFixtures.grpB()).workflows())
            .containsExactly("Workflow-0003", "Workflow-0004", "Workflow-0005", "Workflow-0006");
    }

    @Test
    void aWorkflowOfAnotherTypeIsLeftOutAndOneWithoutTypeIsTechnical() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.nonTechnicalWorkflow());

        assertThat(sliced.workflows())
            .containsExactly("Workflow-0003", "Workflow-0004", "Workflow-0006");
        assertThatThrownBy(() -> sliced.plan("Workflow-0005"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDiagramGroupTheExportDoesNotHoldHasNoWorkflows() {
        assertThat(archives.slice(WorkflowArchiveFixtures.grpB(), DEV,
            WorkflowArchiveFixtures.OWNER, "GRP-01").workflows()).isEmpty();
    }

    @Test
    void theTenWorkflowVariantHoldsTenWorkflowsOfTheDiagramGroup() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.tenWorkflows());

        assertThat(sliced.workflows()).containsExactly("Workflow-0003", "Workflow-0004",
            "Workflow-0005", "Workflow-0006", "Workflow-0007", "Workflow-0008", "Workflow-0009",
            "Workflow-0010", "Workflow-0011", "Workflow-0012");
        assertThat(sliced.plan("Workflow-0010").modules())
            .isEqualTo(sliced.plan("Workflow-0006").modules());
        assertThat(sliced.plan("Workflow-0010").repositoryPaths()).containsExactly(CORE, MSG);
    }

    @Test
    void thePlanListsEveryNodeModuleOnceInExportOrder() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        WorkflowArchivePlan w3 = sliced.plan("Workflow-0003");
        WorkflowArchivePlan w6 = sliced.plan("Workflow-0006");

        assertThat(w3.workflow()).isEqualTo("Workflow-0003");
        // Module-0013 is a node and also bound by WorkflowName: listed once; the Comment node
        // "Note 01" is no module
        assertThat(w3.modules()).containsExactly(M10, M11, M12, M13, M14, M15, M16, M17, M18,
            M19, M20, M21);
        assertThat(w3.missingModules()).isEmpty();
        assertThat(w6.modules()).containsExactly(M26, M27, M15, M28);
        assertThat(sliced.plan("Workflow-0004").modules()).containsExactly(M22, M17, M23);
    }

    @Test
    void aModuleOfTwoWorkflowsIsInBothPlans() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        assertThat(sliced.plan("Workflow-0003").modules()).contains(M15, M17);
        assertThat(sliced.plan("Workflow-0006").modules()).contains(M15);
        assertThat(sliced.plan("Workflow-0004").modules()).contains(M17);
    }

    @Test
    void aNodeNestedBelowAPartnerManagementNodeIsSelected() {
        assertThat(grpB(WorkflowArchiveFixtures.grpB()).plan("Workflow-0005").modules())
            .containsExactly(M24, M25);

        WorkflowArchivePlan plan = grpB(WorkflowArchiveFixtures.nestedNode())
            .plan("Workflow-0005");

        // the PartnerManagement node itself is no module
        assertThat(plan.modules()).containsExactly(M24, M25, M20);
        assertThat(plan.missingModules()).isEmpty();
    }

    @Test
    void aModuleBoundByWorkflowNameThatIsNoNodeIsSelected() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.boundModule());

        assertThat(sliced.plan("Workflow-0004").modules()).containsExactly(M22, M17, M23, M16);
        assertThat(sliced.plan("Workflow-0003").modules()).contains(M16);
        assertThat(sliced.plan("Workflow-0005").modules()).doesNotContain(M16);
    }

    @Test
    void aNodeModuleTheExportDoesNotHoldIsMissingAndNotListed() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.missingModule());

        WorkflowArchivePlan plan = sliced.plan("Workflow-0004");

        assertThat(plan.missingModules()).containsExactly("Module-0023");
        assertThat(plan.modules()).containsExactly(M22, M17);
        assertThat(sliced.plan("Workflow-0003").missingModules()).isEmpty();
        assertThat(sliced.plan("Workflow-0006").missingModules()).isEmpty();
    }

    @Test
    void onlyAWorkflowConnectorNodeMarksThePlan() {
        SlicedExport recorded = grpB(WorkflowArchiveFixtures.grpB());
        recorded.workflows().forEach(w ->
            assertThat(recorded.plan(w).workflowConnector()).as(w).isFalse());

        SlicedExport sliced = grpB(WorkflowArchiveFixtures.workflowConnector());
        ModuleEntry connector = new ModuleEntry("Module-0016",
            V81WorkflowArchives.WORKFLOW_CONNECTOR);

        assertThat(sliced.plan("Workflow-0003").workflowConnector()).isTrue();
        assertThat(sliced.plan("Workflow-0003").modules()).contains(connector);
        // bound to Workflow-0004, but no node of it
        assertThat(sliced.plan("Workflow-0004").modules()).contains(connector);
        assertThat(sliced.plan("Workflow-0004").workflowConnector()).isFalse();
        assertThat(sliced.plan("Workflow-0006").workflowConnector()).isFalse();
    }

    @Test
    void theWorkflowConnectorTypeIsKeptInOneConstant() {
        assertThat(V81WorkflowArchives.WORKFLOW_CONNECTOR).isEqualTo("Workflow Connector");
    }

    @Test
    void theRepositoryPathsAreTheOwnerRootClosure() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        // msg.xsd is referenced by Module-0027, core.xsd by msg.xsd and the WSDLs
        assertThat(sliced.plan("Workflow-0006").repositoryPaths()).containsExactly(CORE, MSG);
        // the key-store location of Module-0018, a value the workspace replaces by a placeholder
        assertThat(sliced.plan("Workflow-0003").repositoryPaths()).containsExactly(CLIENT_KEYS);
        assertThat(sliced.plan("Workflow-0004").repositoryPaths()).isEmpty();
        assertThat(sliced.plan("Workflow-0005").repositoryPaths()).isEmpty();
        assertThat(archives.slice(WorkflowArchiveFixtures.grpA(), DEV,
            WorkflowArchiveFixtures.USER, "GRP-01").plan("Workflow-0001").repositoryPaths())
            .isEmpty();
    }

    @Test
    void aFileReachedOnlyThroughAnotherRepositoryFileIsIncluded() {
        // Module-0027 no longer names core.xsd; msg.xsd includes it
        byte[] export = WorkflowArchiveFixtures.edit(WorkflowArchiveFixtures.grpB(),
            "module/module-0027.xml", xml -> xml.replace("inubitrepository:" + CORE,
                "urn:none:core.xsd"));
        Map<String, byte[]> repository = new LinkedHashMap<>(ArtifactFixtures.entries(
            ArtifactFixtures.entries(export).get("Repository.zip")));
        repository.put("Root/OWNERS/xsd/msg.xsd.dat", ("<?xml version=\"1.0\"?>\n<xs:schema"
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include"
            + " schemaLocation=\"core.xsd\"/></xs:schema>\n").getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries(export));
        entries.put("Repository.zip", ArtifactFixtures.zip(repository));

        assertThat(grpB(ArtifactFixtures.zip(entries)).plan("Workflow-0006").repositoryPaths())
            .containsExactly(CORE, MSG);
    }

    @Test
    void anUnreadableExportIsAnUnexpectedResponse() {
        assertUnexpected(() -> grpB("not a zip".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        entries.remove("module/module.xml");
        assertUnexpected(() -> grpB(ArtifactFixtures.zip(entries)));
    }

    @Test
    void thePlanOfAnotherWorkflowIsRefused() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        assertThatThrownBy(() -> sliced.plan("Workflow-0001"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertUnexpected(Runnable slice) {
        assertThatThrownBy(slice::run).isInstanceOfSatisfying(ToolErrorException.class, e ->
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
