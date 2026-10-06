package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.adapter.archive.v81.WorkspaceInspector;
import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.CheckFinding.Severity;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T025 (research D-13, FR-027, SC-004): the structure checks of workflow files; each defect
 * fixture of T003 yields exactly its finding, the unchanged fixtures no ERROR.
 */
class ArtifactCheckServiceTest {

    static final GroupId DEV = new GroupId("dev");

    @TempDir
    Path root;

    /** Writes {@code zip} into the workspace for {@code owner}, as an export would. */
    void export(byte[] zip, String owner) {
        new ArchiveCodec().prepare(DEV, owner, List.of(zip)).writeTo(root);
    }

    static final NodeId NODE = NodeId.parse("dev/node1");
    final FakeModuleLists server = new FakeModuleLists(NODE);

    ArtifactCheckService service() {
        return new ArtifactCheckService(root, new WorkspaceInspector(),
            new de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner(root),
            group -> group.equals(DEV) ? Optional.of(NODE) : Optional.empty(),
            node -> server, node -> Optional.of("OWNERS"), ResultLimiter.withDefaults(),
            java.time.Clock.systemUTC());
    }

    /** The defect fixture {@code defects/<name>/} as an export ZIP. */
    static byte[] defect(String name) {
        try {
            Path directory = Path.of(ArtifactFixtures.class.getResource(
                "/fixtures/v8_1/artifacts/defects/" + name).toURI());
            Map<String, byte[]> entries = new LinkedHashMap<>();
            for (String fixed : List.of("archive.properties", "Repository.zip",
                "workflow/workflow.xml")) {
                entries.put(fixed, Files.readAllBytes(directory.resolve(fixed)));
            }
            try (Stream<Path> modules = Files.list(directory.resolve("module"))) {
                for (Path module : modules.sorted().toList()) {
                    entries.put("module/" + module.getFileName(), Files.readAllBytes(module));
                }
            }
            return ArtifactFixtures.zip(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private void rewrite(String path, UnaryOperator<String> change) throws IOException {
        Path file = root.resolve(path);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String changed = change.apply(text);
        assertThat(changed).as("the change of %s applies", path).isNotEqualTo(text);
        Files.writeString(file, changed, StandardCharsets.UTF_8);
    }

    @Test
    void theUnchangedFixturesHaveNoError() {
        export(ArtifactFixtures.bytes("grp-a.zip"), "jdoe");
        export(ArtifactFixtures.bytes("grp-b.zip"), "OWNERS");

        List<CheckFinding> findings = service().checkPaths(List.of("dev"), true);

        assertThat(findings).noneMatch(f -> f.severity() == Severity.ERROR);
        assertThat(service().checkPaths(List.of("dev/jdoe"), true)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "dangling-edge, EDGE_TARGET_MISSING, ERROR, Workflow-0001.xml",
        "id-collision, ID_COLLISION, ERROR, Workflow-0001.xml",
        "demux-key-unmatched, DEMUX_KEY_UNMATCHED, ERROR, Workflow-0002.xml",
        "repository-ref-missing, REPOSITORY_REF_MISSING, ERROR, Workflow-0001.xml",
        "variable-unresolved, VARIABLE_UNRESOLVED, WARNING, Workflow-0001.xml"})
    void eachDefectYieldsExactlyItsFinding(String defect, String code, Severity severity,
        String workflow) {
        export(defect(defect), "jdoe");

        List<CheckFinding> findings = service().checkPaths(List.of("dev/jdoe/workflows"), true);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo(code);
            assertThat(finding.severity()).isEqualTo(severity);
            assertThat(finding.check()).isEqualTo(CheckFinding.Check.STRUCTURE);
            assertThat(finding.path()).isEqualTo("dev/jdoe/workflows/GRP-01/" + workflow);
            assertThat(finding.location()).isPresent();
            assertThat(finding.message()).isNotBlank().hasSizeLessThanOrEqualTo(500);
        });
    }

    @Test
    void theFindingsNameTheProblem() {
        export(defect("dangling-edge"), "jdoe");

        CheckFinding finding = service().checkPaths(List.of("dev"), true).get(0);

        assertThat(finding.location()).contains("WorkflowModule[ModuleId=2]/Connection");
        assertThat(finding.message()).contains("99");
    }

    // --- T026: module existence (FR-027, FR-028) ------------------------------------------------

    @Test
    void aModuleNeitherInTheWorkspaceNorOnTheServerIsMissing() {
        export(defect("missing-module"), "jdoe");
        server.modules.put("jdoe", List.of("Module-0001"));
        server.modules.put("OWNERS", List.of("Module-0002"));

        List<CheckFinding> findings = service().checkPaths(List.of("dev/jdoe/workflows"), true);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("MODULE_MISSING");
            assertThat(finding.severity()).isEqualTo(Severity.ERROR);
            assertThat(finding.location()).contains("WorkflowModule[ModuleId=4]");
            assertThat(finding.message()).contains("Module-0404", "jdoe", "OWNERS");
        });
        assertThat(server.calls).containsExactlyInAnyOrder("jdoe", "OWNERS");
    }

    @Test
    void aModuleOnTheServerOfTheInventoryOwnerIsFound() {
        export(defect("missing-module"), "jdoe");
        server.modules.put("OWNERS", List.of("Module-0404"));

        assertThat(service().checkPaths(List.of("dev"), true)).isEmpty();
    }

    @Test
    void aModuleOfAnotherOwnerInTheWorkspaceNeedsNoServer() throws IOException {
        export(defect("missing-module"), "jdoe");
        export(ArtifactFixtures.bytes("module-one.zip"), "OWNERS");
        rewrite("dev/jdoe/workflows/GRP-01/Workflow-0001.xml",
            xml -> xml.replace("<ModuleName>Module-0404</ModuleName>",
                "<ModuleName>Module-0023</ModuleName>"));

        assertThat(service().checkPaths(List.of("dev/jdoe"), true)).isEmpty();
        assertThat(server.calls).isEmpty();
    }

    @Test
    void anUnreachableServerLeavesTheModuleUnverified() {
        export(defect("missing-module"), "jdoe");
        server.unreachable = true;

        assertThat(service().checkPaths(List.of("dev"), true)).singleElement()
            .satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("MODULE_UNVERIFIED");
                assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                assertThat(finding.message()).contains("Module-0404", "dev/node1");
            });
    }

    @Test
    void withoutServerLookupsMissingModulesAreUnverified() {
        export(defect("missing-module"), "jdoe");

        assertThat(service().checkPaths(List.of("dev"), false)).singleElement()
            .satisfies(f -> assertThat(f.code()).isEqualTo("MODULE_UNVERIFIED"));
        assertThat(server.calls).isEmpty();
    }

    @Test
    void theModuleListsAreReadOncePerCheck() throws IOException {
        export(defect("missing-module"), "jdoe");
        rewrite("dev/jdoe/workflows/GRP-01/Workflow-0002.xml",
            xml -> xml.replace("<ModuleName>Module-0005</ModuleName>",
                "<ModuleName>Module-0405</ModuleName>"));

        assertThat(service().checkPaths(List.of("dev"), true))
            .extracting(CheckFinding::code).containsExactly("MODULE_MISSING", "MODULE_MISSING");
        assertThat(server.calls).containsExactlyInAnyOrder("jdoe", "OWNERS");
    }

    @Test
    void anEndlessStylesheetReleasesTheWorkspaceLock() throws IOException {
        // review I1: a deadline ends the run, the lock is free again
        Path stylesheet = root.resolve("dev/OWNERS/modules/XSLT Converter/Endless/"
            + "xslt.stylesheet.xsl");
        Files.createDirectories(stylesheet.getParent());
        Files.writeString(stylesheet, de.dadecker.inubit.mcp.adapter.xslt.XsltRunnerTestAccess
            .endless());
        Files.writeString(root.resolve("input.xml"), "<a/>");
        ArtifactCheckService service = new ArtifactCheckService(root, new WorkspaceInspector(),
            new de.dadecker.inubit.mcp.adapter.xslt.SaxonXsltRunner(root,
                java.time.Duration.ofSeconds(1)), group -> Optional.empty(), node -> server,
            node -> Optional.empty(), ResultLimiter.withDefaults(), java.time.Clock.systemUTC());

        ArtifactCheckService.CheckOutcome outcome = service.check(
            new ArtifactCheckService.CheckRequest(List.of(), Optional.of(
                new ArtifactCheckService.XsltCheck(root.relativize(stylesheet).toString(),
                    "input.xml", Map.of(), Optional.empty())), Optional.empty(), false));

        assertThat(outcome.report().findings()).extracting(CheckFinding::code)
            .containsExactly("XSLT_RUNTIME_ERROR");
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            assertThat(lock).as("released").isNotNull();
        }
    }

    @Test
    void aConditionForADeletedNodeIsAStaleWarningNotAnError() throws IOException {
        // live acceptance: the Workbench keeps conditions of deleted nodes; INUBIT ignores them
        export(ArtifactFixtures.bytes("grp-a.zip"), "jdoe");
        rewrite("dev/jdoe/workflows/GRP-01/Workflow-0002.xml", xml -> xml.replace(
            "<Property name=\"DefaultOutput\">", "<Property name=\"Module-0404(77)@@@DeMuxInput\">"
                + "/*/@type@@@=@@@b</Property><Property name=\"Module-0404(77)@@@ProcessingOrder"
                + "\">2</Property><Property name=\"DefaultOutput\">"));

        List<CheckFinding> findings = service().checkPaths(List.of("dev/jdoe"), false);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("DEMUX_KEY_STALE");
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.message()).contains("Module-0404(77)", "no longer exists");
        });
    }

    @Test
    void aMissingParentIsAnError() throws IOException {
        export(ArtifactFixtures.bytes("grp-b.zip"), "OWNERS");
        rewrite("dev/OWNERS/workflows/GRP-02/Workflow-0005.xml",
            xml -> xml.replace("<ParentModule moduleId=\"25008718\"/>",
                "<ParentModule moduleId=\"99999999\"/>"));

        List<CheckFinding> errors = service().checkPaths(List.of("dev"), true).stream()
            .filter(f -> f.severity() == Severity.ERROR).toList();

        assertThat(errors).isNotEmpty().allSatisfy(f -> {
            assertThat(f.code()).isEqualTo("PARENT_REF_MISSING");
            assertThat(f.message()).contains("99999999");
        });
    }

    @Test
    void aDerivedValueThatNoLongerMatchesIsAWarning() throws IOException {
        export(ArtifactFixtures.bytes("grp-b.zip"), "OWNERS");
        rewrite("dev/OWNERS/modules/JSON Validator/Module-0018/JSONStaticSchema.bin",
            json -> json.replace("$schema", "$schemaX"));
        rewrite("dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd",
            xsd -> xsd.replace("<xs:schema", "<xs:schema "));

        List<CheckFinding> mismatches = service().checkPaths(List.of("dev"), true).stream()
            .filter(f -> f.code().equals("DERIVED_VALUE_MISMATCH")).toList();

        assertThat(mismatches).extracting(CheckFinding::path).containsExactlyInAnyOrder(
            "dev/OWNERS/modules/JSON Validator/Module-0018/module.xml",
            "dev/OWNERS/repository/Root/OWNERS/xsd/msg.xsd");
        assertThat(mismatches).allMatch(f -> f.severity() == Severity.WARNING);
        assertThat(mismatches.get(0).message() + mismatches.get(1).message())
            .contains("JSONStaticSchemaMD5", "contentMD5");
    }
}
