package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 016 (FR-010–FR-012, SC-004): the shaded JAR's manifest carries the project version, and
 * the server reports it even when the JAR file name has no version. Runs in Failsafe after
 * {@code package}; {@code project.jar} and {@code project.version} come from {@code pom.xml}.
 */
class JarVersionIT {

    private static final String VERSION = System.getProperty("project.version");
    private static final Path JAR = Path.of(System.getProperty("project.jar"));

    @TempDir
    Path dir;

    @Test
    void manifestNamesTitleAndProjectVersion() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            Attributes main = jar.getManifest().getMainAttributes();
            assertThat(main.getValue(Attributes.Name.IMPLEMENTATION_TITLE))
                .isEqualTo("inubit-mcp-server");
            assertThat(main.getValue(Attributes.Name.IMPLEMENTATION_VERSION)).isEqualTo(VERSION);
        }
    }

    @Test
    void versionOptionReportsTheManifestVersionForAJarWithoutVersionInItsName()
        throws IOException, InterruptedException {
        Path renamed = Files.copy(JAR, dir.resolve("inubit-mcp-server.jar"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(),
            "-Djava.io.tmpdir=" + dir, "-jar", renamed.toString(), "--version")
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("INUBIT_MCP_UPDATE_CHECK", "off");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(out.strip()).isEqualTo("inubit-mcp-server " + VERSION);
    }
}
