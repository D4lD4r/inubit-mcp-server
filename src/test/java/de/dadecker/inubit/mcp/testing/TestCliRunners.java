package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import java.util.Map;

/**
 * Builds a {@link CliRunner} for the host platform ({@link Platform#windows()}). Until the
 * Windows launch exists (feature 013, T013) this is the existing constructor; T013 switches it to
 * the new one with the host's {@code CliSystem}.
 */
public final class TestCliRunners {

    private TestCliRunners() {
    }

    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources) {
        return new CliRunner(launcher, parentEnvironment, Platform.windows(), resources);
    }
}
