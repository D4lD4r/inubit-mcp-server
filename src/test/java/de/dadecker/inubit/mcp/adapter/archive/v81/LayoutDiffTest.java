package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T009 (feature 005, research D-5): a workflow whose only differences lie inside
 * {@code <StyleSheet>} elements (node positions, label positions) is layout-only; any other
 * difference — an edge, a condition — is a real change. Checked on rendered workspace files of
 * {@code grp-a.zip}.
 */
class LayoutDiffTest {

    private static final GroupId GROUP = new GroupId("dev");
    private static final String OWNER = "jdoe";

    private final Map<String, byte[]> files = WorkspaceWriter.render(new SecretRedactor().redact(
        new ArchiveReader().read(ArtifactFixtures.bytes("grp-a.zip"))), GROUP, OWNER).files();

    private String workflow(String name) {
        String path = WorkspacePath.workflow(GROUP, OWNER, "GRP-01", name).toRelativePath()
            .toString().replace('\\', '/');
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String edit(String text, String from, String to) {
        assertThat(text).contains(from);
        return text.replaceFirst(java.util.regex.Pattern.quote(from),
            java.util.regex.Matcher.quoteReplacement(to));
    }

    @Test
    void aMovedNodeIsLayoutOnly() {
        String release = workflow("Workflow-0001");
        String target = edit(release, "xPos=\"320\" yPos=\"230\"", "xPos=\"340\" yPos=\"260\"");

        assertThat(LayoutDiff.layoutOnly(bytes(release), bytes(target))).isTrue();
    }

    /** {@code Workflow-0002} with a label position on its first edge (as INUBIT writes it). */
    private String labelled() {
        return edit(workflow("Workflow-0002"), "<ConnectionId>5</ConnectionId>",
            "<ConnectionId>5</ConnectionId><StyleSheet labelPosition=\"50.0\"/>");
    }

    @Test
    void aMovedLabelIsLayoutOnly() {
        String release = labelled();
        String target = edit(release, "labelPosition=\"50.0\"", "labelPosition=\"35.5\"");

        assertThat(LayoutDiff.layoutOnly(bytes(release), bytes(target))).isTrue();
    }

    @Test
    void aStyleSheetThatOnlyOneSideHasIsLayoutOnly() {
        assertThat(LayoutDiff.layoutOnly(bytes(labelled()), bytes(workflow("Workflow-0002"))))
            .isTrue();
    }

    @Test
    void aChangedEdgeIsNotLayoutOnly() {
        String release = workflow("Workflow-0001");
        String target = edit(edit(release, "<Connection moduleOutId=\"4\">",
            "<Connection moduleOutId=\"3\">"), "xPos=\"120\"", "xPos=\"140\"");

        assertThat(LayoutDiff.layoutOnly(bytes(release), bytes(target))).isFalse();
    }

    @Test
    void aChangedConditionIsNotLayoutOnly() {
        String release = workflow("Workflow-0002");
        String target = edit(release, "@@@=@@@a", "@@@=@@@b");

        assertThat(LayoutDiff.layoutOnly(bytes(release), bytes(target))).isFalse();
    }

    @Test
    void equalFilesAreNotLayoutOnlyButUnchanged() {
        String release = workflow("Workflow-0001");

        assertThat(LayoutDiff.layoutOnly(bytes(release), bytes(release))).isFalse();
        assertThat(LayoutDiff.layoutOnly(bytes(release),
            bytes(release.replace("><", ">\n<")))).isFalse();
    }

    @Test
    void aFileThatIsNoXmlIsRefused() {
        assertThatThrownBy(() -> LayoutDiff.layoutOnly(bytes("<a>"), bytes("<a/>")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
