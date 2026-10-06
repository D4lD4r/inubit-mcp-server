package de.dadecker.inubit.mcp.application;

import static de.dadecker.inubit.mcp.application.ExportHarness.DEV;
import static de.dadecker.inubit.mcp.application.ExportHarness.diagramGroups;
import static de.dadecker.inubit.mcp.application.ExportHarness.modules;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArchiveCodec;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures;
import de.dadecker.inubit.mcp.application.WorkspaceService.ExportResult;
import de.dadecker.inubit.mcp.application.WorkspaceService.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WorkspacePath;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort;
import de.dadecker.inubit.mcp.domain.port.ArchiveCodecPort.PreparedExport;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T021 (research D-9, FR-016 – FR-020, SC-001, SC-006): the export transaction of
 * {@link WorkspaceService} on a real git history with a fake StartCLI.
 */
class WorkspaceExportTest {

    private static final GroupId GROUP = DEV.group();

    @TempDir
    Path root;

    private ExportHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ExportHarness(root);
        harness.artifacts.exports.put("GRP-01", ArtifactFixtures.bytes("grp-a.zip"));
        harness.artifacts.exports.put("GRP-02", ArtifactFixtures.bytes("grp-b.zip"));
    }

    private static String workflow(String owner, String diagramGroup, String name) {
        return WorkspacePath.workflow(GROUP, owner, diagramGroup, name).toRelativePath()
            .toString();
    }

    @Test
    void anExportWritesTheFilesAndRecordsOneHistoryEntry() {
        ExportResult result = harness.service().export(diagramGroups("OWNERS", "GRP-02"));

        assertThat(harness.artifacts.calls).containsExactly("group OWNERS GRP-02");
        assertThat(result.node()).isEqualTo(DEV);
        assertThat(result.workspace()).isEqualTo(root);
        assertThat(result.localChanges()).isEmpty();
        assertThat(result.unchanged()).isFalse();
        assertThat(result.export()).hasValueSatisfying(entry -> {
            assertThat(entry.message()).isEqualTo("export dev/node1: diagram group GRP-02 ("
                + entry.changes().size() + " files)");
            assertThat(entry.changes()).extracting(PathChange::path)
                .contains(workflow("OWNERS", "GRP-02", "Workflow-0003"));
        });
        assertThat(result.secretsReplaced()).isGreaterThan(40);
        assertThat(harness.log()).containsExactly(result.export().orElseThrow().message());
        assertThat(harness.history.status()).isEmpty();
        assertThat(root.resolve(workflow("OWNERS", "GRP-02", "Workflow-0003"))).isRegularFile();
        try (WorkspaceLock lock = WorkspaceLock.acquire(root)) {
            assertThat(lock).as("the lock is released").isNotNull();
        }
    }

    @Test
    void anExportEntryCarriesTheServerStateTrailerOfItsGroup() {
        // feature 004 (T007, research D-3, D-25): the base of a later import
        harness.service().export(diagramGroups("OWNERS", "GRP-02"));
        String workflow = workflow("OWNERS", "GRP-02", "Workflow-0003");

        assertThat(harness.git("log", "-1", "--format=%(trailers:key=Server-State,valueonly)")
            .strip()).isEqualTo("dev");
        assertThat(harness.history.lastServerState(GROUP, workflow)).contains(
            harness.git("rev-parse", "HEAD").strip());
    }

    @Test
    void localChangesCarryNoServerState() throws IOException {
        harness.service().export(diagramGroups("OWNERS", "GRP-02"));
        String exported = harness.git("rev-parse", "HEAD").strip();
        String workflow = workflow("OWNERS", "GRP-02", "Workflow-0003");
        Files.writeString(root.resolve(workflow), Files.readString(root.resolve(workflow))
            + "<!-- edited -->\n");
        harness.artifacts.exports.put("GRP-02", ArtifactFixtures.bytes("grp-b.zip"));

        harness.service().export(diagramGroups("OWNERS", "GRP-02"));

        assertThat(harness.log()).hasSize(3).element(1).asString().startsWith("local changes");
        assertThat(harness.git("log", "-1", "--skip=1",
            "--format=%(trailers:key=Server-State,valueonly)").strip()).isEmpty();
        assertThat(harness.history.lastServerState(GROUP, workflow))
            .contains(harness.git("rev-parse", "HEAD").strip())
            .isNotEqualTo(Optional.of(exported));
    }

    @Test
    void anUnchangedReExportRecordsNothing() {
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        SortedMap<String, String> before = harness.snapshot();

        ExportResult second = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(second.unchanged()).isTrue();
        assertThat(second.export()).isEmpty();
        assertThat(harness.log()).hasSize(1);
        assertThat(harness.snapshot()).isEqualTo(before);
    }

    @Test
    void aReExportWhoseCheckinCommentsGrewIsUnchanged() {
        // live acceptance (SC-001): INUBIT appends ### segments to every workflow's check-in
        // comment on each export, also before the @@@Deploying User suffix
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        SortedMap<String, String> before = harness.snapshot();
        harness.artifacts.exports.put("GRP-01", ExportHarness.grownComments());

        ExportResult second = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(second.unchanged()).as(() -> String.valueOf(second.export())).isTrue();
        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void personWrittenCommentSegmentsStayInTheFilesAndTheirChangesAreRecorded() throws Exception {
        // review I1 (FR-014): ### also separates segments written by a person
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        service.export(diagramGroups("OWNERS", "GRP-02"));
        try (var files = Files.walk(root.resolve("dev"))) {
            // every fixture workflow but Workflow-0004 (comment "###") has "JD: Fixture"
            assertThat(files.filter(f -> f.toString().contains("/workflows/")
                && f.toString().endsWith(".xml") && !f.endsWith("Workflow-0004.xml")).toList())
                .hasSize(5).allSatisfy(file ->
                    assertThat(Files.readString(file)).contains("JD: Fixture"));
        }
        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "workflow/workflow.xml", xml -> xml.replace("JD: Fixture###",
                "JD: Fixture edited###")));

        ExportResult edited = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(edited.export()).hasValueSatisfying(entry -> assertThat(entry.changes())
            .contains(new PathChange(workflow("jdoe", "GRP-01", "Workflow-0001"),
                PathChange.Kind.MODIFIED)));
    }

    @Test
    void aChangedStylesheetIsExactlyOneModifiedPath() {
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        harness.artifacts.exports.put("GRP-01", ExportHarness.rewrite("grp-a.zip",
            "module/module-0001.xml", xml -> xml.replace("&lt;xsl:output method=\"xml\"",
                "&lt;xsl:output method=\"text\"")));

        ExportResult second = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(second.export()).hasValueSatisfying(entry -> assertThat(entry.changes())
            .containsExactly(new PathChange(WorkspacePath.embedded(GROUP, "jdoe",
                "XSLT Converter", "Module-0001", "xslt.stylesheet", "xsl").toRelativePath()
                .toString(), PathChange.Kind.MODIFIED)));
    }

    @Test
    void artifactsNoLongerExportedDisappearAndWarningsAreReported() {
        WorkspaceService service = harness.service();
        ExportResult first = service.export(diagramGroups("jdoe", "GRP-01"));
        assertThat(first.warnings())
            .containsExactly("Workflow Workflow-0002 is in edit mode by jdoe (CheckoutUser)");
        String module9 = WorkspacePath.moduleIndex(GROUP, "jdoe", "Assign", "Module-0009")
            .toRelativePath().toString();
        assertThat(root.resolve(module9)).isRegularFile();
        harness.artifacts.exports.put("GRP-01", ExportHarness.withoutModule0009());

        ExportResult second = service.export(diagramGroups("jdoe", "GRP-01"));

        assertThat(root.resolve(module9)).doesNotExist();
        assertThat(second.export().orElseThrow().changes())
            .contains(new PathChange(module9, PathChange.Kind.DELETED));
        assertThat(second.warnings()).contains("1 used module(s) are not part of the export"
            + " (e.g. modules of another owner) and were not written");
    }

    @Test
    void aModuleIsExportedWithItsPluginTypeLookedUp() {
        harness.artifacts.exports.put("Module-0023", ArtifactFixtures.bytes("module-one.zip"));
        harness.pluginTypes.put("Module-0023", "XSLT Converter");

        ExportResult result = harness.service().export(modules("OWNERS",
            new ModuleRef("Module-0023", Optional.empty())));

        assertThat(harness.artifacts.calls).containsExactly("listModules OWNERS",
            "module OWNERS XSLT Converter Module-0023");
        assertThat(result.export().orElseThrow().message())
            .startsWith("export dev/node1: module Module-0023 (");
        assertThat(root.resolve(WorkspacePath.module(GROUP, "OWNERS", "XSLT Converter",
            "Module-0023").toRelativePath())).isRegularFile();
    }

    @Test
    void aModuleMissingFromTheModuleListIsNotFound() {
        assertThatThrownBy(() -> harness.service().export(modules("OWNERS",
            new ModuleRef("Module-0404", Optional.empty()))))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().message()).contains("Module-0404", "OWNERS");
            });
        assertThat(harness.artifacts.calls).containsExactly("listModules OWNERS");
    }

    @Test
    void aSecondExportIsRefusedAtOnceWhileTheWorkspaceIsLocked() {
        harness.history.init();
        try (WorkspaceLock held = WorkspaceLock.acquire(root)) {
            assertThatThrownBy(() -> harness.service().export(diagramGroups("jdoe", "GRP-01")))
                .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(
                    e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED));
        }
        assertThat(harness.artifacts.calls).isEmpty();
    }

    @Test
    void aFailingExportOrAnUnreadableArchiveLeavesTheWorkspaceUnchanged() {
        WorkspaceService service = harness.service();
        service.export(diagramGroups("jdoe", "GRP-01"));
        SortedMap<String, String> before = harness.snapshot();

        assertThatThrownBy(() -> service.export(diagramGroups("jdoe", "GRP-01", "GRP-404")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.NOT_FOUND));
        harness.artifacts.exports.put("GRP-01", "not a zip".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.export(diagramGroups("jdoe", "GRP-01")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
            });

        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void aFailureWhileWritingRestoresTheWorkspaceFromTheHistory() {
        harness.service().export(diagramGroups("jdoe", "GRP-01"));
        SortedMap<String, String> before = harness.snapshot();
        harness.artifacts.exports.put("GRP-01", ExportHarness.withoutModule0009());
        ArchiveCodec real = new ArchiveCodec();
        ArchiveCodecPort failing = (group, owner, archives) -> {
            PreparedExport prepared = real.prepare(group, owner, archives);
            return new PreparedExport() {
                @Override
                public int secretsReplaced() {
                    return prepared.secretsReplaced();
                }

                @Override
                public List<String> warnings() {
                    return prepared.warnings();
                }

                @Override
                public List<String> scope() {
                    return prepared.scope();
                }

                @Override
                public java.util.SortedMap<String, byte[]> files() {
                    return prepared.files();
                }

                @Override
                public java.util.Map<String, String> inEditMode() {
                    return prepared.inEditMode();
                }

                @Override
                public void writeTo(Path workspace) {
                    prepared.writeTo(workspace);
                    throw new UncheckedIOException(new IOException("No space left on device"));
                }
            };
        };

        assertThatThrownBy(() -> harness.service(failing).export(diagramGroups("jdoe",
            "GRP-01"))).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("restored");
            });

        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.log()).hasSize(1);
        assertThat(harness.history.status()).isEmpty();
    }

    @Test
    void aFailureWhileRecordingTheExportAlsoRestoresTheWorkspace() {
        // review M1: steps 4 and 5 are undone together; otherwise the next export would commit
        // the server state as "local changes"
        harness.service().export(diagramGroups("jdoe", "GRP-01"));
        SortedMap<String, String> before = harness.snapshot();
        harness.artifacts.exports.put("GRP-01", ExportHarness.withoutModule0009());
        VersionHistoryPort failingCommit = new VersionHistoryPort() {
            @Override
            public void init() {
                harness.history.init();
            }

            @Override
            public List<PathChange> status() {
                return harness.history.status();
            }

            @Override
            public Optional<HistoryEntry> commitAll(String message,
                Map<String, String> trailers) {
                if (message.startsWith("export ")) {
                    throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                        "git commit failed", "fake", "fake"));
                }
                return harness.history.commitAll(message, trailers);
            }

            @Override
            public void restore(Path subtree) {
                harness.history.restore(subtree);
            }

            @Override
            public Optional<String> lastServerState(GroupId group, String path) {
                return harness.history.lastServerState(group, path);
            }

            @Override
            public Optional<byte[]> show(String commit, String path) {
                return harness.history.show(commit, path);
            }

            @Override
            public List<PathChange> changedPaths(String fromCommit, String subtree) {
                return harness.history.changedPaths(fromCommit, subtree);
            }

            @Override
            public List<LocalChange> localChanges(GroupId group, String subtree) {
                return harness.history.localChanges(group, subtree);
            }
        };
        WorkspaceService service = new WorkspaceService(root, failingCommit, new ArchiveCodec(),
            node -> harness.artifacts, node -> {
                throw new AssertionError("not used");
            });

        assertThatThrownBy(() -> service.export(diagramGroups("jdoe", "GRP-01")))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error().code())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED));

        assertThat(harness.snapshot()).isEqualTo(before);
        assertThat(harness.history.status()).isEmpty();
        assertThat(harness.log()).hasSize(1);
    }

    @Test
    void aLargeDiagramGroupIsExportedWithinOneMinute() throws IOException {
        // SC-006: 1 diagram group, 20 workflows, 100 modules; StartCLI answers at once
        harness.artifacts.exports.put("GRP-01", ExportHarness.large(20, 100));
        long start = System.nanoTime();

        ExportResult result = harness.service().export(diagramGroups("jdoe", "GRP-01"));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
        try (var workflows = Files.list(root.resolve(workflow("jdoe", "GRP-01", "x"))
            .getParent())) {
            assertThat(workflows.count()).isEqualTo(20);
        }
        Path modules = root.resolve(WorkspacePath.moduleIndex(GROUP, "jdoe", "Assign", "x")
            .toRelativePath()).getParent().getParent().getParent();
        try (var pluginTypes = Files.list(modules)) {
            assertThat(pluginTypes.mapToLong(type -> {
                try (var names = Files.list(type)) {
                    return names.count();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum()).isEqualTo(100);
        }
        assertThat(result.export()).isPresent();
    }
}
