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
 * Feature 020 (research D-2): the shaded JAR carries the queue-entry helper as the single class
 * file below {@code META-INF/inubit-mcp/queue-entry-helper/}, Java 17 bytecode. Runs in Failsafe
 * after {@code package} (the shaded JAR does not exist yet when Surefire runs
 * {@link QueueEntryHelperPackagingTest}); {@code project.jar} comes from {@code pom.xml}.
 */
class QueueEntryHelperPackagingIT {

    private static final Path JAR = Path.of(System.getProperty("project.jar"));

    @Test
    void theShadedJarHoldsExactlyTheJava17HelperClass() throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            List<String> names = jar.stream().filter(entry -> !entry.isDirectory())
                .map(JarEntry::getName)
                .filter(name -> name.startsWith(QueueEntryHelperPackagingTest.RESOURCE_DIRECTORY))
                .toList();
            assertThat(names).containsExactly(QueueEntryHelperPackagingTest.RESOURCE_DIRECTORY
                + QueueEntryHelperPackagingTest.HELPER_FILE);
            try (InputStream in = jar.getInputStream(jar.getJarEntry(names.get(0)))) {
                assertThat(QueueEntryHelperPackagingTest.majorVersion(in.readAllBytes()))
                    .isEqualTo(QueueEntryHelperPackagingTest.JAVA_17);
            }
        }
    }
}
