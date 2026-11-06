package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Archive;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.FileOps;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Result;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Written;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T009 (research R-11, FR-009, FR-010): placing archives in the target folder, the
 * success path and an archive name that appears between the collision check and the write.
 */
class ArchiveFolderWriterTest {

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir
    Path tmp;

    private final RecordingOps ops = new RecordingOps();
    private final ArchiveFolderWriter writer = new ArchiveFolderWriter(ops);

    @Test
    void createsAMissingFolderAndTwoMissingParentsRightBeforeTheFirstArchive() throws IOException {
        Path folder = tmp.resolve("a").resolve("b").resolve("c");

        Result result = writer.write(folder,
            List.of(archive("Workflow-0001"), archive("Order: In/Out?.")));

        assertThat(ops.calls).hasSizeGreaterThan(3);
        assertThat(ops.calls.subList(0, 3)).containsExactly(
            "createDirectory " + tmp.resolve("a"),
            "createDirectory " + tmp.resolve("a").resolve("b"),
            "createDirectory " + folder);
        assertThat(ops.calls.get(3)).startsWith("createTemporary " + folder);
        for (Path level : List.of(tmp.resolve("a"), tmp.resolve("a/b"), folder)) {
            assertThat(level).isDirectory();
            if (POSIX) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(level)))
                    .as(level.toString()).isEqualTo("rwx------");
            }
        }
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertPlaced(folder, "Order_ In_Out_.diagram.zip", "Order: In/Out?.");
        assertThat(result.written()).containsExactly(
            new Written("Workflow-0001", "Workflow-0001.diagram.zip",
                content("Workflow-0001").length),
            new Written("Order: In/Out?.", "Order_ In_Out_.diagram.zip",
                content("Order: In/Out?.").length));
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertNoTemporaryFileIn(folder);
    }

    @Test
    void writesThroughAHiddenTemporaryFileInTheFolderAndAHardLink() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));

        writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(ops.temporaries).hasSize(1);
        Path temporary = ops.temporaries.get(0);
        assertThat(temporary.getParent()).isEqualTo(folder);
        assertThat(temporary.getFileName().toString())
            .matches("\\.Workflow-0001\\.diagram\\.zip\\.[0-9a-f]+\\.part");
        assertThat(ops.calls).containsExactly(
            "createTemporary " + temporary,
            "writeAndForce " + content("Workflow-0001").length,
            "link " + folder.resolve("Workflow-0001.diagram.zip") + " -> " + temporary,
            "delete " + temporary);
        assertNoTemporaryFileIn(folder);
    }

    @Test
    void theTemporaryNameOfAFileNameOf254BytesStaysWithin255Bytes() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        String workflow = "ä".repeat(121);

        Result result = writer.write(folder, List.of(archive(workflow)));

        String temporary = ops.temporaries.get(0).getFileName().toString();
        assertThat(temporary.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        assertThat(temporary).startsWith(".ää").endsWith(".part");
        assertPlaced(folder, workflow + ".diagram.zip", workflow);
        assertThat(result.failed()).isEmpty();
        assertNoTemporaryFileIn(folder);
    }

    @Test
    void anExistingFolderKeepsItsPermissions() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        if (POSIX) {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-x---"));
        }

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(ops.calls).noneMatch(call -> call.startsWith("createDirectory"));
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
                .isEqualTo("rwxr-x---");
        }
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(result.written()).hasSize(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void anExistingParentKeepsItsPermissionsWhenOnlyTheFolderIsCreated() throws IOException {
        Path parent = Files.createDirectory(tmp.resolve("parent"));
        if (POSIX) {
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path folder = parent.resolve("out");

        writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(ops.calls).filteredOn(call -> call.startsWith("createDirectory"))
            .containsExactly("createDirectory " + folder);
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(parent)))
                .isEqualTo("rwxr-xr-x");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
                .isEqualTo("rwx------");
        }
    }

    @Test
    void aNameThatAppearsBetweenCheckAndWriteIsLeftUnchangedAndReported() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Path existing = folder.resolve("Workflow-0001.diagram.zip");
        byte[] foreign = "somebody else's file".getBytes(StandardCharsets.UTF_8);
        ops.beforeCreateTemporary = () -> Files.write(existing, foreign);

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(existing).hasBinaryContent(foreign);
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.file()).isEqualTo("Workflow-0001.diagram.zip");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0001.diagram.zip")
                .doesNotContain("archive bytes of");
        });
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertNoTemporaryFileIn(folder);
        assertThat(ops.calls).anyMatch(call -> call.startsWith("link "));
    }

    @Test
    void nothingToWriteCreatesNoFolder() {
        Path folder = tmp.resolve("a").resolve("out");

        Result result = writer.write(folder, List.of());

        assertThat(tmp.resolve("a")).doesNotExist();
        assertThat(ops.calls).isEmpty();
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void theProductionConstructorPlacesAnArchiveToo() {
        Path folder = tmp.resolve("out");

        Result result = new ArchiveFolderWriter().write(folder, List.of(archive("Workflow-0001")));

        assertThat(folder.resolve("Workflow-0001.diagram.zip"))
            .hasBinaryContent(content("Workflow-0001"));
        assertThat(result.written()).hasSize(1);
    }

    private static Archive archive(String workflow) {
        return new Archive(workflow, () -> content(workflow));
    }

    private static byte[] content(String workflow) {
        return ("archive bytes of " + workflow).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertPlaced(Path folder, String file, String workflow) throws IOException {
        Path placed = folder.resolve(file);
        assertThat(placed).isRegularFile().hasBinaryContent(content(workflow));
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(placed)))
                .as(file).isEqualTo("rw-------");
        }
    }

    private static void assertNoTemporaryFileIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".part"));
        }
    }

    /** Records each call and delegates to the real file operations. */
    private static final class RecordingOps implements FileOps {

        @FunctionalInterface
        interface Hook {
            void run() throws IOException;
        }

        private final FileOps real = FileOps.system();
        final List<String> calls = new ArrayList<>();
        final List<Path> temporaries = new ArrayList<>();
        Hook beforeCreateTemporary = () -> { };

        @Override
        public FileChannel createTemporary(Path file, FileAttribute<?>... attributes)
            throws IOException {
            beforeCreateTemporary.run();
            calls.add("createTemporary " + file);
            temporaries.add(file);
            return real.createTemporary(file, attributes);
        }

        @Override
        public void writeAndForce(FileChannel channel, byte[] content) throws IOException {
            calls.add("writeAndForce " + content.length);
            real.writeAndForce(channel, content);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            calls.add("link " + link + " -> " + existing);
            real.link(link, existing);
        }

        @Override
        public void delete(Path file) throws IOException {
            calls.add("delete " + file);
            real.delete(file);
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes)
            throws IOException {
            calls.add("createDirectory " + directory);
            real.createDirectory(directory, attributes);
        }
    }
}
