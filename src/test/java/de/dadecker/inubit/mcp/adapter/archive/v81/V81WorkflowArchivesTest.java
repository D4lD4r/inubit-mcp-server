package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan;
import de.dadecker.inubit.mcp.domain.model.WorkflowArchivePlan.ModuleEntry;
import de.dadecker.inubit.mcp.domain.port.WorkflowArchivePort.SlicedExport;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.RawZip.RawEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Node;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;

/**
 * Feature 015 T007 (research R-3, R-5, R-6): the plans of the per-workflow Workbench archives,
 * and T008 (research R-7, FR-004, FR-006): the archives built from them, on the recorded exports
 * and the variants of {@link WorkflowArchiveFixtures}.
 */
class V81WorkflowArchivesTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String GRP_B = "GRP-02";
    private static final String MSG = "/Root/OWNERS/xsd/msg.xsd";
    private static final String CORE = "/Root/OWNERS/xsd/core.xsd";
    private static final String CLIENT_KEYS = "/Root/OWNERS/keys/fixture-client.p12";
    private static final String PROPERTIES = "archive.properties";
    private static final String REPOSITORY = "Repository.zip";
    private static final String WORKFLOWS = "workflow/workflow.xml";
    private static final String INDEX = "module/module.xml";

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

    // --- T008: building one archive -----------------------------------------------------------

    @Test
    void theArchiveHoldsOnlyItsEntriesInExportOrder() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        assertThat(names(RawZip.read(sliced.archive("Workflow-0006")))).containsExactly(
            PROPERTIES, REPOSITORY, WORKFLOWS, "module/module-0028.xml", INDEX,
            "module/module-0026.xml", "module/module-0015.xml", "module/module-0027.xml");
        assertThat(names(RawZip.read(sliced.archive("Workflow-0004")))).containsExactly(
            PROPERTIES, REPOSITORY, WORKFLOWS, INDEX, "module/module-0017.xml",
            "module/module-0023.xml", "module/module-0022.xml");
        assertThat(names(RawZip.read(archives.slice(WorkflowArchiveFixtures.grpA(), DEV,
            WorkflowArchiveFixtures.USER, "GRP-01").archive("Workflow-0001"))))
            .startsWith(PROPERTIES, REPOSITORY, WORKFLOWS)
            .allMatch(name -> !name.endsWith("/"));
    }

    @Test
    void theEntriesAreThoseOfThePlanAndTheCopiedOnesAreByteIdenticalWithTheirTimes() {
        for (Export export : recordedExports()) {
            RawZip original = RawZip.read(export.bytes());
            SlicedExport sliced = export.slice(archives);
            for (String workflow : sliced.workflows()) {
                WorkflowArchivePlan plan = sliced.plan(workflow);
                Set<String> moduleFiles = plan.modules().stream()
                    .map(m -> moduleFile(m.name())).collect(Collectors.toSet());
                List<String> expected = names(original).stream().filter(name ->
                    Set.of(PROPERTIES, REPOSITORY, WORKFLOWS, INDEX).contains(name)
                        || moduleFiles.contains(name)).toList();
                RawZip archive = RawZip.read(sliced.archive(workflow));

                assertThat(names(archive)).as(workflow).containsExactlyElementsOf(expected);
                for (RawEntry entry : archive.entries()) {
                    String name = entry.header().getName();
                    RawEntry source = original.entry(name).orElseThrow();
                    assertThat(entry.header().getTime()).as("%s %s time", workflow, name)
                        .isEqualTo(source.header().getTime());
                    assertThat(entry.header().getMethod()).as("%s %s method", workflow, name)
                        .isEqualTo(source.header().getMethod());
                    if (name.equals(PROPERTIES) || moduleFiles.contains(name)) {
                        assertThat(Arrays.equals(entry.bytes(), source.bytes()))
                            .as("%s %s bytes", workflow, name).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void theWorkflowDocumentHoldsTheGroupWithOnlyThatWorkflowUnchanged() {
        for (Export export : recordedExports()) {
            byte[] input = RawZip.read(export.bytes()).entry(WORKFLOWS).orElseThrow().bytes();
            Element root = XmlTree.parse(input).root();
            Element group = root.child("Workflows").orElseThrow().elements().stream()
                .filter(g -> g.child("WorkflowGroupName").orElseThrow().text()
                    .equals(export.diagramGroup())).findFirst().orElseThrow();
            SlicedExport sliced = export.slice(archives);
            for (String workflow : sliced.workflows()) {
                byte[] built = entry(sliced.archive(workflow), WORKFLOWS);
                Element builtRoot = XmlTree.parse(built).root();
                List<Element> groups = builtRoot.child("Workflows").orElseThrow().elements();

                assertThat(builtRoot.attributes()).isEqualTo(root.attributes());
                assertThat(groups).as(workflow).hasSize(1);
                assertThat(groups.get(0).attributes()).isEqualTo(group.attributes());
                assertThat(groups.get(0).child("WorkflowGroupName").orElseThrow().text())
                    .isEqualTo(export.diagramGroup());
                List<Element> workflows = groups.get(0).elements().stream()
                    .filter(e -> e.localName().equals("Workflow")).toList();
                assertThat(workflows).as(workflow).hasSize(1);
                assertThat(XmlEquality.equal(XmlNormalizer.normalize(workflows.get(0)),
                    XmlNormalizer.normalize(workflowElement(group, workflow))))
                    .as("%s unchanged", workflow).isTrue();
                assertThat(XmlEquality.equal(built, XmlNormalizer.normalize(keep(root,
                    e -> !e.localName().equals("WorkflowGroup") || e == group, e ->
                    !e.localName().equals("Workflow") || name(e, "WorkflowName")
                        .equals(workflow))))).as("%s document", workflow).isTrue();
            }
        }
    }

    @Test
    void theModuleIndexHoldsOnlyTheSelectedEntriesInOriginalOrder() {
        for (Export export : allExports()) {
            byte[] input = RawZip.read(export.bytes()).entry(INDEX).orElseThrow().bytes();
            Element root = XmlTree.parse(input).root();
            SlicedExport sliced = export.slice(archives);
            for (String workflow : sliced.workflows()) {
                Set<String> selected = sliced.plan(workflow).modules().stream()
                    .map(ModuleEntry::name).collect(Collectors.toSet());
                Element expected = keep(root, group -> group.elements().stream()
                    .anyMatch(m -> m.localName().equals("Module")
                        && selected.contains(name(m, "ModuleName"))),
                    m -> !m.localName().equals("Module")
                        || selected.contains(name(m, "ModuleName")));
                byte[] built = entry(sliced.archive(workflow), INDEX);

                assertThat(XmlEquality.equal(built, XmlNormalizer.normalize(expected)))
                    .as("%s %s", export.name(), workflow).isTrue();
                assertThat(XmlTree.parse(built).root().child("Modules").orElseThrow()
                    .elements()).as("no empty module group").allMatch(g -> g.elements().stream()
                        .anyMatch(m -> m.localName().equals("Module")));
            }
        }
    }

    @Test
    void theRepositoryHoldsTheSelectedFilesUnchangedInOriginalOrder() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());
        RawZip original = repository(WorkflowArchiveFixtures.grpB());

        RawZip w6 = repository(sliced.archive("Workflow-0006"));
        assertThat(names(w6)).containsExactly("Root/OWNERS/xsd/msg.xsd.xml",
            "Root/OWNERS/xsd/msg.xsd.dat", "Root/OWNERS/xsd/core.xsd.xml",
            "Root/OWNERS/xsd/core.xsd.dat");
        assertUnchanged(w6, original);
        assertThat(w6.comment()).isEqualTo(original.comment()).isEmpty();

        // the key store of Module-0018: key material included unchanged
        RawZip w3 = repository(sliced.archive("Workflow-0003"));
        assertThat(names(w3)).containsExactly("Root/OWNERS/keys/fixture-client.p12.xml",
            "Root/OWNERS/keys/fixture-client.p12.dat");
        assertUnchanged(w3, original);

        assertThat(repository(sliced.archive("Workflow-0004")).entries()).isEmpty();
        assertThat(repository(sliced.archive("Workflow-0005")).entries()).isEmpty();
    }

    @Test
    void theArchiveCommentOfTheRepositoryIsKept() {
        SlicedExport sliced = archives.slice(WorkflowArchiveFixtures.grpA(), DEV,
            WorkflowArchiveFixtures.USER, "GRP-01");

        for (String workflow : sliced.workflows()) {
            RawZip repository = repository(sliced.archive(workflow));
            assertThat(repository.entries()).as(workflow).isEmpty();
            assertThat(repository.comment()).as(workflow).contains("5.3");
        }
    }

    @Test
    void onlyDirectoryEntriesTheOriginalHasAndThatAreAncestorsOfAKeptFileAreKept() {
        byte[] export = WorkflowArchiveFixtures.repositoryDirectories();
        RawZip original = repository(export);
        assertThat(names(original)).contains(WorkflowArchiveFixtures.XSD_DIRECTORY,
            WorkflowArchiveFixtures.UNUSED_DIRECTORY);
        SlicedExport sliced = grpB(export);

        RawZip w6 = repository(sliced.archive("Workflow-0006"));
        assertThat(names(w6)).containsExactly(WorkflowArchiveFixtures.XSD_DIRECTORY,
            "Root/OWNERS/xsd/msg.xsd.xml", "Root/OWNERS/xsd/msg.xsd.dat",
            "Root/OWNERS/xsd/core.xsd.xml", "Root/OWNERS/xsd/core.xsd.dat");
        assertUnchanged(w6, original);
        ZipHeader directory = ZipHeader.of(w6.entry(WorkflowArchiveFixtures.XSD_DIRECTORY)
            .orElseThrow());
        assertThat(directory).isEqualTo(ZipHeader.of(original.entry(
            WorkflowArchiveFixtures.XSD_DIRECTORY).orElseThrow()));
        assertThat(directory.directory()).isTrue();

        // neither directory is an ancestor of the key store; none is synthesized
        assertThat(names(repository(sliced.archive("Workflow-0003")))).containsExactly(
            "Root/OWNERS/keys/fixture-client.p12.xml", "Root/OWNERS/keys/fixture-client.p12.dat");
        assertThat(repository(sliced.archive("Workflow-0004")).entries()).isEmpty();
    }

    @Test
    void theSyntheticSecretsArePresentUnchanged() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());
        Map<String, byte[]> built = new LinkedHashMap<>();
        sliced.workflows().forEach(w -> built.put(w, sliced.archive(w)));
        List<SyntheticSecret> secrets = ArtifactFixtures.syntheticSecrets().stream()
            .filter(s -> s.fixture().equals("grp-b.zip")).toList();
        assertThat(secrets).isNotEmpty();
        for (SyntheticSecret secret : secrets) {
            String[] location = secret.location().split(" ");
            int found = 0;
            for (Map.Entry<String, byte[]> archive : built.entrySet()) {
                Optional<RawEntry> entry;
                if (location[0].equals("workflow")) {
                    entry = archive.getKey().equals(location[1])
                        ? RawZip.read(archive.getValue()).entry(WORKFLOWS) : Optional.empty();
                } else {
                    entry = RawZip.read(archive.getValue()).entry(location[0]);
                }
                if (entry.isPresent()) {
                    assertThat(new String(entry.get().bytes(), StandardCharsets.UTF_8))
                        .as("%s value at %s in %s", secret.kind(), secret.location(),
                            archive.getKey())
                        .contains(secret.value());
                    found++;
                }
            }
            assertThat(found).as("archives with the %s value at %s", secret.kind(),
                secret.location()).isPositive();
        }
    }

    @Test
    void theArchiveReaderAcceptsEveryBuiltArchive() {
        ArchiveReader reader = new ArchiveReader();
        for (Export export : allExports()) {
            SlicedExport sliced = export.slice(archives);
            for (String workflow : sliced.workflows()) {
                ExportArchive read = reader.read(sliced.archive(workflow));
                assertReadBack(read, export, sliced.plan(workflow));
            }
        }
        // the reader of this adapter skips directory entries of the nested Repository.zip
        Export directories = new Export("repositoryDirectories",
            WorkflowArchiveFixtures.repositoryDirectories(), WorkflowArchiveFixtures.OWNER,
            GRP_B);
        SlicedExport sliced = directories.slice(archives);
        for (String workflow : sliced.workflows()) {
            assertReadBack(ArchiveReader.allowingRepositoryDirectories()
                .read(sliced.archive(workflow)), directories, sliced.plan(workflow));
        }
    }

    @Test
    void anExportWithoutRepositoryGivesArchivesWithoutRepository() {
        byte[] export = WorkflowArchiveFixtures.withoutRepository();
        assertThat(RawZip.read(export).entry(REPOSITORY)).isEmpty();
        Export withoutRepository = new Export("withoutRepository", export,
            WorkflowArchiveFixtures.OWNER, GRP_B);
        SlicedExport sliced = withoutRepository.slice(archives);
        assertThat(sliced.workflows()).isNotEmpty();

        for (String workflow : sliced.workflows()) {
            byte[] archive = sliced.archive(workflow);
            assertThat(names(RawZip.read(archive))).as(workflow).doesNotContain(REPOSITORY)
                .startsWith(PROPERTIES, WORKFLOWS);
            assertThat(sliced.plan(workflow).repositoryPaths()).as(workflow).isEmpty();
            ExportArchive read = new ArchiveReader().read(archive);
            assertThat(read.repository()).as(workflow).isEmpty();
            assertReadBack(read, withoutRepository, sliced.plan(workflow));
        }
    }

    @Test
    void anArchiveOfAnotherWorkflowIsRefused() {
        SlicedExport sliced = grpB(WorkflowArchiveFixtures.grpB());

        assertThatThrownBy(() -> sliced.archive("Workflow-0001"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertReadBack(ExportArchive read, Export export,
        WorkflowArchivePlan plan) {
        assertThat(read.workflowGroups()).as("%s %s", export.name(), plan.workflow())
            .singleElement().satisfies(group -> {
                assertThat(group.name()).isEqualTo(export.diagramGroup());
                assertThat(group.workflows()).extracting(ExportArchive.WorkflowXml::name)
                    .containsExactly(plan.workflow());
            });
        assertThat(read.moduleIndex().stream().map(ModuleIndexEntry::name).distinct())
            .containsExactlyInAnyOrderElementsOf(plan.modules().stream()
                .map(ModuleEntry::name).toList());
        assertThat(read.repository().keySet().stream().map(path -> "/" + path))
            .containsExactlyInAnyOrderElementsOf(plan.repositoryPaths());
    }

    /** Every kept entry has the name, header, bytes and relative order of the original. */
    private static void assertUnchanged(RawZip kept, RawZip original) {
        List<String> order = names(original);
        assertThat(names(kept)).isSortedAccordingTo((a, b) ->
            Integer.compare(order.indexOf(a), order.indexOf(b)));
        for (RawEntry entry : kept.entries()) {
            RawEntry source = original.entry(entry.header().getName()).orElseThrow();
            assertThat(ZipHeader.of(entry)).isEqualTo(ZipHeader.of(source));
            assertThat(Arrays.equals(entry.bytes(), source.bytes()))
                .as("%s bytes", entry.header().getName()).isTrue();
        }
    }

    /** The parts of a local header that writing keeps. */
    private record ZipHeader(String name, long time, int method, long size, long crc,
        boolean directory) {

        static ZipHeader of(RawEntry entry) {
            ZipEntry header = entry.header();
            return new ZipHeader(header.getName(), header.getTime(), header.getMethod(),
                header.getSize(), header.getCrc(), header.isDirectory());
        }
    }

    /** An export and the diagram group to slice. */
    private record Export(String name, byte[] bytes, String owner, String diagramGroup) {

        SlicedExport slice(V81WorkflowArchives archives) {
            return archives.slice(bytes, DEV, owner, diagramGroup);
        }
    }

    private static List<Export> recordedExports() {
        return List.of(
            new Export("grp-a.zip", WorkflowArchiveFixtures.grpA(), WorkflowArchiveFixtures.USER,
                "GRP-01"),
            new Export("grp-b.zip", WorkflowArchiveFixtures.grpB(),
                WorkflowArchiveFixtures.OWNER, GRP_B));
    }

    /** The recorded exports and the variants without directory entries. */
    private static List<Export> allExports() {
        List<Export> exports = new ArrayList<>(recordedExports());
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("boundModule", WorkflowArchiveFixtures.boundModule());
        variants.put("nestedNode", WorkflowArchiveFixtures.nestedNode());
        variants.put("missingModule", WorkflowArchiveFixtures.missingModule());
        variants.put("workflowConnector", WorkflowArchiveFixtures.workflowConnector());
        variants.put("tenWorkflows", WorkflowArchiveFixtures.tenWorkflows());
        variants.put("nonTechnicalWorkflow", WorkflowArchiveFixtures.nonTechnicalWorkflow());
        variants.forEach((name, bytes) -> exports.add(new Export(name, bytes,
            WorkflowArchiveFixtures.OWNER, GRP_B)));
        return exports;
    }

    /**
     * {@code root} with the second-level children ({@code WorkflowGroup}, {@code ModuleGroup})
     * that {@code keepGroup} accepts and, inside them, the children {@code keepChild} accepts.
     */
    private static Element keep(Element root, Predicate<Element> keepGroup,
        Predicate<Element> keepChild) {
        return root.withChildren(root.children().stream().map(container ->
            container instanceof Element c ? (Node) c.withChildren(c.children().stream()
                .filter(g -> !(g instanceof Element e) || keepGroup.test(e))
                .map(g -> g instanceof Element e ? (Node) e.withChildren(e.children().stream()
                    .filter(n -> !(n instanceof Element x) || keepChild.test(x)).toList()) : g)
                .toList()) : container).toList());
    }

    private static Element workflowElement(Element group, String workflow) {
        return group.elements().stream().filter(e -> e.localName().equals("Workflow")
            && name(e, "WorkflowName").equals(workflow)).findFirst().orElseThrow();
    }

    private static String name(Element element, String child) {
        return element.child(child).map(Element::text).orElse("");
    }

    private static String moduleFile(String module) {
        return "module/" + module.toLowerCase(Locale.ROOT) + ".xml";
    }

    private static List<String> names(RawZip zip) {
        return zip.entries().stream().map(e -> e.header().getName()).toList();
    }

    private static byte[] entry(byte[] zip, String name) {
        return RawZip.read(zip).entry(name).orElseThrow().bytes();
    }

    private static RawZip repository(byte[] zip) {
        return RawZip.read(entry(zip, REPOSITORY));
    }

    private static void assertUnexpected(Runnable slice) {
        assertThatThrownBy(slice::run).isInstanceOfSatisfying(ToolErrorException.class, e ->
            assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE));
    }
}
