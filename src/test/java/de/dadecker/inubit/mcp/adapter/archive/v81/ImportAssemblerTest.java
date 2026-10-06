package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Artifact;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Assembled;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.CheckinComment;
import de.dadecker.inubit.mcp.adapter.archive.v81.ImportAssembler.Request;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * T012 (feature 004, research D-6, D-7, D-11, D-24, D-25): the import archive holds exactly the
 * change set, with the target's secret values, the probed check-in comment shape, no
 * {@code CheckoutUser} and no {@code Repository.zip}.
 */
class ImportAssemblerTest {

    private static final GroupId GROUP = new GroupId("dev");
    private static final String OWNER = "OWNERS";
    private static final CheckinComment COMMENT = new CheckinComment("Fix the mapping", "jdoe",
        "inubit-dev-1.example.test", "07.10.2026 10:15:00");

    private final ArchiveReader reader = new ArchiveReader();
    private final ExportArchive raw = reader.read(ArtifactFixtures.bytes("grp-b.zip"));
    private final SecretValues secrets = SecretValues.of(raw);
    private final SortedMap<String, byte[]> files = new TreeMap<>(WorkspaceWriter.render(
        new SecretRedactor().redact(raw), GROUP, OWNER).files());
    private final Map<String, Integer> versions = ImportAssembler.currentVersions(
        List.of(ArtifactFixtures.bytes("grp-b.zip")));

    private static Artifact workflow(String name, boolean created) {
        return new Artifact(name, Optional.empty(), created);
    }

    private static Artifact module(String name, String pluginType, boolean created) {
        return new Artifact(name, Optional.of(pluginType), created);
    }

    private Request group(List<Artifact> workflows, List<Artifact> modules) {
        return new Request(GROUP, OWNER, Optional.of("GRP-02"), workflows, modules, COMMENT,
            versions, java.util.Set.of());
    }

    private static String entryText(byte[] zip, String entry) {
        return new String(ArtifactFixtures.entries(zip).get(entry), StandardCharsets.UTF_8);
    }

    private static String text(Element element) {
        return new String(XmlNormalizer.normalize(element), StandardCharsets.UTF_8);
    }

    private String pluginType(String module) {
        return raw.moduleIndex().stream().filter(entry -> entry.name().equals(module))
            .findFirst().orElseThrow().pluginType();
    }

    @Test
    void aWorkflowImportHoldsOnlyThatWorkflowWithAnEmptyModuleIndex() {
        Assembled assembled = ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0006", false)), List.of()), secrets);

        assertThat(ArtifactFixtures.entries(assembled.zip()).keySet()).containsExactly(
            "archive.properties", "workflow/workflow.xml", "module/module.xml");
        assertThat(assembled.workflows()).containsExactly("Workflow-0006");
        assertThat(assembled.modules()).isEmpty();
        ExportArchive back = reader.read(assembled.zip());
        assertThat(back.workflowGroups()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo("GRP-02");
            assertThat(group.workflows()).extracting(WorkflowXml::name)
                .containsExactly("Workflow-0006");
        });
        assertThat(back.moduleIndex()).isEmpty();
        assertThat(back.repository()).isEmpty();
        assertThat(entryText(assembled.zip(), "archive.properties"))
            .contains("sourceVersion=").contains("operationId=");
    }

    @Test
    void theTargetsSecretsAreBackInPlaceOfThePlaceholders() {
        Assembled assembled = ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0006", false)), List.of(module("Module-0028",
                pluginType("Module-0028"), false))), secrets);

        String workflow = entryText(assembled.zip(), "workflow/workflow.xml");
        String module = entryText(assembled.zip(), "module/module-0028.xml");
        assertThat(workflow + module).doesNotContain("${secret:");
        assertThat(workflow).contains("synthetic-literal-0001", "synthetic-default-0001");
        assertThat(module).contains("U1lOMDAwMDAx", "synthetic-plain-0001", "U1lOMDAwMDAz");
        ExportArchive back = reader.read(assembled.zip());
        assertThat(text(back.moduleFiles().get("Module-0028").element()))
            .isEqualTo(text(raw.moduleFiles().get("Module-0028").element()));
    }

    @Test
    void theCheckinCommentHasTheProbedShapeWithTheNextVersion() {
        Assembled assembled = ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0006", false)), List.of(module("Module-0028",
                pluginType("Module-0028"), false))), secrets);

        ExportArchive back = reader.read(assembled.zip());
        Element workflow = back.workflowGroups().get(0).workflows().get(0).element();
        Element index = back.moduleIndex().get(0).element();
        assertThat(workflow.child("CheckinComment").orElseThrow().text()).isEqualTo(
            "DefaultCommitCommentImport###Fix the mapping###@@@Deploying User: jdoe@@@Server:"
                + " inubit-dev-1.example.test@@@Version: "
                + (versions.get("Workflow-0006") + 1)
                + "@@@Export/Deployment: 07.10.2026 10:15:00@@@");
        assertThat(index.child("CheckinComment").orElseThrow().text()).startsWith(
            "DefaultCommitCommentImport###Fix the mapping###@@@Deploying User: jdoe@@@")
            .contains("@@@Version: " + (versions.get("Module-0028") + 1) + "@@@");
        assertThat(versions.get("Workflow-0006")).isPositive();
    }

    @Test
    void checkoutUserIsStrippedAndUidsComeFromMeta() {
        String path = slash(WorkspacePath.workflow(GROUP, OWNER, "GRP-02", "Workflow-0006"));
        String file = new String(files.get(path), StandardCharsets.UTF_8);
        files.put(path, file.replace("<IsActive>", "<CheckoutUser>jdoe</CheckoutUser><IsActive>")
            .getBytes(StandardCharsets.UTF_8));

        Assembled assembled = ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0006", false)), List.of()), secrets);

        Element workflow = reader.read(assembled.zip()).workflowGroups().get(0).workflows()
            .get(0).element();
        assertThat(workflow.child("CheckoutUser")).isEmpty();
        Element original = raw.workflowGroups().get(0).workflows().stream()
            .filter(w -> w.name().equals("Workflow-0006")).findFirst().orElseThrow().element();
        assertThat(workflow.child("WorkflowUId").map(Element::text))
            .isEqualTo(original.child("WorkflowUId").map(Element::text));
    }

    @Test
    void aModuleImportIsAModuleOnlyArchive() {
        Assembled assembled = ImportAssembler.assemble(files, new Request(GROUP, OWNER,
            Optional.empty(), List.of(), List.of(module("Module-0028", pluginType("Module-0028"),
                false)), COMMENT, versions, java.util.Set.of()), secrets);

        assertThat(ArtifactFixtures.entries(assembled.zip()).keySet()).containsExactly(
            "archive.properties", "module/module.xml", "module/module-0028.xml",
            "Repository.zip");
        ExportArchive back = reader.read(assembled.zip());
        assertThat(back.workflowGroups()).isEmpty();
        assertThat(back.moduleIndex()).extracting(ModuleIndexEntry::name)
            .containsExactly("Module-0028");
        assertThat(back.repository()).isEmpty();
    }

    @Test
    void aNewWorkflowAndModuleGetTheDefaultContextWithoutUids() {
        String source = slash(WorkspacePath.workflow(GROUP, OWNER, "GRP-02", "Workflow-0004"));
        String copy = new String(files.get(source), StandardCharsets.UTF_8)
            .replace("Workflow-0004", "Workflow-0100");
        files.put(slash(WorkspacePath.workflow(GROUP, OWNER, "GRP-02", "Workflow-0100")),
            copy.getBytes(StandardCharsets.UTF_8));
        String type = pluginType("Module-0023");
        String moduleSource = slash(WorkspacePath.module(GROUP, OWNER, type, "Module-0023"));
        String indexSource = slash(WorkspacePath.moduleIndex(GROUP, OWNER, type, "Module-0023"));
        files.put(slash(WorkspacePath.module(GROUP, OWNER, type, "Module-0100")),
            files.get(moduleSource));
        files.put(slash(WorkspacePath.moduleIndex(GROUP, OWNER, type, "Module-0100")),
            new String(files.get(indexSource), StandardCharsets.UTF_8)
                .replace("Module-0023", "Module-0100").getBytes(StandardCharsets.UTF_8));
        embedded(type, "Module-0023", "Module-0100");

        Assembled assembled = ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0100", true)), List.of(module("Module-0100", type, true))),
            secrets);

        ExportArchive back = reader.read(assembled.zip());
        Element workflow = back.workflowGroups().get(0).workflows().get(0).element();
        assertThat(back.workflowGroups().get(0).name()).isEqualTo("GRP-02");
        assertThat(workflow.child("WorkflowName").map(Element::text)).contains("Workflow-0100");
        assertThat(workflow.child("WorkflowUId")).isEmpty();
        assertThat(workflow.child("IsActive").map(Element::text)).contains("false");
        assertThat(workflow.child("CheckinComment").map(Element::text).orElseThrow())
            .contains("@@@Version: 1@@@");
        Element index = back.moduleIndex().get(0).element();
        assertThat(back.moduleIndex().get(0).name()).isEqualTo("Module-0100");
        assertThat(index.child("ModuleUId")).isEmpty();
        assertThat(back.moduleFiles()).containsKey("Module-0100");
        assertThat(assembled.modules()).containsExactly("Module-0100");
    }

    @Test
    void aPlaceholderWithoutValueOnTheTargetIsUnresolvedNamingArtifactAndPath() {
        String type = pluginType("Module-0028");
        files.put(slash(WorkspacePath.module(GROUP, OWNER, type, "Module-0101")),
            files.get(slash(WorkspacePath.module(GROUP, OWNER, type, "Module-0028"))));
        files.put(slash(WorkspacePath.moduleIndex(GROUP, OWNER, type, "Module-0101")),
            new String(files.get(slash(WorkspacePath.moduleIndex(GROUP, OWNER, type,
                "Module-0028"))), StandardCharsets.UTF_8).replace("Module-0028", "Module-0101")
                .getBytes(StandardCharsets.UTF_8));
        embedded(type, "Module-0028", "Module-0101");

        assertThatThrownBy(() -> ImportAssembler.assemble(files, group(List.of(),
            List.of(module("Module-0101", type, true))), secrets))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.SECRET_UNRESOLVED);
                assertThat(e.error().message()).contains("Module-0101", "Password");
                for (var secret : ArtifactFixtures.syntheticSecrets()) {
                    assertThat(e.error().toString()).doesNotContain(secret.value());
                }
            });
    }

    @Test
    void aNewNameThatTheTargetUsesForAnotherKindOrOwnerIsRefused() {
        String path = slash(WorkspacePath.workflow(GROUP, OWNER, "GRP-02", "Module-0028"));
        files.put(path, files.get(slash(WorkspacePath.workflow(GROUP, OWNER, "GRP-02",
            "Workflow-0004"))));

        assertThatThrownBy(() -> ImportAssembler.assemble(files, new Request(GROUP, OWNER,
            Optional.of("GRP-02"), List.of(workflow("Module-0028", true)), List.of(), COMMENT,
            versions, java.util.Set.of("Module-0028")), secrets))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("Module-0028");
            });
    }

    @Test
    void anArtifactThatIsNotInTheFilesIsRefusedBeforeAnythingIsBuilt() {
        assertThatThrownBy(() -> ImportAssembler.assemble(files, group(
            List.of(workflow("Workflow-0999", false)), List.of()), secrets))
            .isInstanceOfSatisfying(ToolErrorException.class, e ->
                assertThat(e.error().message()).contains("Workflow-0999"));
    }

    @Test
    void theReasonIsTheOnlyPersonWrittenSegment() {
        assertThatThrownBy(() -> new CheckinComment("a###b", "jdoe", "h", "t"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckinComment("a@@@b", "jdoe", "h", "t"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckinComment("a\nb", "jdoe", "h", "t"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Copies the embedded documents and the {@code .meta} record of a module. */
    private void embedded(String type, String from, String to) {
        String prefix = slash(WorkspacePath.module(GROUP, OWNER, type, from)).replace(
            "/module.xml", "/");
        String target = prefix.replace("/" + from + "/", "/" + to + "/");
        List<String> keys = new ArrayList<>(files.keySet());
        for (String key : keys) {
            if (key.startsWith(prefix) && !key.endsWith("module.xml")
                && !key.endsWith("index.xml")) {
                files.put(target + key.substring(prefix.length()), files.get(key));
            }
        }
        String meta = slash(WorkspacePath.module(GROUP, OWNER, type, from).metaPath());
        if (files.containsKey(meta)) {
            files.put(meta.replace("/" + from + "/", "/" + to + "/"), files.get(meta));
        }
    }

    private static String slash(WorkspacePath path) {
        return path.toRelativePath().toString().replace('\\', '/');
    }

    private static String slash(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }
}
