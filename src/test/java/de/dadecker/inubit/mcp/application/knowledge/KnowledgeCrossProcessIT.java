package de.dadecker.inubit.mcp.application.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.application.knowledge.Record.Mode;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 019-T033 (US2 AS9, research D-10/D-11): two JVMs append to one note. The second JVM is started
 * with the test classpath and runs {@link #main}; while this process holds the knowledge lock it
 * waits and the note is unchanged, then both append at the same time. Both sections are kept and
 * the history holds both previous versions. Runs in Failsafe; the in-process variant with threads
 * is {@code KnowledgeLockTest}.
 */
@Timeout(120)
class KnowledgeCrossProcessIT {

    private static final String READY = "knowledge-cross-process: ready";
    private static final String DONE = "knowledge-cross-process: recorded";
    private static final String NOTE = "shared";
    private static final String CHILD_SECTION = "From the second process.";
    private static final String OWN_SECTION = "From this process.";

    @TempDir
    Path temp;

    private static KnowledgeService service(Path root) {
        Clock clock = Clock.systemUTC();
        return new KnowledgeService(new KnowledgeDirectory(root, PrivateFiles.forHost(), clock),
            FakeConfiguredSecrets.held(), new NoteRefs(new SecureRandom()),
            Duration.ofSeconds(10), new KnowledgeService.Writing(PrivateFiles.forHost(), clock,
                new KnowledgeAudit.Dependencies(record -> { }, clock, "acme",
                    UUID::randomUUID)));
    }

    private static Record append(String section) {
        return new Record(Optional.of(NOTE), Optional.empty(), Mode.APPEND, Optional.empty(),
            Optional.empty(), section, Optional.empty(),
            Optional.of("cross-process"), Optional.of("cross-process/1"));
    }

    /** The second JVM: appends {@code args[1]} to the note in the knowledge {@code args[0]}. */
    public static void main(String[] args) {
        KnowledgeService service = service(Path.of(args[0]));
        System.out.println(READY);
        System.out.flush();
        Recorded recorded = service.record(append(args[1]));
        System.out.println(DONE + " " + recorded.sizeBefore());
        System.out.flush();
    }

    @Test
    void theAppendsOfTwoProcessesAreBothKeptWithBothPreviousVersions() throws Exception {
        Path root = Files.createDirectories(temp.resolve("knowledge")).toRealPath();
        KnowledgeService service = service(root);
        service.record(new Record(Optional.of(NOTE), Optional.empty(), Mode.CREATE,
            Optional.of("Shared"), Optional.empty(), "Start.", Optional.empty(),
            Optional.of("cross-process"), Optional.of("cross-process/1")));
        Path note = root.resolve(NOTE + ".md");
        String created = Files.readString(note);
        Process child;
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        List<String> output = new ArrayList<>();

        try (KnowledgeLock.Held held = new KnowledgeLock(root, PrivateFiles.forHost())
            .acquire(Duration.ofSeconds(5))) {
            child = start(root, temp.resolve("child-stderr.log"));
            Thread.ofVirtual().start(() -> forward(child, lines));
            awaitLine(lines, output, READY, child);
            Thread.sleep(1_000);

            assertThat(child.isAlive()).as("the second process waits for the lock").isTrue();
            assertThat(Files.readString(note)).isEqualTo(created);
        }
        service.record(append(OWN_SECTION));
        awaitLine(lines, output, DONE, child);

        assertThat(child.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).as(String.join("\n", output)).isZero();
        String text = Files.readString(note);
        assertThat(text).contains("Start.\n\n", "\n\n" + CHILD_SECTION, "\n\n" + OWN_SECTION);
        List<String> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(root.resolve(KnowledgeHistory.DIRECTORY)
            .resolve(NOTE + ".md"))) {
            for (Path file : files.sorted().toList()) {
                versions.add(Files.readString(file));
            }
        }
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).isEqualTo(created);
        assertThat(versions.get(1)).contains("Start.")
            .containsAnyOf(CHILD_SECTION, OWN_SECTION)
            .satisfies(second -> assertThat(second.contains(CHILD_SECTION)
                && second.contains(OWN_SECTION)).isFalse());
    }

    private static Process start(Path root, Path stderr) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(),
            "-cp", System.getProperty("java.class.path"),
            "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
            KnowledgeCrossProcessIT.class.getName(), root.toString(), CHILD_SECTION)
            .redirectError(stderr.toFile());
        return builder.start();
    }

    private static void forward(Process child, BlockingQueue<String> lines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            lines.add("stdout failed: " + e);
        }
    }

    private static void awaitLine(BlockingQueue<String> lines, List<String> output, String prefix,
        Process child) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line != null) {
                output.add(line);
                if (line.startsWith(prefix)) {
                    return;
                }
            } else if (!child.isAlive() && lines.isEmpty()) {
                break;
            }
        }
        throw new AssertionError("no line '" + prefix + "' from the second process (exit "
            + (child.isAlive() ? "-" : child.exitValue()) + "):\n" + String.join("\n", output));
    }
}
