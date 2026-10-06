package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspacePath.Kind;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T007 (research D-2, D-3, D-6): the workspace layout of one group and owner. */
class WorkspacePathTest {

    private static final GroupId DEV = new GroupId("dev");

    @Test
    void aWorkflowLivesBelowItsDiagramGroup() {
        WorkspacePath path = WorkspacePath.workflow(DEV, "OWNERS", "GRP-01", "Workflow-0001");

        assertThat(path.toRelativePath())
            .isEqualTo(Path.of("dev/OWNERS/workflows/GRP-01/Workflow-0001.xml"));
        assertThat(path.metaPath())
            .isEqualTo(Path.of(".meta/dev/OWNERS/workflows/GRP-01/Workflow-0001.xml.json"));
        assertThat(path.kind()).isEqualTo(Kind.WORKFLOW);
    }

    @Test
    void aModuleIsADirectoryWithItsConfigurationIndexAndEmbeddedDocuments() {
        assertThat(WorkspacePath.module(DEV, "OWNERS", "XSLT Converter", "Module-0001")
            .toRelativePath())
            .isEqualTo(Path.of("dev/OWNERS/modules/XSLT Converter/Module-0001/module.xml"));
        assertThat(WorkspacePath.moduleIndex(DEV, "OWNERS", "XSLT Converter", "Module-0001")
            .toRelativePath())
            .isEqualTo(Path.of("dev/OWNERS/modules/XSLT Converter/Module-0001/index.xml"));
        assertThat(WorkspacePath.embedded(DEV, "OWNERS", "XSLT Converter", "Module-0001",
            "xslt.stylesheet", "xsl").toRelativePath())
            .isEqualTo(Path.of(
                "dev/OWNERS/modules/XSLT Converter/Module-0001/xslt.stylesheet.xsl"));
        assertThat(WorkspacePath.embedded(DEV, "OWNERS", "XSLT Converter", "Module-0001",
            "xslt.stylesheet", "xsl").metaPath())
            .isEqualTo(Path.of(
                ".meta/dev/OWNERS/modules/XSLT Converter/Module-0001/xslt.stylesheet.xsl.json"));
    }

    @Test
    void aRepositoryFileKeepsItsRepositoryPath() {
        WorkspacePath path = WorkspacePath.repository(DEV, "OWNERS", "/Root/OWNERS/xsd/msg.xsd");

        assertThat(path.toRelativePath())
            .isEqualTo(Path.of("dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd"));
        assertThat(path.segments()).containsExactly("Root", "OWNERS", "xsd", "msg.xsd");
        assertThat(WorkspacePath.repository(DEV, "OWNERS", "Root/OWNERS/xsd/msg.xsd"))
            .isEqualTo(path);
    }

    @Test
    void namesAreEncodedSegmentBySegment() {
        WorkspacePath path = WorkspacePath.workflow(DEV, "team/a", "Orders: 2026", "a/b..");

        assertThat(path.toRelativePath())
            .isEqualTo(Path.of("dev/team%2Fa/workflows/Orders%3A 2026/a%2Fb%2E%2E.xml"));
        assertThat(path.toRelativePath().getNameCount()).isEqualTo(5);
        assertThat(WorkspacePath.repository(DEV, "OWNERS", "Root/x/../y").toRelativePath())
            .isEqualTo(Path.of("dev/OWNERS/repository/Root/x/%2E%2E/y"));
    }

    @Test
    void parseInvertsEveryKind() {
        List<WorkspacePath> paths = List.of(
            WorkspacePath.workflow(DEV, "OWNERS", "GRP-01", "Workflow-0001"),
            WorkspacePath.workflow(DEV, "jdoe", "Orders: 2026/Q4", ".odd name. "),
            WorkspacePath.module(DEV, "OWNERS", "Web Services Connector", "Module-0028"),
            WorkspacePath.moduleIndex(DEV, "OWNERS", "Web Services Connector", "Module-0028"),
            WorkspacePath.embedded(DEV, "OWNERS", "Web Services Connector", "Module-0028",
                "WsdlData", "wsdl"),
            WorkspacePath.embedded(DEV, "OWNERS", "JSON Validator", "Module-0018",
                "JSONStaticSchema", "bin"),
            WorkspacePath.embedded(DEV, "OWNERS", "XSLT Converter", "M", "odd.name.", "xsl"),
            WorkspacePath.repository(DEV, "OWNERS", "Root/OWNERS/xsd/msg.xsd"));

        for (WorkspacePath path : paths) {
            assertThat(WorkspacePath.parse(path.toRelativePath())).isEqualTo(path);
            assertThat(WorkspacePath.parseMeta(path.metaPath())).isEqualTo(path);
        }
    }

    @Test
    void theNamesAreAvailableDecoded() {
        WorkspacePath path = WorkspacePath.parse(
            Path.of("dev/team%2Fa/modules/XSLT Converter/a%2Fb/xslt.stylesheet.xsl"));

        assertThat(path.group()).isEqualTo(DEV);
        assertThat(path.owner()).isEqualTo("team/a");
        assertThat(path.kind()).isEqualTo(Kind.EMBEDDED);
        assertThat(path.segments()).containsExactly("XSLT Converter", "a/b", "xslt.stylesheet",
            "xsl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "dev/OWNERS", "dev/OWNERS/workflows/GRP-01",
        "dev/OWNERS/workflows/GRP-01/W.txt", "dev/OWNERS/workflows/GRP-01/x/W.xml",
        "dev/OWNERS/modules/T/M", "dev/OWNERS/modules/T/M/noextension",
        "dev/OWNERS/modules/T/M/sub/x.xsl", "dev/OWNERS/other/x.xml", "Dev/OWNERS/repository/x",
        "dev/OWNERS/repository", ".meta/dev/OWNERS/workflows/G/W.xml.json",
        "dev/OWNERS/workflows/G/a%2.xml", "/dev/OWNERS/workflows/G/W.xml",
        "dev/OWNERS/workflows/../W.xml", "dev/OWNERS/modules/T/M/x.XSL"})
    void otherPathsAreNoArtifactPaths(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.parse(Path.of(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev/OWNERS/workflows/G/W.xml.json",
        ".meta/dev/OWNERS/workflows/G/W.xml",
        ".meta/dev/OWNERS/workflows/G/W.json", ".tests/dev/OWNERS/workflows/G/W.xml.json"})
    void otherPathsAreNoMetaPaths(String path) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> WorkspacePath.parseMeta(Path.of(path)));
    }

    @Test
    void embeddedFilesCannotShadowTheModuleFiles() {
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.embedded(DEV, "O",
            "T", "M", "module", "xml")).withMessageContaining("module.xml");
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.embedded(DEV, "O",
            "T", "M", "index", "xml")).withMessageContaining("index.xml");
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.embedded(DEV, "O",
            "T", "M", "p", "XSL")).withMessageContaining("extension");
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.embedded(DEV, "O",
            "T", "M", "p", "a.b")).withMessageContaining("extension");
    }

    @Test
    void segmentsMatchTheKind() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkspacePath(DEV, "O",
            Kind.WORKFLOW, List.of("only-one")));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkspacePath(DEV, "O",
            Kind.REPOSITORY, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.repository(DEV, "O",
            "Root//x.xsd"));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkspacePath.workflow(DEV, "O",
            "", "W"));
        assertThatNullPointerException().isThrownBy(() -> WorkspacePath.workflow(null, "O",
            "G", "W"));
    }
}
