package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.HandshakeStartCliPinProbe;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.StartCliPinProbe;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.os.PosixSystem;
import de.dadecker.inubit.mcp.adapter.os.WindowsSystem;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.ClientCheckLookup;
import de.dadecker.inubit.mcp.domain.port.WriteLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds a {@link CliRunner} for the host platform ({@link Platform#windows()}) with the host's
 * {@link CliSystem}, or for a simulated Windows host on any platform. StartCLI's processes count
 * as contained: tests never bind their JVM to a job (feature 013, research R-7). Unless given,
 * every node's effective version is unknown (feature 017: StartCLI is chosen by the line).
 */
public final class TestCliRunners {

    /** The system directory of the simulated Windows host. */
    public static final String SYSTEM_DIRECTORY = "C:\\Windows\\system32";

    /** A {@link CliSystem} with fixed answers. */
    public record FixedSystem(CliSystem.SystemDirectory systemDirectory, Charset oemCharset)
        implements CliSystem {
    }

    private TestCliRunners() {
    }

    /** No node's version is known. */
    public static final Function<NodeId, Optional<InubitVersion>> UNKNOWN_VERSIONS =
        id -> Optional.empty();

    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources) {
        return forHost(launcher, parentEnvironment, resources, UNKNOWN_VERSIONS);
    }

    /** Feature 017: with the version source StartCLI is chosen by (research D-4). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        Function<NodeId, Optional<InubitVersion>> versions) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources, new HandshakeStartCliPinProbe(), versions);
    }

    /** Feature 017: with the recorded client checks of the launch guard (research D-4). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        Function<NodeId, Optional<InubitVersion>> versions, ClientCheckLookup checks) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources, new HandshakeStartCliPinProbe(), versions, checks);
    }

    /** Feature 017: with the write lock writes are handed off under (research D-24). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        Function<NodeId, Optional<InubitVersion>> versions, ClientCheckLookup checks,
        WriteLock writeLock) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources, new HandshakeStartCliPinProbe(), versions, checks, writeLock);
    }

    /** As above, with the StartCLI pin probe of feature 014 (research R-15). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        StartCliPinProbe pinProbe) {
        return forHost(launcher, parentEnvironment, resources, pinProbe, UNKNOWN_VERSIONS);
    }

    /** As above, with the effective versions StartCLI is chosen for (feature 017). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        StartCliPinProbe pinProbe, Function<NodeId, Optional<InubitVersion>> versions) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources, pinProbe, versions);
    }

    /** As above, with the recorded client checks of the launch guard (feature 017, D-4). */
    public static CliRunner forHost(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, CliResources resources,
        StartCliPinProbe pinProbe, Function<NodeId, Optional<InubitVersion>> versions,
        ClientCheckLookup checks) {
        boolean windows = Platform.windows();
        return new CliRunner(launcher, parentEnvironment, windows,
            windows ? new WindowsSystem() : new PosixSystem(), ProcessContainment.Bound::new,
            resources, pinProbe, versions, checks);
    }

    /**
     * What the helper of features 020/021 needs from the host platform ({@link Platform#windows()};
     * on Windows the simulated system directory); its processes count as contained.
     */
    public static HelperInstallation.Host helperHost(Map<String, String> parentEnvironment,
        CliResources resources, Path tempRoot) {
        return Platform.windows()
            ? new HelperInstallation.Host(true, parentEnvironment,
                new FixedSystem(new CliSystem.Read(SYSTEM_DIRECTORY), Charset.forName("IBM850")),
                ProcessContainment.Bound::new, resources, tempRoot)
            : new HelperInstallation.Host(false, parentEnvironment,
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, tempRoot);
    }

    /**
     * A runner on a simulated Windows host ({@link #SYSTEM_DIRECTORY}, OEM code page 850) with
     * the given containment state; with a fake launcher nothing needs Windows.
     */
    public static CliRunner simulatedWindows(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, ProcessContainment.State containment,
        CliResources resources) {
        return simulatedWindows(launcher, parentEnvironment, containment, resources,
            UNKNOWN_VERSIONS);
    }

    /** As above, with the version source StartCLI is chosen by (feature 017). */
    public static CliRunner simulatedWindows(ProcessLauncher launcher,
        Map<String, String> parentEnvironment, ProcessContainment.State containment,
        CliResources resources, Function<NodeId, Optional<InubitVersion>> versions) {
        return new CliRunner(launcher, parentEnvironment, true,
            new FixedSystem(new CliSystem.Read(SYSTEM_DIRECTORY), Charset.forName("IBM850")),
            () -> containment, resources, new HandshakeStartCliPinProbe(), versions);
    }
}
