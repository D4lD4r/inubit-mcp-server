package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.Main;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Feature 020 (research D-2): the queue-entry helper is compiled by its own compiler execution
 * ({@code compile-queue-entry-helper}, release 17) into a resource directory of the compiled
 * classes: exactly one class file, Java 17 bytecode, not loadable as a class of the server.
 * {@link QueueEntryHelperPackagingIT} checks the same inside the shaded JAR.
 */
class QueueEntryHelperPackagingTest {

    static final String RESOURCE_DIRECTORY = "META-INF/inubit-mcp/queue-entry-helper/";
    static final String HELPER_CLASS = "de.dadecker.inubit.mcp.helper.QueueEntryHelper";
    static final String HELPER_FILE = HELPER_CLASS.replace('.', '/') + ".class";
    /** The class-file major version of Java 17. */
    static final int JAVA_17 = 61;

    @Test
    void theResourceDirectoryHoldsExactlyTheHelperClass() throws Exception {
        Path dir = resourceDirectory();
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> names = files.filter(Files::isRegularFile)
                .map(file -> dir.relativize(file).toString().replace('\\', '/')).toList();
            assertThat(names).containsExactly(HELPER_FILE);
        }
    }

    @Test
    void theHelperIsJava17Bytecode() throws Exception {
        assertThat(majorVersion(Files.readAllBytes(resourceDirectory().resolve(HELPER_FILE))))
            .isEqualTo(JAVA_17);
    }

    @Test
    void theHelperIsNotAClassOfTheServer() {
        assertThatThrownBy(() -> Class.forName(HELPER_CLASS, false,
            QueueEntryHelperPackagingTest.class.getClassLoader()))
            .isInstanceOf(ClassNotFoundException.class);
    }

    /** The helper's resource directory below the compiled production classes. */
    static Path resourceDirectory() throws URISyntaxException {
        Path root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return root.resolve(RESOURCE_DIRECTORY);
    }

    static int majorVersion(byte[] classFile) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(classFile);
        if (buffer.getInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        buffer.getShort();
        return Short.toUnsignedInt(buffer.getShort());
    }
}
