package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.ModuleIndexEntry;
import de.dadecker.inubit.mcp.adapter.archive.v81.ExportArchive.WorkflowXml;
import de.dadecker.inubit.mcp.adapter.archive.v81.MetaStore.Split;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T016 (research D-6, FR-013, FR-014): volatile values live in {@code .meta/}. */
class MetaStoreTest {

    private static final GroupId DEV = new GroupId("dev");

    @TempDir
    Path root;

    private static ExportArchive archive(String fixture) {
        return new ArchiveReader().read(ArtifactFixtures.bytes(fixture));
    }

    private static String text(Element element) {
        return new String(XmlNormalizer.normalize(element), StandardCharsets.UTF_8);
    }

    @Test
    void metaRecordsAreSortedJsonAtTheMirroredPath() throws IOException {
        WorkspacePath path = WorkspacePath.workflow(DEV, "OWNERS", "GRP-01", "Workflow-0001");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("zeta", "last");
        values.put("WorkflowUId", "-6f1dbb5:1a10fc4fb59:-7fff");
        values.put("context", Map.of("position", 1, "documentVersion", "5.3"));
        values.put("attributes", List.of(Map.of("name", "workflowType", "value", "technical")));
        MetaStore store = new MetaStore(root);

        store.write(path, values);

        Path file = root.resolve(".meta/dev/OWNERS/workflows/GRP-01/Workflow-0001.xml.json");
        String json = Files.readString(file);
        assertThat(json).startsWith("{\n  \"WorkflowUId\"").endsWith("}\n")
            .doesNotContain("\r");
        assertThat(json.indexOf("\"context\"")).isLessThan(json.indexOf("\"zeta\""));
        assertThat(json.indexOf("\"documentVersion\"")).isLessThan(json.indexOf("\"position\""));
        assertThat(store.read(path)).contains(Map.of("zeta", "last",
            "WorkflowUId", "-6f1dbb5:1a10fc4fb59:-7fff",
            "context", Map.of("position", 1, "documentVersion", "5.3"),
            "attributes", List.of(Map.of("name", "workflowType", "value", "technical"))));
        assertThat(MetaStore.serialize(values)).isEqualTo(Files.readAllBytes(file));
    }

    @Test
    void aMissingRecordIsEmptyAndABrokenOneIsRefused() throws IOException {
        WorkspacePath path = WorkspacePath.module(DEV, "OWNERS", "Assign", "M");
        MetaStore store = new MetaStore(root);

        assertThat(store.read(path)).isEmpty();
        Path file = root.resolve(".meta/dev/OWNERS/modules/Assign/M/module.xml.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        assertThatIllegalArgumentException().isThrownBy(() -> store.read(path))
            .withMessageContaining(".meta");
    }

    @Test
    void theUidAndTheExportSuffixOfTheCheckinCommentMoveToMeta() {
        WorkflowXml workflow = archive("grp-a.zip").workflowGroups().get(0).workflows().get(1);

        Split split = MetaStore.split(workflow.element());
        String file = text(split.element());

        assertThat(split.values()).containsEntry("WorkflowUId", "-6ff0c09a:1a0c97b5683:-7f78");
        assertThat((String) split.values().get("CheckinComment.exportSuffix"))
            .startsWith("@@@Deploying User: jdoe@@@Server: inubit-dev-1.example.test@@@")
            .endsWith("@@@");
        assertThat(split.exportTime()).isPresent();
        assertThat(file).contains("<WorkflowUId/>",
            "<CheckinComment>DefaultCommitCommentImport###JD: Fixture###</CheckinComment>",
            "<CheckoutUser>jdoe</CheckoutUser>", "<IsActive>false</IsActive>",
            "<StyleSheet xPos=").doesNotContain("Deploying User", "-7f78");
        assertThat((String) split.values().get(MetaStore.CHECKIN_HISTORY))
            .as("only the repetitions of the last segment").matches("(###)+");
        assertThat(MetaStore.restore(split.element(), split.values(), split.exportTime()))
            .isEqualTo(workflow.element());
    }

    @Test
    void theExportTimeIsNotKeptSoThatAnUnchangedReExportYieldsTheSameRecord() {
        // spike §3: INUBIT writes the export time into the suffix on every export (SC-001)
        Element first = archive("grp-a.zip").workflowGroups().get(0).workflows().get(0).element();
        Element second = XmlTree.parse(text(first).replace("Export/Deployment: 06.10.2026"
            + " 08:20:54", "Export/Deployment: 07.10.2026 09:00:00")
            .getBytes(StandardCharsets.UTF_8)).root();

        Split a = MetaStore.split(first);
        Split b = MetaStore.split(second);

        assertThat(MetaStore.serialize(a.values())).isEqualTo(MetaStore.serialize(b.values()));
        assertThat((String) a.values().get(MetaStore.CHECKIN_SUFFIX))
            .doesNotContain("Export/Deployment");
        assertThat(a.exportTime()).contains("06.10.2026 08:20:54");
        assertThat(text(MetaStore.restore(a.element(), a.values(),
            Optional.of("07.10.2026 09:00:00")))).isEqualTo(text(second));
    }

    @Test
    void theGrowingHistoryOfAWorkflowCommentMovesToMeta() {
        // recordings: every export appends copies of the last ###-separated segment of the
        // part before @@@Deploying User:; every other segment is written by a person
        String head = "DD: change###Import from inubit without version history";
        String suffix = "@@@Deploying User: jdoe@@@Version: 3@@@Export/Deployment:"
            + " 06.10.2026 08:00:00@@@";
        String repeated = "###Import from inubit without version history";
        Element first = workflow(head + suffix);
        Element second = workflow(head + repeated + repeated + suffix);

        Split a = MetaStore.split(first);
        Split b = MetaStore.split(second);

        assertThat(text(a.element())).contains("<CheckinComment>" + head + "</CheckinComment>");
        assertThat(a.element()).isEqualTo(b.element());
        assertThat(a.values()).doesNotContainKey(MetaStore.CHECKIN_HISTORY);
        assertThat(b.values()).containsEntry(MetaStore.CHECKIN_HISTORY, repeated + repeated);
        assertThat(MetaStore.restore(a.element(), a.values(), a.exportTime())).isEqualTo(first);
        assertThat(MetaStore.restore(b.element(), b.values(), b.exportTime()))
            .isEqualTo(second);
    }

    @Test
    void personWrittenSegmentsStayInTheFile() {
        Split split = MetaStore.split(workflow("DD: first###JD: second###JD: third"
            + "@@@Deploying User: jdoe@@@"));

        assertThat(text(split.element()))
            .contains("<CheckinComment>DD: first###JD: second###JD: third</CheckinComment>");
        assertThat(split.values()).doesNotContainKey(MetaStore.CHECKIN_HISTORY);
    }

    private static Element workflow(String comment) {
        return XmlTree.parse(("<Workflow><WorkflowName>W</WorkflowName><CheckinComment>"
            + comment + "</CheckinComment></Workflow>").getBytes(StandardCharsets.UTF_8)).root();
    }

    @Test
    void theModuleIndexKeepsReviewedValuesAndMovesTheUid() {
        ModuleIndexEntry entry = archive("module-one.zip").moduleIndex().get(0);

        Split split = MetaStore.split(entry.element());
        String file = text(split.element());

        assertThat(split.values()).containsKeys("ModuleUId", "CheckinComment.exportSuffix");
        assertThat(file).contains("<ModuleUId/>", "<LastUpdate>", "<ExportUser>OWNERS</ExportUser>",
            "<IsActive>true</IsActive>");
        assertThat(MetaStore.restore(split.element(), split.values(), split.exportTime()))
            .isEqualTo(entry.element());
    }

    @Test
    void aCommentWithoutExportSuffixStaysWhole() {
        Element workflow = XmlTree.parse(("<Workflow><WorkflowName>W</WorkflowName>"
            + "<CheckinComment>written by a person</CheckinComment></Workflow>")
            .getBytes(StandardCharsets.UTF_8)).root();

        Split split = MetaStore.split(workflow);

        assertThat(split.element()).isEqualTo(workflow);
        assertThat(split.values()).isEmpty();
        assertThat(MetaStore.restore(workflow, Map.of(), Optional.empty()))
            .isEqualTo(workflow);
    }
}
