package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Feature 020 (research D-2), renamed in feature 021 (D-3): the shaded JAR carries the client
 * helper as the single class file below {@code META-INF/inubit-mcp/client-helper/}, Java 17
 * bytecode, and nothing below the 020 directory {@code queue-entry-helper/}. Runs in Failsafe
 * after {@code package} (the shaded JAR does not exist yet when Surefire runs
 * {@link ClientHelperPackagingTest}); {@code project.jar} comes from {@code pom.xml}.
 */
class ClientHelperPackagingIT {

    private static final Path JAR = Path.of(System.getProperty("project.jar"));

    @Test
    void theShadedJarHoldsExactlyTheJava17HelperClass() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            List<String> names = jar.stream().filter(entry -> !entry.isDirectory())
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(ClientHelperPackagingTest.RESOURCE_DIRECTORY))
                .toList();
            assertThat(names).containsExactly(ClientHelperPackagingTest.RESOURCE_DIRECTORY
                + ClientHelperPackagingTest.HELPER_FILE);
            try (InputStream in = jar.getInputStream(jar.getJarEntry(names.get(0)))) {
                assertThat(ClientHelperPackagingTest.majorVersion(in.readAllBytes()))
                    .isEqualTo(ClientHelperPackagingTest.JAVA_17);
            }
            String oldDirectory = ClientHelperPackagingTest.OLD_RESOURCE_DIRECTORY;
            assertThat(jar.stream().map(JarEntry::getName))
                .noneMatch(name -> name.startsWith(oldDirectory));
        }
    }
}
