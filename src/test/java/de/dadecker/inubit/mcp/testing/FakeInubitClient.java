package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * A substitute INUBIT client installation for a child JVM (feature 020, research D-19), like
 * {@link FakeStartCli} for StartCLI: {@code <home>/lib/ibis.jar} holds the fake vendor classes
 * under {@code com/inubit/ibis} and {@link FakeInubitScript}, packed from the test classes at
 * install time; {@code <home>/lib/shared/} is empty; {@code <home>/conf/} holds the script and,
 * after a run, the calls the fakes received.
 */
public final class FakeInubitClient {

    private static final String VENDOR_CLASSES = "com/inubit/ibis/";
    private static final String SCRIPT_CLASSES = FakeInubitScript.class.getName()
        .replace('.', '/');

    private final Path home;

    private FakeInubitClient(Path home) {
        this.home = home;
    }

    /** Installs a client home at {@code home} whose fakes follow {@code script}. */
    public static FakeInubitClient install(Path home, FakeInubitScript script)
        throws IOException {
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(lib.resolve("shared"));
        Path conf = Files.createDirectories(home.resolve("conf"));
        writeJar(lib.resolve("ibis.jar"));
        script.writeTo(conf.resolve(FakeInubitScript.SCRIPT_FILE));
        return new FakeInubitClient(home);
    }

    public Path home() {
        return home;
    }

    /** The calls the fakes received in child JVMs using this home, empty before any. */
    public List<Call> calls() {
        return FakeInubitScript.readCalls(
            home.resolve("conf").resolve(FakeInubitScript.CALLS_FILE));
    }

    private static void writeJar(Path jar) throws IOException {
        Path classes = testClasses();
        try (Stream<Path> files = Files.walk(classes);
            OutputStream out = Files.newOutputStream(jar);
            JarOutputStream entries = new JarOutputStream(out)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                if (name.startsWith(VENDOR_CLASSES) || name.startsWith(SCRIPT_CLASSES + ".")
                    || name.startsWith(SCRIPT_CLASSES + "$")) {
                    entries.putNextEntry(new JarEntry(name));
                    Files.copy(file, entries);
                    entries.closeEntry();
                }
            }
        }
    }

    private static Path testClasses() {
        try {
            return Path.of(FakeInubitScript.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
