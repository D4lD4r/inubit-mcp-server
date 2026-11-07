package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Whether a step loads the native code of JNA, checked in a fresh JVM: another test of the same
 * test JVM may already have loaded it (on Windows the {@code PrivateFileSystem} does, feature
 * 013), so {@code jna.loaded} of the test JVM says nothing about the step.
 */
public final class NativeCodeProbe {

    private static final String MARKER = "jna.loaded=";

    private NativeCodeProbe() {
    }

    /** Runs the step {@code args[0]} and prints {@code jna.loaded} afterwards. */
    public static void main(String[] args) {
        switch (args[0]) {
            case "stores" -> {
                for (String osName : new String[] {"Mac OS X", "Windows 11", "Linux", "FreeBSD"}) {
                    CredentialStores.forOs(osName, FakeProcessLauncher.of("", "", 0), Map.of(),
                        Path.of("/tmp")).kind();
                }
            }
            case "drives" -> {
                new WindowsNetworkPaths(false, JnaDriveTypeApi::new).remoteDrive('Z');
                new JnaDriveTypeApi();
            }
            default -> throw new IllegalArgumentException("Unknown step " + args[0]);
        }
        System.out.println();
        System.out.println(MARKER + System.getProperty("jna.loaded"));
    }

    /** {@code jna.loaded} after {@code step} in a fresh JVM with the test class path. */
    static String jnaLoadedAfter(String step) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
            "-cp", System.getProperty("java.class.path"), NativeCodeProbe.class.getName(), step))
            .redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("The probe failed: " + output);
        }
        return output.lines().filter(line -> line.startsWith(MARKER))
            .reduce((first, second) -> second)
            .map(line -> line.substring(MARKER.length()))
            .orElseThrow(() -> new IllegalStateException("No result from the probe: " + output));
    }
}
