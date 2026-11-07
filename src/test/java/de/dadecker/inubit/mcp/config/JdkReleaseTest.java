package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link JdkRelease} (feature 017, research D-17). */
class JdkReleaseTest {

    private static final Path JDK = Path.of("/opt/jdk");

    private static Function<Path, Optional<String>> releaseFile(String content) {
        return path -> path.equals(JDK.resolve("release")) ? Optional.of(content)
            : Optional.empty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "JAVA_VERSION=\"17.0.9\"   | 17",
        "JAVA_VERSION=\"21\"       | 21",
        "JAVA_VERSION=\"21.0.2+13\" | 21",
        "JAVA_VERSION=\"11.0.21\"  | 11",
        "JAVA_VERSION=\"1.8.0_392\" | 8",
        "JAVA_VERSION=\"1.7.0_80\" | 7",
        "JAVA_VERSION=17.0.1      | 17"})
    void readsTheMajorOfJavaVersion(String line, int major) {
        String release = "IMPLEMENTOR=\"Eclipse Adoptium\"\n" + line.strip()
            + "\nJAVA_VERSION_DATE=\"2023-10-17\"\n";

        assertThat(JdkRelease.major(JDK, releaseFile(release))).hasValue(major);
    }

    @Test
    void readsTheReleaseFileBelowTheJavaHome() {
        List<Path> read = new ArrayList<>();

        JdkRelease.major(JDK, path -> {
            read.add(path);
            return Optional.empty();
        });

        assertThat(read).containsExactly(JDK.resolve("release"));
    }

    @Test
    void anUnreadableReleaseFileIsUnknown() {
        assertThat(JdkRelease.major(JDK, path -> Optional.empty())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "IMPLEMENTOR=\"Eclipse Adoptium\"",
        "JAVA_VERSION=\"\"",
        "JAVA_VERSION=\"abc\"",
        "JAVA_VERSION_DATE=\"2023-10-17\"",
        "JAVA_VERSION=\"1\"",
        "JAVA_VERSION=\"99999999999999\"",
        "# JAVA_VERSION=\"17\""})
    void aReleaseFileWithoutAReadableJavaVersionIsUnknown(String content) {
        assertThat(JdkRelease.major(JDK, releaseFile(content))).isEmpty();
    }

    @Test
    void theFileReaderReadsAnExistingFileAndNothingElse(@TempDir Path directory)
        throws IOException {
        Files.writeString(directory.resolve("release"), "JAVA_VERSION=\"21.0.4\"\n");

        assertThat(JdkRelease.major(directory, JdkRelease::read)).isEqualTo(OptionalInt.of(21));
        assertThat(JdkRelease.major(directory.resolve("missing"), JdkRelease::read)).isEmpty();
        assertThat(JdkRelease.read(directory)).as("a directory").isEmpty();
    }
}
