package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.StartCliEnvironment;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-call installation of the queue-entry helper (feature 020, research D-2, D-3, D-8): the
 * private directory {@code inubit-mcp-queue-<profile>-<pid>-<random>} with {@code helper/},
 * {@code vendor/} and {@code out/}, the exact argument array on POSIX and on a simulated Windows
 * host, and each precondition, refused with nothing created. The launch with this argument array
 * in a real child JVM is {@link ClientHelperProcessTest}.
 */
class HelperInstallationTest {

    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final NodeId NODE = NodeId.parse("int/a");
    private static final List<String> ADD_OPENS = List.of(
        "--add-opens=java.xml/com.sun.org.apache.xerces.internal.dom=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED");
    /** The server's environment, with a credential the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "SystemRoot", "C:\\Windows",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    @TempDir
    Path root;

    private Path tempRoot;
    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private final CliRunner runner = TestCliRunners.forHost(FakeProcessLauncher.of("", "", 0),
        Map.of(), resources);

    @BeforeEach
    void createTempRoot() throws IOException {
        tempRoot = Files.createDirectories(root.resolve("tmp"));
    }

    @AfterEach
    void closeResources() {
        resources.close();
    }

    // --- the installation and its argument arrays (D-2, D-3, D-8) -------------------------

    @Test
    void posixArgumentArrayEnvironmentAndPrivateDirectory() throws Exception {
        assumeFalse(Platform.windows(), "a POSIX home path has no drive colon");
        Path home = client("client");
        Path jdk = jdk();
        Path directory;

        try (HelperInstallation installation = HelperInstallation.install(posix(),
            selection(node().cliHome(home).cliJavaHome(jdk)), Optional.of(jdk.toString()))) {
            directory = installation.directory();
            assertInstalled(installation);
            assertThat(Files.getPosixFilePermissions(directory))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));

            LaunchSpec spec = installation.launchSpec();
            List<String> expected = new ArrayList<>(List.of(jdk + "/bin/java", "-Xmx512m",
                "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3",
                "-Dsecretkey.path=" + home + "/conf",
                "-Djava.io.tmpdir=" + directory + "/vendor"));
            expected.addAll(ADD_OPENS);
            expected.addAll(List.of("-cp",
                directory + "/helper:" + home + "/lib/*:" + home + "/lib/shared/*",
                ClientHelperPackagingTest.HELPER_CLASS));
            assertThat(spec.command()).containsExactlyElementsOf(expected);
            assertThat(spec.environment())
                .isEqualTo(StartCliEnvironment.posix(PARENT, jdk.toString()));
            assertThat(spec.workingDirectory()).isEqualTo(home);
            leakCheck(spec);
        }
        assertThat(directory).as("deleted on close").doesNotExist();
    }

    @Test
    void simulatedWindowsArgumentArrayAndEnvironment() throws Exception {
        Path home = client("client");
        Path jdk = jdk();

        try (HelperInstallation installation = HelperInstallation.install(windows(),
            selection(node().cliHome(home).cliJavaHome(jdk)), Optional.of(jdk.toString()))) {
            Path directory = installation.directory();
            assertInstalled(installation);

            LaunchSpec spec = installation.launchSpec();
            String separator = home.getFileSystem().getSeparator();
            List<String> expected = new ArrayList<>(List.of(
                jdk.resolve("bin").resolve("java.exe").toString(), "-Xmx512m",
                "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3",
                "-Dsecretkey.path=" + home.resolve("conf"),
                "-Djava.io.tmpdir=" + directory.resolve("vendor")));
            expected.addAll(ADD_OPENS);
            expected.addAll(List.of("-cp",
                directory.resolve("helper") + ";" + home.resolve("lib") + separator + "*;"
                    + home.resolve("lib").resolve("shared") + separator + "*",
                ClientHelperPackagingTest.HELPER_CLASS));
            assertThat(spec.command()).containsExactlyElementsOf(expected);
            assertThat(spec.environment()).isEqualTo(StartCliEnvironment.windows(PARENT,
                jdk.toString(), TestCliRunners.SYSTEM_DIRECTORY));
            assertThat(spec.workingDirectory()).isEqualTo(home);
            leakCheck(spec);
        }
    }

    @Test
    void closingTwiceIsHarmlessAndAShutdownDeletesAnOpenInstallation() throws Exception {
        Path home = client("client");
        Path jdk = jdk();
        HelperInstallation first = HelperInstallation.install(host(),
            selection(node().cliHome(home)), Optional.of(jdk.toString()));
        HelperInstallation second = HelperInstallation.install(host(),
            selection(node().cliHome(home)), Optional.of(jdk.toString()));

        first.close();
        first.close();
        resources.close();

        assertThat(first.directory()).doesNotExist();
        assertThat(second.directory()).as("deleted by the shutdown").doesNotExist();
        assertThat(tempRoot).isEmptyDirectory();
    }

    // --- preconditions: CLI_UNAVAILABLE, nothing created (D-3, D-8) -----------------------

    @Test
    void withoutAClientHomeTheVersionEntryToAddIsNamed() throws Exception {
        Path other = client("client-90");

        ToolError error = refused(host(),
            selection(node().cliClient("9.0", other, Optional.empty())),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " int/a (INUBIT 8.1.17): no INUBIT client installation is configured");
        assertThat(error.likelyCause())
            .isEqualTo("Neither cli.home, defaults.cliClients.\"8.1\" nor cliHome is set");
        assertThat(error.nextStep()).isEqualTo("Add defaults.cliClients.\"8.1\".home (the"
            + " INUBIT 8.1 client directory) or set cliHome");
    }

    @Test
    void aClientHomeWithoutIbisJarIsRefused() throws Exception {
        Path home = client("client");
        Files.delete(home.resolve("lib").resolve("ibis.jar"));

        ToolError error = refused(host(),
            selection(node().cliClient("8.1", home, Optional.empty())),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " int/a (INUBIT 8.1.17): " + home.resolve("lib").resolve("ibis.jar")
            + " is missing or unreadable");
        assertThat(error.nextStep()).isEqualTo("Set defaults.cliClients.\"8.1\".home to the"
            + " INUBIT 8.1 client directory, which contains lib/ibis.jar");
    }

    @Test
    void withoutAJdkTheJdkSettingIsNamed() throws Exception {
        ToolError error = refused(host(), selection(node().cliHome(client("client"))),
            Optional.empty());

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " int/a (INUBIT 8.1.17): no JDK is configured");
        assertThat(error.likelyCause())
            .isEqualTo("Neither cli.javaHome (cliJavaHome) nor JAVA_HOME is set");
        assertThat(error.nextStep()).isEqualTo("Set cli.javaHome to the JDK the INUBIT client"
            + " needs (8.1: JDK 17, 9.0: JDK 21)");
    }

    @Test
    void aJdkWithoutAJavaExecutableIsRefused() throws Exception {
        Path jdk = Files.createDirectories(root.resolve("not-a-jdk").resolve("bin"))
            .getParent();

        ToolError error = refused(host(), selection(node().cliHome(client("client"))),
            Optional.of(jdk.toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " int/a (INUBIT 8.1.17): " + javaExecutable(jdk, Platform.windows())
            + " is not an executable file");
        assertThat(error.likelyCause())
            .isEqualTo("cli.javaHome (cliJavaHome) or JAVA_HOME does not point to a JDK");
    }

    @Test
    void aClassPathSeparatorInTheClientHomeIsRefusedOnPosix() throws Exception {
        assumeFalse(Platform.windows(), "':' is not allowed in a Windows file name");
        Path home = client("client:0001");

        ToolError error = refused(posix(), selection(node().cliHome(home)),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper cannot be started for"
            + " int/a (INUBIT 8.1.17): cli.home " + home + " contains ':', the class path"
            + " separator");
        assertThat(error.nextStep())
            .isEqualTo("Install the INUBIT client in a directory whose path has no ':'");
    }

    @Test
    void aClassPathSeparatorInTheClientHomeIsRefusedOnWindows() throws Exception {
        Path home = client("client;0001");

        ToolError error = refused(windows(), selection(node().cliHome(home)),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).endsWith(": cli.home " + home + " contains ';', the class"
            + " path separator");
    }

    @Test
    void aClassPathSeparatorInTheTemporaryDirectoryIsRefused() throws Exception {
        tempRoot = Files.createDirectories(root.resolve("tmp;0001"));

        ToolError error = refused(windows(), selection(node().cliHome(client("client"))),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).endsWith(": the temporary directory " + tempRoot
            + " contains ';', the class path separator");
        assertThat(error.nextStep()).isEqualTo("Start the MCP server with"
            + " -Djava.io.tmpdir=<a directory whose path has no ';'>");
    }

    @Test
    void anUnavailableContainmentIsRefused() throws Exception {
        HelperInstallation.Host host = new HelperInstallation.Host(true, PARENT,
            windowsSystem(), () -> new ProcessContainment.Unavailable("nested jobs refused"),
            resources, tempRoot);

        ToolError error = refused(host, selection(node().cliHome(client("client"))),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).endsWith(": its process could not be bound to the MCP"
            + " server (nested jobs refused)");
    }

    @Test
    void anUnreadableWindowsSystemDirectoryIsRefused() throws Exception {
        HelperInstallation.Host host = new HelperInstallation.Host(true, PARENT,
            new FixedSystem(new CliSystem.Unreadable("GetSystemDirectoryW failed"),
                StandardCharsets.UTF_8),
            ProcessContainment.Bound::new, resources, tempRoot);

        ToolError error = refused(host, selection(node().cliHome(client("client"))),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).endsWith(": the Windows system directory could not be"
            + " read (GetSystemDirectoryW failed)");
    }

    @Test
    void withoutPrivateFileSupportNothingIsLaunched() throws Exception {
        for (PrivateFileSystem files : List.of(PrivateFiles.failing(), unsupported())) {
            CliResources failing = new CliResources("acme", files);
            HelperInstallation.Host host = new HelperInstallation.Host(Platform.windows(),
                PARENT, hostSystem(), ProcessContainment.Bound::new, failing, tempRoot);

            ToolError error = refused(host, selection(node().cliHome(client("client"))),
                Optional.of(jdk().toString()));
            failing.close();

            assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
            assertThat(error.message()).startsWith("No private temporary directory for the"
                + " queue-entry helper on int/a (");
            assertThat(error.nextStep()).contains(tempRoot.toString());
        }
    }

    // --- failures after the directory exists: it is deleted -------------------------------

    @Test
    void aMissingHelperResourceIsInternalAndTheDirectoryIsDeleted() throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            ToolError error = refused(() -> HelperInstallation.install(host(),
                selection(node().cliHome(client("client"))), Optional.of(jdk().toString()),
                empty));

            assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
            assertThat(error.message()).isEqualTo("The queue-entry helper is missing from the"
                + " MCP server's build; it was not started for int/a");
            assertThat(error.likelyCause()).isEqualTo("The build is incomplete");
        }
    }

    @Test
    void aFailedCopyOfTheHelperIsUnavailableAndTheDirectoryIsDeleted() throws Exception {
        ClassLoader unreadable = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("read failed");
                    }
                };
            }
        };

        ToolError error = refused(() -> HelperInstallation.install(host(),
            selection(node().cliHome(client("client"))), Optional.of(jdk().toString()),
            unreadable));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("The queue-entry helper could not be copied into"
            + " its private temporary directory for int/a (IOException)");
        assertThat(error.likelyCause()).doesNotContain("cannot be restricted");
        assertThat(error.nextStep()).contains(tempRoot.toString());
    }

    @Test
    void afterTheShutdownNothingIsCreated() throws Exception {
        resources.close();

        ToolError error = refused(host(), selection(node().cliHome(client("client"))),
            Optional.of(jdk().toString()));

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).isEqualTo("The MCP server is shutting down; the queue-entry"
            + " helper was not started for int/a");
        assertThat(error.message()).doesNotContain("StartCLI");
    }

    // --- helpers --------------------------------------------------------------------------

    private void assertInstalled(HelperInstallation installation) throws IOException {
        Path directory = installation.directory();
        assertThat(directory.getParent()).isEqualTo(tempRoot);
        assertThat(directory.getFileName().toString()).matches("inubit-mcp-queue-acme-"
            + ProcessHandle.current().pid() + "-[0-9]+");
        assertThat(directory.resolve("vendor")).isEmptyDirectory();
        assertThat(installation.outDirectory()).isEqualTo(directory.resolve("out"))
            .isEmptyDirectory();
        assertThat(installation.trustStoreFile()).isEqualTo(directory.resolve("trust.p12"))
            .doesNotExist();
        try (InputStream packaged = getClass().getClassLoader().getResourceAsStream(
            ClientHelperPackagingTest.RESOURCE_DIRECTORY
                + ClientHelperPackagingTest.HELPER_FILE)) {
            assertThat(directory.resolve("helper")
                .resolve(ClientHelperPackagingTest.HELPER_FILE))
                .hasBinaryContent(packaged.readAllBytes());
        }
    }

    /** The refusal of {@link HelperInstallation#install}; nothing below the temporary root. */
    private ToolError refused(HelperInstallation.Host host, CliRunner.Selection selection,
        Optional<String> javaHome) throws IOException {
        return refused(() -> HelperInstallation.install(host, selection, javaHome));
    }

    private ToolError refused(Installing installing) throws IOException {
        ToolError error = catchThrowableOfType(ToolErrorException.class, installing::install)
            .error();
        assertThat(error.node()).contains(NODE);
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
        for (String text : List.of(error.message(), error.likelyCause(), error.nextStep())) {
            TestSecrets.assertNoLeak(text);
        }
        return error;
    }

    @FunctionalInterface
    private interface Installing {
        HelperInstallation install() throws Exception;
    }

    private static void leakCheck(LaunchSpec spec) {
        TestSecrets.assertNoLeak(String.join(" ", spec.command()));
        TestSecrets.assertNoLeak(spec.environment().toString());
    }

    private static TestNodeConfig node() {
        return TestNodeConfig.node().id("int/a");
    }

    private CliRunner.Selection selection(TestNodeConfig node) {
        EffectiveNodeConfig server = node.build();
        return runner.select(server, Optional.of(V81));
    }

    private HelperInstallation.Host host() {
        return Platform.windows() ? windows() : posix();
    }

    private HelperInstallation.Host posix() {
        return new HelperInstallation.Host(false, PARENT,
            new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
            ProcessContainment.Bound::new, resources, tempRoot);
    }

    private HelperInstallation.Host windows() {
        return new HelperInstallation.Host(true, PARENT, windowsSystem(),
            ProcessContainment.Bound::new, resources, tempRoot);
    }

    private static CliSystem windowsSystem() {
        return new FixedSystem(new CliSystem.Read(TestCliRunners.SYSTEM_DIRECTORY),
            Charset.forName("IBM850"));
    }

    private static CliSystem hostSystem() {
        return Platform.windows() ? windowsSystem()
            : new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8);
    }

    /** A client home with {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private Path client(String name) throws IOException {
        Path home = root.resolve(name);
        Files.createDirectories(home.resolve("lib").resolve("shared"));
        Files.createDirectories(home.resolve("conf"));
        Path ibis = home.resolve("lib").resolve("ibis.jar");
        if (Files.notExists(ibis)) {
            Files.createFile(ibis);
        }
        return home;
    }

    /** A JDK directory with an executable {@code bin/java} and {@code bin/java.exe}. */
    private Path jdk() throws IOException {
        Path jdk = root.resolve("jdk");
        Path bin = Files.createDirectories(jdk.resolve("bin"));
        for (String name : List.of("java", "java.exe")) {
            Path executable = bin.resolve(name);
            if (Files.notExists(executable)) {
                Files.createFile(executable);
                if (!Platform.windows()) {
                    Files.setPosixFilePermissions(executable,
                        PosixFilePermissions.fromString("rwx------"));
                }
            }
        }
        return jdk;
    }

    private static Path javaExecutable(Path jdk, boolean windows) {
        return jdk.resolve("bin").resolve(windows ? "java.exe" : "java");
    }

    /** A file system without private-file support: every call is unsupported. */
    private static PrivateFileSystem unsupported() {
        return (PrivateFileSystem) Proxy.newProxyInstance(
            PrivateFileSystem.class.getClassLoader(), new Class<?>[] {PrivateFileSystem.class},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException("no posix:permissions");
            });
    }
}
