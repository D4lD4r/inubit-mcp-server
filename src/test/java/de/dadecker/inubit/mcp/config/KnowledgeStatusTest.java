package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 019 (T011, FR-003, research D-3): the {@code Knowledge:} line of {@code --check-config};
 * no note name, title or content, only the path, the state, recording and the count.
 */
class KnowledgeStatusTest {

    @TempDir
    Path root;

    @Test
    void theLineNamesPathStateRecordingAndCount() {
        Path path = root.resolve("knowledge");

        assertThat(new KnowledgeStatus(path, KnowledgeStatus.State.ABSENT, Optional.empty(), true,
            0).line()).isEqualTo("Knowledge: " + path + " (absent), recording on, 0 notes");
        assertThat(new KnowledgeStatus(path, KnowledgeStatus.State.OK, Optional.empty(), false,
            12).line()).isEqualTo("Knowledge: " + path + " (ok), recording off, 12 notes");
        assertThat(new KnowledgeStatus(path, KnowledgeStatus.State.PROBLEM,
            Optional.of("not readable"), true, 0).line())
            .isEqualTo("Knowledge: " + path + " (not readable), recording on, 0 notes");
    }

    @Test
    void aProblemIsGivenExactlyForTheProblemState() {
        Path path = root.resolve("knowledge");

        assertThatThrownBy(() -> new KnowledgeStatus(path, KnowledgeStatus.State.PROBLEM,
            Optional.empty(), true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeStatus(path, KnowledgeStatus.State.OK,
            Optional.of("x"), true, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeStatus(path, KnowledgeStatus.State.OK,
            Optional.empty(), true, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void probeTellsAbsentOkAndNotADirectory() throws IOException {
        Path knowledge = root.resolve("knowledge");

        assertThat(KnowledgeStatus.probe(new KnowledgeConfig(knowledge, true), 3))
            .isEqualTo(new KnowledgeStatus(knowledge, KnowledgeStatus.State.ABSENT,
                Optional.empty(), true, 0));

        Files.createDirectory(knowledge);
        assertThat(KnowledgeStatus.probe(new KnowledgeConfig(knowledge, false), 3))
            .isEqualTo(new KnowledgeStatus(knowledge, KnowledgeStatus.State.OK,
                Optional.empty(), false, 3));

        Path file = Files.createFile(root.resolve("file"));
        assertThat(KnowledgeStatus.probe(new KnowledgeConfig(file, true), 3))
            .isEqualTo(new KnowledgeStatus(file, KnowledgeStatus.State.PROBLEM,
                Optional.of("not a directory"), true, 0));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void probeTellsNotReadable() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads everything");
        Path knowledge = Files.createDirectory(root.resolve("knowledge"));
        Files.setPosixFilePermissions(knowledge, PosixFilePermissions.fromString("-wx------"));
        try {
            assertThat(KnowledgeStatus.probe(new KnowledgeConfig(knowledge, true), 3))
                .isEqualTo(new KnowledgeStatus(knowledge, KnowledgeStatus.State.PROBLEM,
                    Optional.of("not readable"), true, 0));
        } finally {
            Files.setPosixFilePermissions(knowledge, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aRelativeDirectoryIsNotProbed() {
        Path relative = Path.of("notes");

        assertThat(KnowledgeStatus.probe(new KnowledgeConfig(relative, true), 3))
            .isEqualTo(new KnowledgeStatus(relative, KnowledgeStatus.State.PROBLEM,
                Optional.of("not usable: see the errors"), true, 0));
    }
}
