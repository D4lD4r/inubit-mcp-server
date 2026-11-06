package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Archive;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.FileOps;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Result;
import de.dadecker.inubit.mcp.application.ArchiveFolderWriter.Written;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
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
 * Feature 015 T009 and T019 (research R-11, FR-009, FR-010, FR-011): placing archives in the
 * target folder — the success path, and the failures: each stops the writes with the archive in
 * {@code failed}, the rest in {@code notWritten}, and never leaves a partial or replaced file.
 */
class ArchiveFolderWriterTest {

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir(factory = GitFreeTempDir.class)
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
        ops.beforeCreateTemporary = (path, call) -> Files.write(existing, foreign);

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

    // T019: write failures (research R-11, FR-009, FR-011)

    @Test
    void aFullDiskOnTheSecondOfThreeArchivesStopsTheWritesWithoutAPartialFile()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeWrite = (channel, content, call) -> {
            if (call == 2) {
                channel.write(ByteBuffer.wrap(content, 0, content.length / 2));
                throw new IOException("No space left on device");
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.file()).isEqualTo("Workflow-0002.diagram.zip");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("No space left on device").doesNotContain("archive bytes of");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(result.warnings()).isEmpty();
        assertThat(folder.resolve("Workflow-0002.diagram.zip")).doesNotExist();
        assertThat(folder.resolve("Workflow-0003.diagram.zip")).doesNotExist();
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
    }

    @Test
    void aNameThatAppearsAfterTheCollisionCheckStopsTheWritesAndStaysUnchanged()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        byte[] foreign = "somebody else's file".getBytes(StandardCharsets.UTF_8);
        ops.beforeLink = (link, call) -> {
            if (call == 2) {
                Files.write(link, foreign);
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertThat(folder.resolve("Workflow-0002.diagram.zip")).hasBinaryContent(foreign);
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("'Workflow-0002.diagram.zip'")
                .contains("appeared in the target folder after the collision check")
                .doesNotContain("archive bytes of");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(list(folder))
            .containsExactlyInAnyOrder("Workflow-0001.diagram.zip", "Workflow-0002.diagram.zip");
    }

    @Test
    void aClashOfTheRandomTemporaryNameIsNotReportedAsAnAppearedArchiveName() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        byte[] foreign = "a foreign part file".getBytes(StandardCharsets.UTF_8);
        ops.beforeCreateTemporary = (file, call) -> Files.write(file, foreign);

        Result result = writer.write(folder, List.of(archive("Workflow-0001"),
            archive("Workflow-0002")));

        Path clash = ops.temporaries.get(0);
        assertThat(clash).hasBinaryContent(foreign);
        assertThat(folder.resolve("Workflow-0001.diagram.zip")).doesNotExist();
        assertThat(ops.calls).noneMatch(call -> call.startsWith("link ")
            || call.startsWith("delete "));
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0001.diagram.zip")
                .doesNotContain("appeared").doesNotContain("collision check");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0002");
    }

    @Test
    void aFailingCloseOfTheTemporaryFileDeletesItAndStopsTheWrites() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.failingClose = true;

        Result result = writer.write(folder, List.of(archive("Workflow-0001"),
            archive("Workflow-0002")));

        assertThat(ops.calls).contains("delete " + ops.temporaries.get(0))
            .noneMatch(call -> call.startsWith("link "));
        assertThat(list(folder)).isEmpty();
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0001.diagram.zip")
                .contains("Disk quota exceeded");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0002");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void hardLinksThatAreUnsupportedFallBackToCreateNewOnTheFinalName() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        Path temporary = ops.temporaries.get(0);
        Path target = folder.resolve("Workflow-0001.diagram.zip");
        assertThat(ops.calls).containsExactly(
            "createTemporary " + temporary,
            "writeAndForce " + content("Workflow-0001").length,
            "link " + target + " -> " + temporary,
            "delete " + temporary,
            "createTemporary " + target,
            "writeAndForce " + content("Workflow-0001").length);
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
        assertThat(result.written()).containsExactly(new Written("Workflow-0001",
            "Workflow-0001.diagram.zip", content("Workflow-0001").length));
        assertThat(result.failed()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aFileSystemWithoutHardLinksFallsBackToCreateNewOnTheFinalName() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            throw new FileSystemException(link.toString(), null, "Operation not permitted");
        };

        Result result = writer.write(folder, threeArchives());

        for (String workflow : List.of("Workflow-0001", "Workflow-0002", "Workflow-0003")) {
            assertPlaced(folder, workflow + ".diagram.zip", workflow);
        }
        assertThat(list(folder)).hasSize(3);
        assertThat(result.written()).hasSize(3);
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
    }

    @Test
    void aFailingFallbackWriteDeletesTheFileTheCallCreatedAndLeavesOthersUntouched()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        byte[] other = "an unrelated file".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("notes.txt"), other);
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };
        ops.beforeWrite = (channel, content, call) -> {
            if (call == 4) {
                channel.write(ByteBuffer.wrap(content, 0, content.length / 2));
                throw new IOException("No space left on device");
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(folder.resolve("notes.txt")).hasBinaryContent(other);
        assertThat(list(folder))
            .containsExactlyInAnyOrder("Workflow-0001.diagram.zip", "notes.txt");
        assertThat(ops.calls).contains("delete " + folder.resolve("Workflow-0002.diagram.zip"));
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("No space left on device");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aNameThatAppearsBeforeTheFallbackWriteIsLeftUnchangedAndReported() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Path existing = folder.resolve("Workflow-0001.diagram.zip");
        byte[] foreign = "somebody else's file".getBytes(StandardCharsets.UTF_8);
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };
        ops.beforeCreateTemporary = (file, call) -> {
            if (file.equals(existing)) {
                Files.write(existing, foreign);
            }
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(existing).hasBinaryContent(foreign);
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
        assertThat(ops.calls).doesNotContain("delete " + existing);
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> assertThat(
            failed.message()).contains("'Workflow-0001.diagram.zip'")
            .contains("appeared in the target folder after the collision check"));
    }

    @Test
    void aTemporaryFileThatCannotBeDeletedCountsAsWrittenWithACallLevelWarning()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeDelete = (file, call) -> {
            throw new IOException("Device or resource busy");
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001"),
            archive("Workflow-0002")));

        Path leftover = ops.temporaries.get(0);
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertPlaced(folder, "Workflow-0002.diagram.zip", "Workflow-0002");
        assertThat(result.written()).extracting(Written::workflow)
            .containsExactly("Workflow-0001", "Workflow-0002");
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings().get(0)).contains(leftover.toString())
            .contains("could not be deleted").contains("clear text")
            .doesNotContain("archive bytes of");
        assertThat(result.warnings().get(1)).contains(ops.temporaries.get(1).toString());
    }

    @Test
    void aPartialFinalFileOfAFailedFallbackWriteThatCannotBeDeletedIsNamedInAWarning()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Path partial = folder.resolve("Workflow-0002.diagram.zip");
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };
        ops.beforeWrite = (channel, content, call) -> {
            if (call == 4) {
                channel.write(ByteBuffer.wrap(content, 0, content.length / 2));
                throw new IOException("No space left on device");
            }
        };
        ops.beforeDelete = (file, call) -> {
            if (file.equals(partial)) {
                throw new IOException("Device or resource busy");
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(ops.calls).contains("delete " + partial);
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("No space left on device");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
            .contains(partial.toString()).contains("could not be deleted")
            .contains("clear text").doesNotContain("archive bytes of"));
    }

    @Test
    void aTemporaryFileThatCannotBeDeletedBeforeTheFallbackIsNamedInAWarning()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };
        ops.beforeDelete = (file, call) -> {
            throw new IOException("Device or resource busy");
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        Path leftover = ops.temporaries.get(0);
        assertThat(leftover.getFileName().toString()).endsWith(".part");
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).isEmpty();
        assertThat(result.notWritten()).isEmpty();
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
            .contains(leftover.toString()).contains("could not be deleted")
            .contains("clear text").doesNotContain("archive bytes of"));
    }

    @Test
    void theTemporaryFileOfAFailedArchiveThatCannotBeDeletedIsNamedInAWarning()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeWrite = (channel, content, call) -> {
            if (call == 2) {
                channel.write(ByteBuffer.wrap(content, 0, content.length / 2));
                throw new IOException("No space left on device");
            }
        };
        ops.beforeDelete = (file, call) -> {
            if (file.getFileName().toString().startsWith(".Workflow-0002.diagram.zip.")) {
                throw new IOException("Device or resource busy");
            }
        };

        Result result = writer.write(folder, threeArchives());

        Path leftover = ops.temporaries.get(1);
        assertThat(leftover.getFileName().toString())
            .startsWith(".Workflow-0002.diagram.zip.").endsWith(".part");
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(folder.resolve("Workflow-0002.diagram.zip")).doesNotExist();
        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.message()).contains("No space left on device");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning)
            .contains(leftover.toString()).contains("could not be deleted")
            .contains("clear text").doesNotContain("archive bytes of"));
    }

    @Test
    void aTargetFolderThatCannotBeCreatedFailsTheFirstArchiveAndWritesNothing() {
        Path folder = tmp.resolve("a").resolve("out");
        ops.beforeCreateDirectory = (directory, call) -> {
            if (directory.equals(folder)) {
                throw new FileSystemException(directory.toString(), null, "Permission denied");
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertThat(folder).doesNotExist();
        assertThat(ops.calls).noneMatch(call -> call.startsWith("createTemporary"));
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.file()).isEqualTo("Workflow-0001.diagram.zip");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("target folder").contains("Permission denied");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0002", "Workflow-0003");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aFolderCreatedByTheCallStaysEmptyWhenTheFirstArchiveFails() throws IOException {
        Path folder = tmp.resolve("out");
        ops.beforeCreateTemporary = (file, call) -> {
            throw new FileSystemException(file.toString(), null, "Read-only file system");
        };

        Result result = writer.write(folder, threeArchives());

        assertThat(folder).isDirectory();
        assertThat(list(folder)).isEmpty();
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.message()).contains("Read-only file system");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0002", "Workflow-0003");
    }

    @Test
    void aDirectoryLevelThatAppearsConcurrentlyIsAcceptedAndKeepsItsPermissions()
        throws IOException {
        Path middle = tmp.resolve("a");
        Path folder = middle.resolve("out");
        ops.beforeCreateDirectory = (directory, call) -> {
            if (directory.equals(middle)) {
                Files.createDirectory(middle);
                if (POSIX) {
                    Files.setPosixFilePermissions(middle,
                        PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001")));

        assertThat(ops.calls).filteredOn(call -> call.startsWith("createDirectory"))
            .containsExactly("createDirectory " + middle, "createDirectory " + folder);
        if (POSIX) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(middle)))
                .isEqualTo("rwxr-xr-x");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
                .isEqualTo("rwx------");
        }
        assertPlaced(folder, "Workflow-0001.diagram.zip", "Workflow-0001");
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void aFileThatAppearsConcurrentlyInPlaceOfADirectoryLevelFailsTheFirstArchive()
        throws IOException {
        Path middle = tmp.resolve("a");
        ops.beforeCreateDirectory = (directory, call) -> {
            if (directory.equals(middle)) {
                Files.write(middle, new byte[] {1});
            }
        };

        Result result = writer.write(middle.resolve("out"), List.of(archive("Workflow-0001"),
            archive("Workflow-0002")));

        assertThat(middle).isRegularFile().hasBinaryContent(new byte[] {1});
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement()
            .satisfies(failed -> assertThat(failed.message()).contains("target folder"));
        assertThat(result.notWritten()).containsExactly("Workflow-0002");
    }

    @Test
    void aRuntimeExceptionBuildingAnArchiveIsReportedWithItsClassNameOnly() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        List<Archive> archives = List.of(archive("Workflow-0001"),
            new Archive("Workflow-0002", () -> {
                throw new IllegalStateException("detail of " + folder + " and archive bytes of");
            }),
            archive("Workflow-0003"));

        Result result = writer.write(folder, archives);

        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.file()).isEqualTo("Workflow-0002.diagram.zip");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("IllegalStateException").doesNotContain("detail of")
                .doesNotContain(folder.toString());
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
    }

    @Test
    void aToolErrorBuildingAnArchiveIsReportedAsInternalCarryingItsCodeAndMessage()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        List<Archive> archives = List.of(archive("Workflow-0001"),
            new Archive("Workflow-0002", () -> {
                throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                    "The export has no workflow document", "cause", "next step"));
            }),
            archive("Workflow-0003"));

        Result result = writer.write(folder, archives);

        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("UNEXPECTED_RESPONSE: The export has no workflow document");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
    }

    @Test
    void aRuntimeExceptionOfAFileOperationIsReportedWithItsClassNameOnly() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeWrite = (channel, content, call) -> {
            if (call == 2) {
                channel.write(ByteBuffer.wrap(content, 0, 3));
                throw new IllegalStateException("detail of " + folder);
            }
        };

        Result result = writer.write(folder, threeArchives());

        assertThat(result.written()).extracting(Written::workflow).containsExactly("Workflow-0001");
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0002");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("Workflow-0002.diagram.zip")
                .contains("IllegalStateException").doesNotContain("detail of");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0003");
        assertThat(list(folder)).containsExactly("Workflow-0001.diagram.zip");
    }

    @Test
    void aRuntimeExceptionCreatingTheTargetFolderFailsTheFirstArchive() {
        Path folder = tmp.resolve("out");
        ops.beforeCreateDirectory = (directory, call) -> {
            throw new SecurityException("detail of " + directory);
        };

        Result result = writer.write(folder, List.of(archive("Workflow-0001"),
            archive("Workflow-0002")));

        assertThat(folder).doesNotExist();
        assertThat(result.written()).isEmpty();
        assertThat(result.failed()).singleElement().satisfies(failed -> {
            assertThat(failed.workflow()).isEqualTo("Workflow-0001");
            assertThat(failed.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(failed.message()).contains("SecurityException")
                .doesNotContain("detail of");
        });
        assertThat(result.notWritten()).containsExactly("Workflow-0002");
    }

    private static List<Archive> threeArchives() {
        return List.of(archive("Workflow-0001"), archive("Workflow-0002"),
            archive("Workflow-0003"));
    }

    private static List<String> list(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).toList();
        }
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

    /**
     * Records each call and delegates to the real file operations; the hooks run before the
     * real operation (with the 1-based number of that operation's call) to inject failures or
     * races.
     */
    private static final class RecordingOps implements FileOps {

        @FunctionalInterface
        interface Hook {
            void run(Path path, int call) throws IOException;
        }

        @FunctionalInterface
        interface WriteHook {
            void run(FileChannel channel, byte[] content, int call) throws IOException;
        }

        private final FileOps real = FileOps.system();
        final List<String> calls = new ArrayList<>();
        final List<Path> temporaries = new ArrayList<>();
        Hook beforeCreateTemporary = (path, call) -> { };
        WriteHook beforeWrite = (channel, content, call) -> { };
        Hook beforeLink = (path, call) -> { };
        Hook beforeDelete = (path, call) -> { };
        Hook beforeCreateDirectory = (path, call) -> { };
        boolean failingClose;
        private int creates;
        private int writes;
        private int links;
        private int deletes;
        private int directories;

        @Override
        public FileChannel createTemporary(Path file, FileAttribute<?>... attributes)
            throws IOException {
            calls.add("createTemporary " + file);
            temporaries.add(file);
            beforeCreateTemporary.run(file, ++creates);
            FileChannel channel = real.createTemporary(file, attributes);
            return failingClose ? new FailingCloseChannel(channel) : channel;
        }

        @Override
        public void writeAndForce(FileChannel channel, byte[] content) throws IOException {
            calls.add("writeAndForce " + content.length);
            beforeWrite.run(channel, content, ++writes);
            real.writeAndForce(channel, content);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            calls.add("link " + link + " -> " + existing);
            beforeLink.run(link, ++links);
            real.link(link, existing);
        }

        @Override
        public void delete(Path file) throws IOException {
            calls.add("delete " + file);
            beforeDelete.run(file, ++deletes);
            real.delete(file);
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes)
            throws IOException {
            calls.add("createDirectory " + directory);
            beforeCreateDirectory.run(directory, ++directories);
            real.createDirectory(directory, attributes);
        }
    }

    /** A channel whose {@code close()} closes the real channel, then fails (NFS, quota). */
    private static final class FailingCloseChannel extends FileChannel {

        private final FileChannel delegate;

        FailingCloseChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
            throw new IOException("Disk quota exceeded");
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            delegate.force(metaData);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target)
            throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count)
            throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return delegate.write(src, position);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }
    }
}
