package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.FakeStartCli;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * T017 (feature 013, SC-003): the secret inspection of {@link SecretInspectionCases} on Windows,
 * through {@code cmd.exe} and the substitute {@code startcli.bat}; the line the interpreter
 * received ({@code %CMDCMDLINE%}) is the one the server built and holds no credential.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSecretInspectionTest extends SecretInspectionCases {

    /** Added by the JDK to every child ({@code SystemRoot}) or by {@code cmd.exe} itself. */
    private static final Set<String> INTERPRETER = Set.of("systemroot", "prompt");

    @Override
    Set<String> allowlist() {
        Set<String> names = new TreeSet<>(StartCliEnvironment.WINDOWS_VARIABLES);
        names.addAll(Set.of("ComSpec", "PATH", "NoDefaultCurrentDirectoryInExePath",
            "JAVA_HOME", "JAVA_TOOL_OPTIONS"));
        return names;
    }

    @Override
    boolean addedByTheInterpreter(String name) {
        // cmd.exe keeps the per-drive directories as hidden variables named "=C:" etc.
        return name.startsWith("=") || INTERPRETER.contains(name);
    }

    @Override
    String normalized(String name) {
        return lowerCase(name);
    }

    @Override
    void inspectPlatform(FakeStartCli cli, ProcessLauncher.LaunchSpec spec) throws IOException {
        String line = cli.commandLine();
        assertThat(line).isEqualTo(String.join(" ", spec.command()));
        assertThat(holdsACredential(line)).as("%%CMDCMDLINE%%").isFalse();
    }
}
