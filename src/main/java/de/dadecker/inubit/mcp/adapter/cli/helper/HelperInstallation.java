package de.dadecker.inubit.mcp.adapter.cli.helper;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.StartCliEnvironment;
import de.dadecker.inubit.mcp.config.CliChoice;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The installation of the helper for one call (feature 020, research D-2, D-3, D-8): the private
 * directory {@code <tmp>/inubit-mcp-queue-<profile>-<pid>-<random>} (for a server log
 * {@code inubit-mcp-log-…}, feature 021 research D-7; for a node tool {@code inubit-mcp-node-…},
 * feature 024 research D-24) with
 * {@code helper/} (the helper class from the MCP server's own jar), {@code vendor/} (the
 * client's {@code java.io.tmpdir}), {@code out/} (the content files) and, written by the caller,
 * {@link HelperTrustStore#FILE_NAME}; and the argument array that starts the helper on the
 * configured INUBIT client. The directory is registered with {@link CliResources}, so a shutdown
 * deletes it as well; {@link #close()} deletes it after the call.
 */
public final class HelperInstallation implements AutoCloseable {

    /** The helper's main class. */
    public static final String MAIN_CLASS = "de.dadecker.inubit.mcp.helper.ClientHelper";
    static final String CLASS_FILE = "de/dadecker/inubit/mcp/helper/ClientHelper.class";
    static final String RESOURCE = "META-INF/inubit-mcp/client-helper/" + CLASS_FILE;
    /** The {@code --add-opens} of {@code bin/startcli.sh} of 8.1.17 and 9.0.10 (research D-3). */
    static final List<String> ADD_OPENS = List.of(
        "java.xml/com.sun.org.apache.xerces.internal.dom", "java.base/java.lang",
        "java.base/java.lang.reflect", "java.base/java.io", "java.desktop/java.awt",
        "java.base/java.util", "java.base/java.util.concurrent", "java.base/java.nio.charset",
        "java.base/java.text", "java.desktop/java.awt.font");

    /**
     * What the launch needs from the host, as {@link CliRunner} has it.
     *
     * @param windows           whether the helper runs on Windows ({@code java.exe}, {@code ;})
     * @param parentEnvironment the server's environment; the child gets the allowlist only
     * @param tempRoot          the server's {@code java.io.tmpdir}
     */
    public record Host(boolean windows, Map<String, String> parentEnvironment, CliSystem system,
        ProcessContainment containment, CliResources resources, Path tempRoot) {

        public Host {
            parentEnvironment = Map.copyOf(parentEnvironment);
            Objects.requireNonNull(system, "system");
            Objects.requireNonNull(containment, "containment");
            Objects.requireNonNull(resources, "resources");
            tempRoot = tempRoot.toAbsolutePath();
        }
    }

    /** What the helper runs for; names it in the texts and chooses the directory's prefix. */
    public enum Use {
        /** The queue-entry read of feature 020: {@code inubit-mcp-queue-…}. */
        QUEUE_ENTRY("the queue-entry helper", "The queue-entry helper"),
        /** The server-log download of feature 021: {@code inubit-mcp-log-…}. */
        SERVER_LOG("the INUBIT client helper", "The INUBIT client helper"),
        /** A node tool of feature 024: {@code inubit-mcp-node-…}. */
        NODE_TOOLS("the INUBIT client helper", "The INUBIT client helper");

        private final String label;
        private final String subject;

        Use(String label, String subject) {
            this.label = label;
            this.subject = subject;
        }

        /** The name at the start of a sentence. */
        String subject() {
            return subject;
        }

        private String prefix(CliResources resources) {
            return switch (this) {
                case QUEUE_ENTRY -> resources.queuePrefix();
                case SERVER_LOG -> resources.logPrefix();
                case NODE_TOOLS -> resources.nodePrefix();
            };
        }
    }

    private final Path directory;
    private final LaunchSpec launchSpec;
    private final CliResources.Registration registration;

    private HelperInstallation(Path directory, LaunchSpec launchSpec,
        CliResources.Registration registration) {
        this.directory = directory;
        this.launchSpec = launchSpec;
        this.registration = registration;
    }

    /**
     * Checks the preconditions, then creates and fills the private directory, for
     * {@link Use#QUEUE_ENTRY}.
     *
     * @param selection the client chosen for the node and its version
     * @param javaHome  the effective Java home ({@link CliRunner#javaHome})
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} with the node if a precondition fails
     *     or no private directory can be created (nothing is created then) or the helper
     *     class cannot be copied into it (the directory is deleted); {@code INTERNAL} if
     *     the server is shutting down or its build lacks the helper (the directory is deleted)
     */
    public static HelperInstallation install(Host host, CliRunner.Selection selection,
        Optional<String> javaHome) {
        return install(host, selection, javaHome, Use.QUEUE_ENTRY);
    }

    /** As above, for {@code use}. */
    public static HelperInstallation install(Host host, CliRunner.Selection selection,
        Optional<String> javaHome, Use use) {
        return install(host, selection, javaHome, use, HelperInstallation.class.getClassLoader());
    }

    static HelperInstallation install(Host host, CliRunner.Selection selection,
        Optional<String> javaHome, ClassLoader loader) {
        return install(host, selection, javaHome, Use.QUEUE_ENTRY, loader);
    }

    private static HelperInstallation install(Host host, CliRunner.Selection selection,
        Optional<String> javaHome, Use use, ClassLoader loader) {
        NodeId node = selection.server().id();
        Launch launch = check(host, selection, javaHome, use);
        CliResources.Reservation reservation;
        try {
            reservation = host.resources().reserve();
        } catch (ToolErrorException e) {
            throw shuttingDown(e, node, use);
        }
        PrivateFileSystem files = host.resources().files();
        Path directory;
        try {
            directory = files.createTempDirectory(host.tempRoot(), use.prefix(host.resources()));
        } catch (IOException | UnsupportedOperationException e) {
            reservation.close();
            throw noPrivateDirectory(host, node, use, e);
        }
        CliResources.Registration registration;
        try {
            registration = reservation.directory(directory);
        } catch (ToolErrorException e) {
            throw shuttingDown(e, node, use);
        }
        try {
            fill(files, directory);
            copyHelper(directory, loader, host, node, use);
            return new HelperInstallation(directory, launch.spec(host, directory), registration);
        } catch (IOException | UnsupportedOperationException e) {
            registration.close();
            throw noPrivateDirectory(host, node, use, e);
        } catch (RuntimeException e) {
            registration.close();
            throw e;
        }
    }

    /** The call's private directory. */
    public Path directory() {
        return directory;
    }

    /** {@code <tmp>/out}: the helper writes the content files here. */
    public Path outDirectory() {
        return directory.resolve("out");
    }

    /** {@code <tmp>/trust.p12}, written by {@link HelperTrustStore#write} for TLS nodes. */
    public Path trustStoreFile() {
        return directory.resolve(HelperTrustStore.FILE_NAME);
    }

    /** The argument array, environment and working directory of the helper. */
    public LaunchSpec launchSpec() {
        return launchSpec;
    }

    /** Deletes the directory; idempotent. */
    @Override
    public void close() {
        registration.close();
    }

    /** The checked inputs of the launch. */
    private record Launch(Path home, Path java, String javaHome,
        Optional<String> systemDirectory) {

        LaunchSpec spec(Host host, Path directory) {
            String separator = host.windows() ? ";" : ":";
            String fileSeparator = home.getFileSystem().getSeparator();
            List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx512m",
                "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3",
                "-Dsecretkey.path=" + home.resolve("conf"),
                "-Djava.io.tmpdir=" + directory.resolve("vendor")));
            ADD_OPENS.forEach(module -> command.add("--add-opens=" + module + "=ALL-UNNAMED"));
            command.add("-cp");
            command.add(directory.resolve("helper") + separator
                + home.resolve("lib") + fileSeparator + "*" + separator
                + home.resolve("lib").resolve("shared") + fileSeparator + "*");
            command.add(MAIN_CLASS);
            Map<String, String> environment = systemDirectory
                .map(system -> StartCliEnvironment.windows(host.parentEnvironment(), javaHome,
                    system))
                .orElseGet(() -> StartCliEnvironment.posix(host.parentEnvironment(), javaHome));
            return new LaunchSpec(command, environment, home);
        }
    }

    private static Launch check(Host host, CliRunner.Selection selection,
        Optional<String> javaHome, Use use) {
        EffectiveNodeConfig server = selection.server();
        String prefix = use.subject() + " cannot be started for " + server.id()
            + selection.version().map(version -> " (INUBIT " + version.raw() + ")").orElse("")
            + ": ";
        Optional<MajorMinor> line = selection.choice().entry()
            .or(() -> selection.version().map(MajorMinor::of));
        Path home = server.cli().home().orElseThrow(() -> selection.entryHint()
            .map(entry -> unavailable(server, prefix
                    + "no INUBIT client installation is configured",
                "Neither cli.home, defaults.cliClients.\"" + entry.text() + "\" nor cliHome is"
                    + " set",
                "Add defaults.cliClients.\"" + entry.text() + "\".home (the INUBIT "
                    + entry.text() + " client directory) or set cliHome"))
            .orElseGet(() -> unavailable(server, prefix
                    + "no INUBIT client installation is configured",
                "cliHome / cli.home is not set", "Set cliHome to the INUBIT client directory")));
        String homeSetting = homeSetting(selection.choice());
        Path ibis = home.resolve("lib").resolve("ibis.jar");
        if (!Files.isRegularFile(ibis) || !Files.isReadable(ibis)) {
            throw unavailable(server, prefix + ibis + " is missing or unreadable",
                homeSetting + " does not point to an INUBIT client installation",
                "Set " + homeSetting + " to the INUBIT "
                    + line.map(known -> known.text() + " ").orElse("")
                    + "client directory, which contains lib/ibis.jar");
        }
        String jdk = javaHome.orElseThrow(() -> unavailable(server,
            prefix + "no JDK is configured",
            "Neither cli.javaHome (cliJavaHome) nor JAVA_HOME is set",
            "Set cli.javaHome to the JDK the INUBIT client needs (8.1: JDK 17, 9.0: JDK 21)"));
        Path java = Path.of(jdk).resolve("bin").resolve(host.windows() ? "java.exe" : "java");
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
            throw unavailable(server, prefix + java + " is not an executable file",
                "cli.javaHome (cliJavaHome) or JAVA_HOME does not point to a JDK",
                "Set cli.javaHome to the JDK the INUBIT client needs (8.1: JDK 17, 9.0:"
                    + " JDK 21)");
        }
        String separator = host.windows() ? ";" : ":";
        if (home.toString().contains(separator)) {
            throw unavailable(server, prefix + homeSetting + " " + home + " contains '"
                    + separator + "', the class path separator",
                "The java launcher would split the class path at it",
                "Install the INUBIT client in a directory whose path has no '" + separator
                    + "'");
        }
        if (host.tempRoot().toString().contains(separator)) {
            throw unavailable(server, prefix + "the temporary directory " + host.tempRoot()
                    + " contains '" + separator + "', the class path separator",
                "The java launcher would split the class path at it",
                "Start the MCP server with -Djava.io.tmpdir=<a directory whose path has no '"
                    + separator + "'>");
        }
        // type patterns, as in CliRunner (javac 21 record-pattern stack map issue)
        if (host.containment().state() instanceof ProcessContainment.Unavailable refused) {
            throw unavailable(server, prefix + "its process could not be bound to the MCP"
                    + " server (" + refused.reason() + ")",
                "Windows refused the job that ends the helper's process with the MCP server",
                "Start the MCP server outside a job that forbids nested jobs; the REST tools"
                    + " work meanwhile");
        }
        if (!host.windows()) {
            return new Launch(home, java, jdk, Optional.empty());
        }
        CliSystem.SystemDirectory system = host.system().systemDirectory();
        if (system instanceof CliSystem.Unreadable unreadable) {
            throw unavailable(server, prefix + "the Windows system directory could not be read"
                    + " (" + unreadable.reason() + ")",
                "The helper's environment cannot be built without it",
                "Restart the MCP server; the REST tools work meanwhile");
        }
        return new Launch(home, java, jdk, Optional.of(((CliSystem.Read) system).path()));
    }

    /** The setting that names the chosen client directory. */
    private static String homeSetting(CliChoice choice) {
        return switch (choice.source()) {
            case VERSION_ENTRY -> "defaults.cliClients.\"" + choice.entry().orElseThrow().text()
                + "\".home";
            case DEFAULTS -> "defaults.cliHome";
            case NODE, GROUP, NONE -> "cli.home";
        };
    }

    /** Creates {@code helper/}, {@code vendor/} and {@code out/}. */
    private static void fill(PrivateFileSystem files, Path directory) throws IOException {
        files.createDirectory(directory.resolve("helper"));
        files.createDirectory(directory.resolve("vendor"));
        files.createDirectory(directory.resolve("out"));
    }

    /** Copies the helper class into {@code helper/}. */
    private static void copyHelper(Path directory, ClassLoader loader, Host host, NodeId node,
        Use use) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                    use.subject() + " is missing from the MCP server's build; it was not"
                        + " started for " + node,
                    "The build is incomplete",
                    "Rebuild or reinstall the MCP server").withNode(node));
            }
            Path target = directory.resolve("helper").resolve(CLASS_FILE);
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (IOException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
                use.subject() + " could not be copied into its private temporary"
                    + " directory for " + node + " (" + e.getClass().getSimpleName() + ")",
                "Writing to the temporary directory failed; it may be full",
                "Make sure the temporary directory " + host.tempRoot() + " has free space and is"
                    + " writable by the account of the MCP server, or point java.io.tmpdir of"
                    + " the MCP server to such a directory").withNode(node));
        }
    }

    /** {@link CliResources#reserve()} refused: the texts name the helper, not StartCLI (D-3). */
    private static ToolErrorException shuttingDown(ToolErrorException e, NodeId node, Use use) {
        ToolError refused = e.error();
        return new ToolErrorException(ToolError.of(refused.code(),
            "The MCP server is shutting down; " + use.label + " was not started for " + node,
            refused.likelyCause(), refused.nextStep()).withNode(node));
    }

    private static ToolErrorException noPrivateDirectory(Host host, NodeId node, Use use,
        Exception e) {
        return new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE,
            "No private temporary directory for " + use.label + " on " + node + " ("
                + e.getClass().getSimpleName() + ")",
            CliResources.NO_PRIVATE_ACCESS,
            CliResources.privateDirectoryStep(host.tempRoot(), host.windows())).withNode(node));
    }

    private static ToolErrorException unavailable(EffectiveNodeConfig server, String message,
        String likelyCause, String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.CLI_UNAVAILABLE, message,
            likelyCause, nextStep).withNode(server.id()));
    }
}
