package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link CliRunner} for the host platform ({@link Platform#windows()}) with the host's
 * {@link CliSystem}, or for a simulated Windows host on any platform. StartCLI's processes count
 * as contained: tests never bind their JVM to a job (feature 013, research R-7).
 */
public final class TestCliRunners {

    /** The system directory of the simulated Windows host. */
    public static final String SYSTEM_DIRECTORY = "C:\\Windows\\system32";

    /** A {@link CliSystem} with fixed answers. */
    public record FixedSystem(Optional<String> systemDirectory, Charset oemCharset)
        implements CliSystem {
    }

    private TestCliRunners() {
    }

    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources);
    }

    /**
     * A runner on a simulated Windows host ({@link #SYSTEM_DIRECTORY}, OEM code page 850) with
     * the given containment state; with a fake launcher nothing needs Windows.
     */
    public static CliRunner simulatedWindows(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, ProcessContainment.State containment,
        CliResources resources) {
        return new CliRunner(launcher, parentEnvironment, true,
            new FixedSystem(Optional.of(SYSTEM_DIRECTORY), Charset.forName("IBM850")),
            () -> containment, resources);
    }
}
