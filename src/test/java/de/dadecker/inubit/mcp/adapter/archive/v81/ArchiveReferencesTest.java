package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Document;
import de.dadecker.inubit.mcp.adapter.archive.v81.EmbeddedDocuments.Extracted;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.RepositoryClosure;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference;
import de.dadecker.inubit.mcp.domain.model.RepositoryReference.Kind;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Feature 015 T005 (research R-6): the reference collection shared by {@link WorkspaceWriter}
 * and the per-workflow archives, on the bytes each caller serializes.
 */
class ArchiveReferencesTest {

    private static final GroupId DEV = new GroupId("dev");
    private static final String OWNER = "OWNERS";
    private static final String MSG = "/Root/OWNERS/xsd/msg.xsd";
    private static final String CORE = "/Root/OWNERS/xsd/core.xsd";

    private static ExportArchive grpB() {
        return new ArchiveReader().read(ArtifactFixtures.bytes("grp-b.zip"));
    }

    private static WorkflowXml workflow(ExportArchive export, String name) {
        return export.workflowGroups().stream().flatMap(g -> g.workflows().stream())
            .filter(w -> w.name().equals(name)).findFirst().orElseThrow();
    }

    /** The technical modules of {@code workflow}, as {@code WorkspaceWriter} counts them. */
    private static Set<String> moduleNames(WorkflowXml workflow) {
        return workflow.element().elements().stream()
            .filter(node -> node.localName().equals("WorkflowModule")
                && node.attribute("moduleType").filter("technical"::equals).isPresent())
            .flatMap(node -> node.child("ModuleName").map(Element::text).stream())
            .collect(Collectors.toSet());
    }

    /** The references of one workflow, its modules' index entries and module files. */
    private static List<RepositoryReference> references(ExportArchive export, String name) {
        WorkflowXml workflow = workflow(export, name);
        List<RepositoryReference> references = new ArrayList<>(ArchiveReferences.workflow(
            WorkspacePath.workflow(DEV, OWNER, workflow.diagramGroup(), workflow.name()),
            XmlNormalizer.normalize(workflow.element())));
        Set<String> modules = moduleNames(workflow);
        assertThat(modules).as(name).isNotEmpty();
        for (ModuleIndexEntry entry : export.moduleIndex()) {
            if (modules.contains(entry.name())) {
                references.addAll(ArchiveReferences.indexEntry(WorkspacePath.moduleIndex(DEV,
                    OWNER, entry.pluginType(), entry.name()),
                    XmlNormalizer.normalize(entry.element())));
            }
        }
        for (ModuleXml module : export.moduleFiles().values()) {
            if (modules.contains(module.name())) {
                references.addAll(ArchiveReferences.moduleFile(modulePath(module),
                    XmlNormalizer.normalize(module.element())));
            }
        }
        return references;
    }

    private static WorkspacePath modulePath(ModuleXml module) {
        return WorkspacePath.module(DEV, OWNER, module.pluginType(), module.name());
    }

    private static List<String> targets(List<RepositoryReference> references) {
        return references.stream().map(RepositoryReference::to).distinct().toList();
    }

    @Test
    void workflow0006AndItsModulesReferenceTheSchemaAndTheClosureHoldsBothSchemas() {
        ExportArchive export = grpB();

        List<RepositoryReference> references = references(export, "Workflow-0006");
        RepositoryClosure closure = ArchiveReferences.closure(OWNER, references,
            export.repository().values(), DEV);

        assertThat(targets(references)).contains(MSG);
        assertThat(closure.included()).contains(MSG, CORE);
    }

    @Test
    void theClosureAddsASchemaReachedOnlyThroughAnotherRepositoryFile() {
        Map<String, byte[]> entries = new LinkedHashMap<>(ArtifactFixtures.entries("grp-b.zip"));
        String module = new String(entries.get("module/module-0027.xml"), StandardCharsets.UTF_8);
        entries.put("module/module-0027.xml", module.replace("inubitrepository:" + CORE,
            "urn:none:core.xsd").getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> repository = new LinkedHashMap<>(
            ArtifactFixtures.entries(entries.get("Repository.zip")));
        repository.put("Root/OWNERS/xsd/msg.xsd.dat", ("<?xml version=\"1.0\"?>\n<xs:schema"
            + " xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include"
            + " schemaLocation=\"core.xsd\"/></xs:schema>\n").getBytes(StandardCharsets.UTF_8));
        entries.put("Repository.zip", ArtifactFixtures.zip(repository));
        ExportArchive export = new ArchiveReader().read(ArtifactFixtures.zip(entries));

        List<RepositoryReference> references = references(export, "Workflow-0006");
        RepositoryClosure closure = ArchiveReferences.closure(OWNER, references,
            export.repository().values(), DEV);

        assertThat(targets(references)).contains(MSG).doesNotContain(CORE);
        assertThat(closure.included()).contains(MSG, CORE);
        assertThat(closure.referencedBy().get(CORE))
            .containsExactly("dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd");
    }

    @Test
    void workflow0003AndItsModulesReachNeitherSchema() {
        ExportArchive export = grpB();

        List<RepositoryReference> references = references(export, "Workflow-0003");
        RepositoryClosure closure = ArchiveReferences.closure(OWNER, references,
            export.repository().values(), DEV);

        assertThat(targets(references)).doesNotContain(MSG, CORE);
        assertThat(closure.included()).doesNotContain(MSG, CORE);
    }

    @Test
    void theReferencesInsideAModuleFilesEmbeddedDocumentsAreIncluded() {
        // Module-0027 (Web Services Connector): the WSDLs in WsdlData and ValidWsdlData import
        // both schemas; the properties themselves name them in (old.)uri.import.<n>
        ModuleXml module = grpB().moduleFiles().get("Module-0027");
        WorkspacePath path = modulePath(module);
        WorkspacePath wsdl = WorkspacePath.embedded(DEV, OWNER, module.pluginType(),
            module.name(), "WsdlData", "wsdl");
        WorkspacePath validWsdl = WorkspacePath.embedded(DEV, OWNER, module.pluginType(),
            module.name(), "ValidWsdlData", "wsdl");

        List<RepositoryReference> references = ArchiveReferences.moduleFile(path,
            XmlNormalizer.normalize(module.element()));

        assertThat(references).contains(
            new RepositoryReference(path, MSG, Kind.URI),
            new RepositoryReference(wsdl, MSG, Kind.URI),
            new RepositoryReference(wsdl, CORE, Kind.URI),
            new RepositoryReference(validWsdl, MSG, Kind.URI),
            new RepositoryReference(validWsdl, CORE, Kind.URI));
    }

    @Test
    void aReferenceOnlyInAnEmbeddedStylesheetIsFound() {
        WorkspacePath path = WorkspacePath.module(DEV, "jdoe", "XSLT Converter", "Module-0001");
        String stylesheet = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""
            + " version=\"1.0\"><xsl:import href=\"inubitrepository:/Root/jdoe/xsl/a.xsl\"/>"
            + "</xsl:stylesheet>";
        String module = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Properties>"
            + "<Property name=\"xslt.stylesheet\" type=\"XmlDocument\">"
            + stylesheet.replace("&", "&amp;").replace("<", "&lt;") + "</Property>"
            + "</Properties>\n";

        List<RepositoryReference> references = ArchiveReferences.moduleFile(path,
            module.getBytes(StandardCharsets.UTF_8));

        assertThat(references).containsExactly(new RepositoryReference(
            WorkspacePath.embedded(DEV, "jdoe", "XSLT Converter", "Module-0001",
                "xslt.stylesheet", "xsl"), "/Root/jdoe/xsl/a.xsl", Kind.URI));
    }

    @Test
    void theIndexEntryCountsAMessageTypeSchemaAndTheWorkflowAUri() {
        WorkspacePath index = WorkspacePath.moduleIndex(DEV, "jdoe", "Assign", "Module-0003");
        WorkspacePath workflow = WorkspacePath.workflow(DEV, "jdoe", "GRP-01", "Workflow-0001");

        assertThat(ArchiveReferences.indexEntry(index, ("<Module><OutputMessageTypeSchema>"
            + "/Root/jdoe/xsd/a.xsd</OutputMessageTypeSchema></Module>")
            .getBytes(StandardCharsets.UTF_8)))
            .containsExactly(new RepositoryReference(index, "/Root/jdoe/xsd/a.xsd", Kind.URI));
        assertThat(ArchiveReferences.workflow(workflow, ("<Workflow a=\"inubitrepository:"
            + "/Root/jdoe/xsd/b.xsd\"/>").getBytes(StandardCharsets.UTF_8)))
            .containsExactly(new RepositoryReference(workflow, "/Root/jdoe/xsd/b.xsd", Kind.URI));
    }

    @Test
    void aModuleFileThatIsNoXmlHasNoReferences() {
        WorkspacePath path = WorkspacePath.module(DEV, "jdoe", "Assign", "Module-0003");

        assertThat(ArchiveReferences.moduleFile(path,
            "<Properties>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    /**
     * The scan {@code WorkspaceWriter} did before the extraction: the normalized properties as
     * the module path, each extracted document as its own path.
     */
    private static List<RepositoryReference> writerScan(WorkspacePath path, Element module) {
        Extracted extracted = EmbeddedDocuments.extract(module);
        List<RepositoryReference> references = new ArrayList<>(RepositoryReferences.scan(path,
            XmlNormalizer.normalize(extracted.properties())));
        for (Document document : extracted.documents()) {
            int dot = document.fileName().lastIndexOf('.');
            WorkspacePath file = WorkspacePath.embedded(path.group(), path.owner(),
                path.segments().get(0), path.segments().get(1), document.property(),
                document.fileName().substring(dot + 1));
            references.addAll(RepositoryReferences.scan(file, document.content()));
        }
        return references;
    }

    @Test
    void theModuleFileReferencesAreThoseOfThePropertiesAndEmbeddedDocumentsForAnyInput() {
        // no behaviour change for WorkspaceWriter (redacted elements), the same references from
        // the unredacted elements and from the raw module file bytes of the export
        int nonEmpty = 0;
        for (String fixture : ArtifactFixtures.EXPORTS) {
            Map<String, byte[]> entries = ArtifactFixtures.entries(fixture);
            ExportArchive export = new ArchiveReader().read(ArtifactFixtures.bytes(fixture));
            ExportArchive redacted = new SecretRedactor().redact(export).archive();
            for (ModuleXml module : export.moduleFiles().values()) {
                WorkspacePath path = modulePath(module);
                Element redactedModule = redacted.moduleFiles().get(module.name()).element();
                List<RepositoryReference> expected = writerScan(path, module.element());

                assertThat(ArchiveReferences.moduleFile(path,
                    XmlNormalizer.normalize(redactedModule)))
                    .as("%s %s redacted", fixture, module.name())
                    .isEqualTo(writerScan(path, redactedModule));
                assertThat(ArchiveReferences.moduleFile(path,
                    XmlNormalizer.normalize(module.element())))
                    .as("%s %s", fixture, module.name()).isEqualTo(expected);
                assertThat(ArchiveReferences.moduleFile(path, entries.get("module/"
                    + module.name().toLowerCase(Locale.ROOT) + ".xml")))
                    .as("%s %s raw", fixture, module.name()).isEqualTo(expected);
                nonEmpty += expected.isEmpty() ? 0 : 1;
            }
        }
        assertThat(nonEmpty).isPositive();
    }
}
