package de.dadecker.inubit.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.Main;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Feature 022 (T005, research D-8): {@code RestEndpoint} is the only place that names an INUBIT
 * REST path. Every class file below {@code target/classes} is read without a bytecode library and
 * its {@code Utf8} constants (string literals included) are searched for {@code /ibis/rest}; only
 * the enum itself may hold it.
 */
class RestEndpointRegistryTest {

    private static final String REST_ROOT = "/ibis/rest";
    private static final String REGISTRY = "de/dadecker/inubit/mcp/adapter/rest/RestEndpoint";

    /** Class (internal name) → its {@code Utf8} constants. */
    private static Map<String, List<String>> classes;

    @BeforeAll
    static void readCompiledClasses() throws Exception {
        Path root = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation()
            .toURI());
        classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))
                ::iterator) {
                String name = root.relativize(file).toString().replace('\\', '/');
                classes.put(name.substring(0, name.length() - ".class".length()),
                    utf8Constants(Files.readAllBytes(file)));
            }
        }
    }

    @Test
    void theRegistryHoldsTheRestRoot() {
        assertThat(classes.get(REGISTRY)).as("constants of RestEndpoint")
            .anyMatch(text -> text.contains(REST_ROOT));
    }

    @Test
    void noOtherClassHoldsTheRestRoot() {
        assertThat(classes.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(REGISTRY))
            .filter(entry -> entry.getValue().stream().anyMatch(text -> text.contains(REST_ROOT)))
            .map(Map.Entry::getKey))
            .as("classes besides RestEndpoint holding " + REST_ROOT)
            .isEmpty();
    }

    private static List<String> utf8Constants(byte[] classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("not a class file");
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            List<String> texts = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> texts.add(in.readUTF());
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2); // Class, String, MethodType, …
                    case 15 -> in.skipBytes(3); // MethodHandle
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // Long and Double take two slots
                    }
                    default -> throw new IllegalArgumentException("constant tag " + tag);
                }
            }
            return texts;
        }
    }
}
