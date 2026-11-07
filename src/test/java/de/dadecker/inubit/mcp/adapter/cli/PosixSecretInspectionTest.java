package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.testing.FakeStartCli;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * T017 (feature 013, SC-003): the POSIX twin of {@link WindowsSecretInspectionTest}, the secret
 * inspection of {@link SecretInspectionCases} with today's launch ({@code startcli.sh}).
 */
@DisabledOnOs(OS.WINDOWS)
class PosixSecretInspectionTest extends SecretInspectionCases {

    /**
     * Set by {@code /bin/sh} itself ({@code PWD}, {@code SHLVL}, {@code _}) or by macOS in every
     * process ({@code __CF_USER_TEXT_ENCODING}).
     */
    private static final Set<String> INTERPRETER =
        Set.of("PWD", "SHLVL", "_", "__CF_USER_TEXT_ENCODING");

    @Override
    Set<String> allowlist() {
        Set<String> names = new TreeSet<>(StartCliEnvironment.POSIX_VARIABLES);
        names.addAll(Set.of("JAVA_HOME", "JAVA_TOOL_OPTIONS"));
        return names;
    }

    @Override
    boolean addedByTheInterpreter(String name) {
        return INTERPRETER.contains(name);
    }

    @Override
    String normalized(String name) {
        return name;
    }

    @Override
    void inspectPlatform(FakeStartCli cli, ProcessLauncher.LaunchSpec spec) {
        // no interpreter line on POSIX: the shell receives the arguments as they are
    }
}
