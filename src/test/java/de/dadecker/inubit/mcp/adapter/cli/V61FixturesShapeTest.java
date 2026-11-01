package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T001 (feature 011, research D-15): the 6.1 fixture folder exists with its {@code README.md} and
 * {@code RECONSTRUCTED.txt}, every line of {@code RECONSTRUCTED.txt} names a case and its
 * capability, and every case listed there has its three files.
 */
class V61FixturesShapeTest {

    private static final String FOLDER = "/fixtures/v6_1/cli/";
    private static final Pattern LINE = Pattern.compile("^([a-z0-9_]+) ([a-z_]+)$");

    @Test
    void theFolderHasItsReadme() {
        assertThat(resource(FOLDER + "README.md")).as(FOLDER + "README.md").isNotNull();
    }

    @Test
    void everyLineOfReconstructedNamesACaseAndItsCapability() {
        for (String line : entries()) {
            assertThat(LINE.matcher(line).matches()).as(line).isTrue();
        }
    }

    @Test
    void everyReconstructedCaseHasStdoutStderrAndExitCode() {
        for (String line : entries()) {
            Matcher entry = LINE.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
                assertThat(resource(FOLDER + entry.group(1) + suffix))
                    .as(entry.group(1) + suffix).isNotNull();
            }
        }
    }

    /**
     * The lines of {@code RECONSTRUCTED.txt} without blank lines and {@code #} comments; also read
     * by {@link V61RecordedFixturesTest}.
     */
    static List<String> entries() {
        try (InputStream in = resource(FOLDER + "RECONSTRUCTED.txt")) {
            assertThat(in).as(FOLDER + "RECONSTRUCTED.txt").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String path) {
        return V61FixturesShapeTest.class.getResourceAsStream(path);
    }
}
