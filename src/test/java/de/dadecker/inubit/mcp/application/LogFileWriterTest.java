package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.application.LogFileWriter.FileOps;
import de.dadecker.inubit.mcp.application.LogFileWriter.Placed;
import de.dadecker.inubit.mcp.application.TargetFolderRules.CheckedFolder;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 021 T019 (research D-7 step 4, FR-007, FR-008, FR-011, SC-004): placing the downloaded
 * log in the target folder — a private {@code .part} copy, the name claimed with a hard link (or
 * an empty placeholder and an atomic move), never replacing a file and never leaving a file of a
 * failed call.
 */
class LogFileWriterTest {

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final String BASE = "prod-n1-trace-20261112-101500";
    private static final byte[] CONTENT =
        "line 1\nline 2 äöü\n".getBytes(StandardCharsets.UTF_8);

    @TempDir(factory = GitFreeTempDir.class)
    Path tmp;

    private final RecordingOps ops = new RecordingOps();
    private final LogFileWriter writer = new LogFileWriter(ops);

    @Test
    void placesTheFileAsBaseLogThroughAHiddenPartFileAndAHardLink() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        assertThat(placed).isEqualTo(new Placed(folder.resolve(BASE + ".log"), CONTENT.length));
        assertThat(placed.file()).hasBinaryContent(CONTENT);
        assertThat(list(folder)).containsExactly(BASE + ".log");
        Path part = ops.created.get(0);
        assertThat(part.getParent()).isEqualTo(folder);
        assertThat(part.getFileName().toString()).matches("\\." + BASE + "\\.[0-9a-f]+\\.part");
        assertThat(ops.calls).containsExactly(
            "createNew " + part,
            "copy",
            "force",
            "link " + placed.file() + " -> " + part,
            "delete " + part);
    }

    @Test
    void anExistingNameGetsTheSuffix2AndStaysUnchanged() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Files.writeString(folder.resolve(BASE + ".log"), "older download");

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        assertThat(placed.file()).isEqualTo(folder.resolve(BASE + "-2.log"))
            .hasBinaryContent(CONTENT);
        assertThat(folder.resolve(BASE + ".log")).hasContent("older download");
        assertThat(list(folder)).containsExactlyInAnyOrder(BASE + ".log", BASE + "-2.log");
    }

    @Test
    void aNameCreatedByAnotherCallRightBeforeTheLinkIsNotReplaced() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            if (call == 1) {
                Files.writeString(link, "the other call's file");
            }
        };

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        assertThat(placed.file()).isEqualTo(folder.resolve(BASE + "-2.log"));
        assertThat(folder.resolve(BASE + ".log")).hasContent("the other call's file");
        assertThat(list(folder)).containsExactlyInAnyOrder(BASE + ".log", BASE + "-2.log");
    }

    @Test
    void allNamesUpToSuffix999TakenIsInvalidInputAndLeavesNoFileOfTheCall() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        List<String> taken = new ArrayList<>();
        taken.add(BASE + ".log");
        for (int n = 2; n <= 999; n++) {
            taken.add(BASE + "-" + n + ".log");
        }
        for (String name : taken) {
            Files.writeString(folder.resolve(name), name);
        }

        assertThatThrownBy(() -> writer.place(checked(folder), BASE, source(CONTENT)))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).containsIgnoringCase("no free file name");
            });
        assertThat(list(folder)).containsExactlyInAnyOrderElementsOf(taken);
        assertThat(folder.resolve(BASE + "-999.log")).hasContent(BASE + "-999.log");
        assertThat(ops.calls).doesNotContain("link " + folder.resolve(BASE + "-1000.log")
            + " -> " + ops.created.get(0));
    }

    @ParameterizedTest(name = "hard links available: {0}")
    @ValueSource(booleans = {true, false})
    void twentyConcurrentPlacementsOfTheSameBaseGetTwentyDistinctNamesAndReplaceNothing(
        boolean hardLinks) throws Exception {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Files.writeString(folder.resolve(BASE + ".log"), "existing");
        FileOps shared = hardLinks ? FileOps.system() : new NoHardLinks();
        LogFileWriter concurrent = new LogFileWriter(shared);
        Map<Integer, Path> sources = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            sources.put(i, source("src-" + i, ("download " + i + "\n").getBytes(
                StandardCharsets.UTF_8)));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<Placed>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Path src = sources.get(i);
                Callable<Placed> call = () -> {
                    start.await();
                    return concurrent.place(checked(folder), BASE, src);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Placed placed = futures.get(i).get();
                files.add(placed.file());
                assertThat(placed.file()).hasBinaryContent(Files.readAllBytes(sources.get(i)));
            }
            assertThat(files).doesNotHaveDuplicates().hasSize(20)
                .doesNotContain(folder.resolve(BASE + ".log"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(folder.resolve(BASE + ".log")).hasContent("existing");
        assertThat(list(folder)).hasSize(21).noneMatch(name -> name.endsWith(".part"));
    }

    @Test
    void theFileIsOwnerOnlyAndACreatedFolderAndItsParentAreOwnerOnly() throws IOException {
        Path folder = tmp.resolve("a").resolve("b");

        Placed placed = writer.place(new CheckedFolder(folder, false, tmp, List.of()), BASE,
            source(CONTENT));

        assertThat(placed.file()).hasBinaryContent(CONTENT);
        if (POSIX) {
            assertThat(permissions(placed.file())).isEqualTo("rw-------");
            assertThat(permissions(tmp.resolve("a"))).isEqualTo("rwx------");
            assertThat(permissions(folder)).isEqualTo("rwx------");
        }
    }

    @Test
    void thePermissionsOfAnExistingFolderAreNotChanged() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        if (POSIX) {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-x---"));
        }

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        assertThat(placed.file()).hasBinaryContent(CONTENT);
        if (POSIX) {
            assertThat(permissions(folder)).isEqualTo("rwxr-x---");
            assertThat(permissions(placed.file())).isEqualTo("rw-------");
        }
    }

    @ParameterizedTest(name = "failing {0}")
    @ValueSource(strings = {"copy", "force", "link", "link-runtime", "part-delete"})
    void anInjectedFailureLeavesNoFileOfTheCallAndTheCreatedFolderStays(String step)
        throws IOException {
        Path folder = tmp.resolve("new");
        IOException injected = new IOException("injected " + step);
        switch (step) {
            case "copy" -> ops.beforeCopy = (target, call) -> {
                Files.write(target, "partial".getBytes(StandardCharsets.UTF_8));
                throw injected;
            };
            case "force" -> ops.beforeForce = (target, call) -> {
                throw injected;
            };
            case "link" -> ops.beforeLink = (link, call) -> {
                throw injected;
            };
            case "link-runtime" -> ops.beforeLink = (link, call) -> {
                throw new IllegalStateException("injected");
            };
            case "part-delete" -> ops.beforeDelete = (file, call) -> {
                if (call == 1) {
                    throw injected;
                }
            };
            default -> throw new IllegalArgumentException(step);
        }

        assertThatThrownBy(() -> writer.place(new CheckedFolder(folder, false, tmp, List.of()),
            BASE, source(CONTENT)))
            .satisfies(e -> {
                if (!step.equals("link-runtime")) {
                    assertThat(e).isSameAs(injected);
                }
            });
        assertThat(folder).isDirectory();
        assertThat(list(folder)).isEmpty();
    }

    @Test
    void aFailingMoveInThePlaceholderFallbackLeavesNoFileOfTheCall() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            throw new FileSystemException(link.toString(), null, "Operation not supported");
        };
        IOException injected = new IOException("injected move");
        ops.beforeMove = (target, call) -> {
            throw injected;
        };

        assertThatThrownBy(() -> writer.place(checked(folder), BASE, source(CONTENT)))
            .isSameAs(injected);
        assertThat(list(folder)).isEmpty();
    }

    @Test
    void withoutHardLinksTheNameIsClaimedByAPlaceholderReplacedAtomicallyByThePart()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        ops.beforeLink = (link, call) -> {
            throw new FileSystemException(link.toString(), null, "Operation not supported");
        };

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        Path file = folder.resolve(BASE + ".log");
        Path part = ops.created.get(0);
        assertThat(placed).isEqualTo(new Placed(file, CONTENT.length));
        assertThat(file).hasBinaryContent(CONTENT);
        assertThat(list(folder)).containsExactly(BASE + ".log");
        assertThat(ops.calls).containsExactly(
            "createNew " + part,
            "copy",
            "force",
            "link " + file + " -> " + part,
            "createNew " + file,
            "move " + part + " -> " + file);
        if (POSIX) {
            assertThat(permissions(file)).isEqualTo("rw-------");
        }
    }

    @Test
    void withoutHardLinksAnExistingNameGetsTheSuffix2AndStaysUnchanged() throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Files.writeString(folder.resolve(BASE + ".log"), "older download");
        ops.beforeLink = (link, call) -> {
            throw new UnsupportedOperationException("no hard links");
        };

        Placed placed = writer.place(checked(folder), BASE, source(CONTENT));

        assertThat(placed.file()).isEqualTo(folder.resolve(BASE + "-2.log"))
            .hasBinaryContent(CONTENT);
        assertThat(folder.resolve(BASE + ".log")).hasContent("older download");
        assertThat(list(folder)).containsExactlyInAnyOrder(BASE + ".log", BASE + "-2.log");
    }

    @Test
    void neitherHardLinksNorAtomicMovesIsInvalidInputAndLeavesNoFileOfTheCall()
        throws IOException {
        Path folder = Files.createDirectory(tmp.resolve("out"));
        Files.writeString(folder.resolve("other.txt"), "foreign");
        ops.beforeLink = (link, call) -> {
            throw new FileSystemException(link.toString(), null, "Operation not supported");
        };
        ops.beforeMove = (target, call) -> {
            throw new AtomicMoveNotSupportedException(null, target.toString(), "cross device");
        };

        assertThatThrownBy(() -> writer.place(checked(folder), BASE, source(CONTENT)))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().message()).isEqualTo("The file system of the target folder"
                    + " supports neither hard links nor atomic renames; choose a local folder");
            });
        assertThat(list(folder)).containsExactly("other.txt");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"utf-8", "latin-1", "binary", "empty"})
    void theFileHoldsExactlyTheBytesOfTheSource(String kind) throws IOException {
        byte[] bytes = switch (kind) {
            case "utf-8" -> "Grüße € 😀\r\nzweite Zeile\n".getBytes(StandardCharsets.UTF_8);
            case "latin-1" -> "Grüße ½ ÿ\r\nzweite Zeile\n".getBytes(StandardCharsets.ISO_8859_1);
            case "binary" -> allByteValues();
            case "empty" -> new byte[0];
            default -> throw new IllegalArgumentException(kind);
        };
        Path folder = Files.createDirectory(tmp.resolve("out"));

        Placed placed = writer.place(checked(folder), BASE, source(bytes));

        assertThat(placed.size()).isEqualTo(bytes.length);
        assertThat(placed.file()).hasBinaryContent(bytes);
    }

    private static byte[] allByteValues() {
        byte[] bytes = new byte[3 * 256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private static CheckedFolder checked(Path folder) {
        return new CheckedFolder(folder, true, folder, List.of());
    }

    private Path source(byte[] content) throws IOException {
        return source("trace", content);
    }

    private Path source(String name, byte[] content) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("private"));
        return Files.write(dir.resolve(name + ".log"), content);
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static List<String> list(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).toList();
        }
    }

    /** The system operations, except that hard links are unsupported (FAT, exFAT). */
    private static final class NoHardLinks implements FileOps {

        private final FileOps real = FileOps.system();

        @Override
        public FileChannel createNew(Path file, FileAttribute<?>... attributes)
            throws IOException {
            return real.createNew(file, attributes);
        }

        @Override
        public long copy(Path source, FileChannel target) throws IOException {
            return real.copy(source, target);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            real.force(channel);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            throw new FileSystemException(link.toString(), null, "Operation not supported");
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            real.move(source, target);
        }

        @Override
        public void delete(Path file) throws IOException {
            real.delete(file);
        }

        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes)
            throws IOException {
            real.createDirectory(directory, attributes);
        }
    }

    /**
     * Records each call and delegates to the real file operations; the hooks run before the
     * real operation (with the 1-based number of that operation's call) to inject failures or
     * races. {@code created} lists the files created with {@code createNew}, in order.
     */
    private static final class RecordingOps implements FileOps {

        @FunctionalInterface
        interface Hook {
            void run(Path path, int call) throws IOException;
        }

        private final FileOps real = FileOps.system();
        final List<String> calls = new ArrayList<>();
        final List<Path> created = new ArrayList<>();
        private final Map<FileChannel, Path> channels = new HashMap<>();
        Hook beforeCopy = (target, call) -> { };
        Hook beforeForce = (target, call) -> { };
        Hook beforeLink = (link, call) -> { };
        Hook beforeMove = (target, call) -> { };
        Hook beforeDelete = (file, call) -> { };
        private int copies;
        private int forces;
        private int links;
        private int moves;
        private int deletes;

        @Override
        public FileChannel createNew(Path file, FileAttribute<?>... attributes)
            throws IOException {
            calls.add("createNew " + file);
            FileChannel channel = real.createNew(file, attributes);
            created.add(file);
            channels.put(channel, file);
            return channel;
        }

        @Override
        public long copy(Path source, FileChannel target) throws IOException {
            calls.add("copy");
            beforeCopy.run(channels.get(target), ++copies);
            return real.copy(source, target);
        }

        @Override
        public void force(FileChannel channel) throws IOException {
            calls.add("force");
            beforeForce.run(channels.get(channel), ++forces);
            real.force(channel);
        }

        @Override
        public void link(Path link, Path existing) throws IOException {
            calls.add("link " + link + " -> " + existing);
            beforeLink.run(link, ++links);
            real.link(link, existing);
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            calls.add("move " + source + " -> " + target);
            beforeMove.run(target, ++moves);
            real.move(source, target);
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
            real.createDirectory(directory, attributes);
        }
    }
}
