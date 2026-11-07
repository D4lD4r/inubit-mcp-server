package de.dadecker.inubit.mcp.adapter.cli;

import static de.dadecker.inubit.mcp.adapter.TestNodeConfig.storeLabels;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ImportPort.Mode;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestPermits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T017 (feature 013, SC-003, FR-003): export, import and history calls of
 * {@link CliExportRunner} and {@link CliImportRunner} through the host's real launch and the
 * substitute StartCLI. No credential occurs in the arguments StartCLI received, in its
 * environment or in any file the call wrote; the environment holds only the allowlist (plus what
 * the interpreter itself adds); the password arrived on standard input as UTF-8 plus {@code \n}.
 * {@link WindowsSecretInspectionTest} runs the cases on Windows and also inspects
 * {@code %CMDCMDLINE%}; {@link PosixSecretInspectionTest} is the POSIX twin.
 */
@Timeout(60)
abstract class SecretInspectionCases {

    static final String PASSWORD = "S3cr3t-inspect-pw";
    /** A credential in the server's own environment that must not reach StartCLI. */
    static final String TOKEN = "ghp_inspect-canary-token";
    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final WritePermit PERMIT = TestPermits.of(DEV, Capability.DEVELOPMENT_WRITE);
    private static final byte[] ARCHIVE = {'P', 'K', 3, 4, 'i', 'm', 'p'};

    @TempDir
    Path directory;

    private Path cliHome;
    private Path tempRoot;
    private final RecordingLauncher launcher = new RecordingLauncher();
    /** Files under the temp directory at the launch and when StartCLI exited (review T017 r1). */
    private final Set<Path> seenDuringTheCall = new TreeSet<>();
    private final Set<Path> heldACredentialDuringTheCall = new TreeSet<>();
    private final CredentialGuard guard = new CredentialGuard(storeLabels("INUBIT", DEV),
        new MutableClock(Instant.parse("2026-10-01T08:00:00Z")));

    /** The names the server may set for StartCLI on this platform (the allowlist). */
    abstract Set<String> allowlist();

    /** Whether the interpreter or shell running the script adds {@code name} by itself. */
    abstract boolean addedByTheInterpreter(String name);

    /** Platform-specific inspection of the run (Windows: {@code %CMDCMDLINE%}). */
    abstract void inspectPlatform(FakeStartCli cli, ProcessLauncher.LaunchSpec spec)
        throws IOException;

    /** Names compared as the platform does (case-insensitively on Windows). */
    abstract String normalized(String name);

    @BeforeEach
    void layout() throws IOException {
        cliHome = Files.createDirectories(directory.resolve("client"));
        tempRoot = Files.createDirectories(directory.resolve("tmp"));
    }

    @Test
    void theModuleExportPassesNoCredentialOutsideStandardInput() throws IOException {
        FakeStartCli cli = FakeStartCli.replayingExport(cliHome, "export_modules_sample");

        byte[] xml = exports().exportModules("OWNERS");

        assertThat(xml).isEqualTo(entry("export_modules_sample.zip", "module/module.xml"));
        inspect(cli);
    }

    @Test
    void theHistoryExportPassesNoCredentialOutsideStandardInput() throws IOException {
        FakeStartCli cli = FakeStartCli.replayingExport(cliHome, "export_history_sample");

        byte[] xml = exports().exportHistory("OWNERS", "technical", "GRP-41");

        assertThat(xml).isEqualTo(entry("export_history_sample.zip", "versionHistory.xml"));
        inspect(cli);
    }

    @Test
    void theImportPassesNoCredentialOutsideStandardInput() throws IOException {
        FakeStartCli cli = FakeStartCli.replaying(cliHome, "import_created");

        ImportProtocol protocol = imports().importArchive(ARCHIVE, Mode.WORKFLOW, "jdoe", PERMIT);

        assertThat(protocol.total()).isEqualTo(5);
        inspect(cli);
    }

    private void inspect(FakeStartCli cli) throws IOException {
        assertThat(launcher.launched).hasSize(1);
        ProcessLauncher.LaunchSpec spec = launcher.launched.get(0);
        FakeStartCli.Recorded record = cli.record();

        assertThat(record.args()).containsExactlyElementsOf(spec.startCliArguments());
        assertThat(record.args()).noneMatch(SecretInspectionCases::holdsACredential);
        assertThat(spec.command()).noneMatch(SecretInspectionCases::holdsACredential);
        assertThat(new String(record.stdin(), StandardCharsets.UTF_8)).isEqualTo(PASSWORD + "\n");

        assertThat(names(spec.environment().keySet())).as("what the server sets")
            .isSubsetOf(names(allowlist()));
        assertThat(spec.environment().values()).noneMatch(SecretInspectionCases::holdsACredential);
        Set<String> received = new TreeSet<>(names(record.environment().keySet()));
        received.removeAll(names(spec.environment().keySet()));
        assertThat(received).as("what StartCLI received beyond what the server set")
            .allMatch(this::addedByTheInterpreter);
        assertThat(record.environment().values())
            .noneMatch(SecretInspectionCases::holdsACredential);

        assertThat(seenDuringTheCall).as("temporary files while StartCLI ran").isNotEmpty();
        assertThat(heldACredentialDuringTheCall).as("temporary files while StartCLI ran")
            .isEmpty();
        assertThat(filesHoldingACredential()).as("files written during the call").isEmpty();
        try (Stream<Path> left = Files.list(tempRoot)) {
            assertThat(left).as("temporary data").isEmpty();
        }
        inspectPlatform(cli, spec);
    }

    static boolean holdsACredential(String value) {
        return value.contains(PASSWORD) || value.contains(TOKEN);
    }

    private Set<String> names(Set<String> names) {
        Set<String> normalized = new TreeSet<>();
        names.forEach(name -> normalized.add(normalized(name)));
        return normalized;
    }

    /** Every file below the test directory that holds a credential (UTF-8 or UTF-16LE). */
    private List<Path> filesHoldingACredential() throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (fileHoldsACredential(path)) {
                    found.add(path);
                }
            }
        }
        return found;
    }

    private static boolean fileHoldsACredential(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return holdsACredential(new String(bytes, StandardCharsets.UTF_8))
            || holdsACredential(new String(bytes, StandardCharsets.UTF_16LE));
    }

    /**
     * Records the files under the temp directory: the runner deletes its directory before the
     * call returns, so the files (the import archive, the export file) are read while they exist.
     */
    private void scanTempRoot() throws IOException {
        try (Stream<Path> paths = Files.walk(tempRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                seenDuringTheCall.add(tempRoot.relativize(path));
                if (fileHoldsACredential(path)) {
                    heldACredentialDuringTheCall.add(tempRoot.relativize(path));
                }
            }
        }
    }

    private EffectiveNodeConfig server() {
        return TestNodeConfig.node().cliHome(cliHome)
            .cliJavaHome(Path.of(System.getProperty("java.home"))).build();
    }

    private NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    /** The host's environment plus credentials the server holds in its own environment. */
    private CliRunner runner() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("INUBIT_DEV_PASSWORD", PASSWORD);
        environment.put("GITHUB_TOKEN", TOKEN);
        return TestCliRunners.forHost(launcher, environment,
            new CliResources("acme", PrivateFiles.forHost()));
    }

    private CliExportRunner exports() {
        return new CliExportRunner(server(), credentials(), guard, runner(),
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    private CliImportRunner imports() {
        return new CliImportRunner(server(), credentials(), guard, runner(),
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    private static byte[] entry(String fixture, String name) throws IOException {
        byte[] zip;
        try (InputStream in = SecretInspectionCases.class.getResourceAsStream(
            "/fixtures/v8_1/cli/" + fixture)) {
            assertThat(in).as(fixture).isNotNull();
            zip = in.readAllBytes();
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        throw new AssertionError("no entry " + name + " in " + fixture);
    }

    static String lowerCase(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * The real launcher, remembering what it was asked to start; scans the temp directory at the
     * launch and once the process has exited.
     */
    private final class RecordingLauncher implements ProcessLauncher {

        final List<LaunchSpec> launched = new ArrayList<>();
        private final SystemProcessLauncher system = new SystemProcessLauncher();

        @Override
        public LaunchedProcess launch(LaunchSpec spec) throws IOException {
            launched.add(spec);
            scanTempRoot();
            LaunchedProcess process = system.launch(spec);
            return new LaunchedProcess() {
                @Override
                public OutputStream stdin() {
                    return process.stdin();
                }

                @Override
                public InputStream stdout() {
                    return process.stdout();
                }

                @Override
                public InputStream stderr() {
                    return process.stderr();
                }

                @Override
                public boolean waitFor(Duration timeout) throws InterruptedException {
                    boolean exited = process.waitFor(timeout);
                    if (exited) {
                        try {
                            scanTempRoot();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    return exited;
                }

                @Override
                public int exitValue() {
                    return process.exitValue();
                }

                @Override
                public boolean isAlive() {
                    return process.isAlive();
                }

                @Override
                public void destroy() {
                    process.destroy();
                }

                @Override
                public boolean treeAlive() {
                    return process.treeAlive();
                }

                @Override
                public void destroyForcibly() {
                    process.destroyForcibly();
                }
            };
        }
    }
}
