package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.config.CredentialVariables;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T085: the inventory exports through StartCLI (research R-11) with {@link FakeProcessLauncher}:
 * commands, the owner-only temporary directory deleted in {@code finally}, the export timeout,
 * allow-listed entries, the required success message and the quoting rule.
 */
@Timeout(30)
class CliExportRunnerTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PASSWORD = "S3cr3t-export-pw";
    private static final Pattern EXPORT_FILE = Pattern.compile("--exportFile '([^']+)'");

    @TempDir
    Path cliHome;
    @TempDir
    Path tempRoot;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T08:00:00Z"));
    private final CredentialGuard guard =
        new CredentialGuard(new CredentialVariables("INUBIT", DEV), clock);
    /** What the fake StartCLI saw at launch: the export directory and its permissions. */
    private final List<Set<PosixFilePermission>> launchPermissions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void installFakeCli() throws IOException {
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, "#!/bin/sh\n");
    }

    private TestNodeConfig server() {
        return TestNodeConfig.node().cliHome(cliHome).cliJavaHome(Path.of("/opt/jdk-17"));
    }

    private static NodeCredentials credentials() {
        return new NodeCredentials(DEV,
            Optional.of(new SourcedValue<>("jdoe", "INUBIT_DEV_USERNAME")),
            Optional.of(new SourcedValue<>(Secret.of(PASSWORD), "INUBIT_DEV_PASSWORD")),
            Optional.empty(), Optional.empty());
    }

    private CliExportRunner exports(FakeProcessLauncher launcher, EffectiveNodeConfig config) {
        return exports(launcher, config, credentials(), false);
    }

    private CliExportRunner exports(FakeProcessLauncher launcher, EffectiveNodeConfig config,
        NodeCredentials credentials, boolean windows) {
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), windows,
            new CliResources("acme"));
        return new CliExportRunner(config, credentials, guard, runner,
            new CliOutputClassifier(new SecretScrubber()), tempRoot);
    }

    /** StartCLI that writes {@code zip} to the export file, as the real one does. */
    private FakeProcessLauncher writing(String fixtureCase, byte[] zip) {
        return FakeProcessLauncher.replaying(fixtureCase).onLaunch(spec -> {
            Path file = exportFile(spec);
            try {
                launchPermissions.add(Files.getPosixFilePermissions(file.getParent()));
                Files.write(file, zip);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String execCommand(LaunchSpec spec) {
        List<String> command = spec.command();
        return command.get(command.indexOf("--execCommand") + 1);
    }

    private static Path exportFile(LaunchSpec spec) {
        Matcher matcher = EXPORT_FILE.matcher(execCommand(spec));
        assertThat(matcher.find()).as(execCommand(spec)).isTrue();
        return Path.of(matcher.group(1));
    }

    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        throw new AssertionError("no entry " + name);
    }

    private List<Path> leftovers() throws IOException {
        try (Stream<Path> files = Files.list(tempRoot)) {
            return files.toList();
        }
    }

    private static ToolError errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolErrorException e) {
            assertThat(e.error().toString()).doesNotContain(PASSWORD);
            return e.error();
        }
        throw new AssertionError("expected a ToolErrorException");
    }

    @Test
    void theHistoryExportRunsTheQuotedCommandAndReturnsVersionHistoryXml() throws IOException {
        byte[] zip = FakeProcessLauncher.fixture("export_history_sample.zip");
        FakeProcessLauncher launcher = writing("export_history_sample", zip);

        byte[] xml = exports(launcher, server().build())
            .exportHistory("OWNERS", "technical", "GRP-41");

        assertThat(xml).isEqualTo(entry(zip, "versionHistory.xml"));
        String command = execCommand(launcher.last().spec());
        Path file = exportFile(launcher.last().spec());
        assertThat(command).isEqualTo("export --exportWorkflowUser 'OWNERS' --exportWorkflowType"
            + " 'technical' --exportWorkflowGroup 'GRP-41' --includeHistory --exportFile '"
            + file + "'");
        assertThat(file.getFileName()).hasToString("history.zip");
        assertThat(file.getParent().getParent()).isEqualTo(tempRoot);
        // 002 research D-8: the runner's profile and this process's pid
        assertThat(file.getParent().getFileName().toString())
            .startsWith("inubit-mcp-export-acme-" + ProcessHandle.current().pid() + "-");
        assertThat(launcher.last().spec().command()).doesNotContain(PASSWORD);
        assertThat(new String(launcher.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\n");
    }

    @Test
    void theModuleExportPassesTheLiteralEmptyArgumentsInsideTheExecCommand() throws IOException {
        byte[] zip = FakeProcessLauncher.fixture("export_modules_sample.zip");
        FakeProcessLauncher launcher = writing("export_modules_sample", zip);

        byte[] xml = exports(launcher, server().build()).exportModules("OWNERS");

        assertThat(xml).isEqualTo(entry(zip, "module/module.xml"));
        Path file = exportFile(launcher.last().spec());
        assertThat(execCommand(launcher.last().spec())).isEqualTo("export --exportModule ''"
            + " --exportModuleGroup '' --exportModuleUser 'OWNERS' --exportFile '" + file + "'");
        assertThat(file.getFileName()).hasToString("modules.zip");
        assertThat(launcher.last().spec().command()).filteredOn(argument -> argument.equals("''"))
            .as("'' is part of the single --execCommand argument").isEmpty();
    }

    @Test
    void theTemporaryDirectoryIsOwnerOnlyDuringTheRunAndDeletedAfterwards() throws IOException {
        FakeProcessLauncher launcher = writing("export_modules_sample",
            FakeProcessLauncher.fixture("export_modules_sample.zip"));

        exports(launcher, server().build()).exportModules("OWNERS");

        assertThat(launchPermissions).containsExactly(PosixFilePermissions.fromString(
            "rwx------"));
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theDirectoryIsAlsoDeletedWhenStartCliFails() throws IOException {
        FakeProcessLauncher launcher = writing("login_failed", new byte[] {1, 2, 3});

        ToolError error =
            errorOf(() -> exports(launcher, server().build()).exportModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(DEV);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theExportTimeoutIsCliExportTimeoutAndTheDirectoryIsDeletedOnTimeout()
        throws IOException {
        FakeProcessLauncher launcher = writing("export_modules_sample", new byte[] {1})
            .hanging();
        EffectiveNodeConfig config = server().cliTimeout(Duration.ofSeconds(20))
            .cliExportTimeout(Duration.ofMillis(300)).build();

        long start = System.nanoTime();
        ToolError error = errorOf(() -> exports(launcher, config).exportModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("cliExportTimeout").doesNotContain("raise cliTimeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start))
            .isLessThan(Duration.ofSeconds(10));
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theDirectoryIsDeletedWhenTheCallIsInterrupted() throws Exception {
        FakeProcessLauncher launcher = writing("export_modules_sample", new byte[] {1})
            .hanging();
        CliExportRunner exports = exports(launcher, server().build());
        AtomicReference<ToolError> error = new AtomicReference<>();

        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(
            () -> exports.exportModules("OWNERS"))));
        while (launcher.launchCount() == 0) {
            Thread.sleep(10);
        }
        call.interrupt();
        call.join(Duration.ofSeconds(10));

        assertThat(error.get().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void onlyTheAllowListedEntryIsReadFromTheExport() throws IOException {
        byte[] zip = zip(Map.of(
            "versionHistory.xml", "<VersionInformation/>",
            "Repository.zip", "binary",
            "module/Secret-Module.xml", "<config password='x'/>"));
        FakeProcessLauncher launcher = writing("export_history_sample", zip);

        byte[] xml = exports(launcher, server().build()).exportHistory("OWNERS", "technical", "G");

        assertThat(new String(xml, StandardCharsets.UTF_8)).isEqualTo("<VersionInformation/>");
        assertThat(CliExportRunner.ALLOWED_ENTRIES).containsExactlyInAnyOrder(
            "versionHistory.xml", "module/module.xml", "workflow/workflow.xml");
        assertThatThrownBy(() -> CliExportRunner.readEntry(DEV, tempRoot.resolve("x.zip"),
            "Repository.zip")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExportWithoutTheEntryOrWithoutFileIsAnUnexpectedResponse() throws IOException {
        FakeProcessLauncher noEntry = writing("export_history_sample",
            zip(Map.of("module/module.xml", "<x/>")));
        ToolError missingEntry = errorOf(() -> exports(noEntry, server().build())
            .exportHistory("OWNERS", "technical", "G"));
        assertThat(missingEntry.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(missingEntry.message()).contains("versionHistory.xml");

        FakeProcessLauncher noFile = FakeProcessLauncher.replaying("export_history_sample");
        ToolError missingFile = errorOf(() -> exports(noFile, server().build())
            .exportHistory("OWNERS", "technical", "G"));
        assertThat(missingFile.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theSuccessMessageExportedSuccessfullyIsRequired() throws IOException {
        FakeProcessLauncher launcher = FakeProcessLauncher.of(
            "1-OK: Something else happened.\n", "", 0).onLaunch(spec -> {
                try {
                    Files.write(exportFile(spec), FakeProcessLauncher.fixture(
                        "export_modules_sample.zip"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

        ToolError error =
            errorOf(() -> exports(launcher, server().build()).exportModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.node()).contains(DEV);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void valuesOutsideTheQuotingRuleAreRejectedBeforeStartCliIsLaunched() throws IOException {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("export_history_sample");
        CliExportRunner exports = exports(launcher, server().build());

        for (Runnable call : List.<Runnable>of(
            () -> exports.exportHistory("OWNERS", "technical", "O'Brien"),
            () -> exports.exportHistory("OWNERS", "technical", "-x"),
            () -> exports.exportHistory("OWNERS", "technical", ""),
            () -> exports.exportHistory("OWNERS", "tech'nical", "G"),
            () -> exports.exportHistory("P'KP", "technical", "G"),
            () -> exports.exportModules("P'KP"))) {
            ToolError error = errorOf(call);
            assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
            assertThat(error.message()).contains("not supported by CLI quoting");
            assertThat(error.node()).contains(DEV);
        }
        assertThat(launcher.launchCount()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void aGroupWithASpaceIsSingleQuoted() {
        FakeProcessLauncher launcher = writing("export_history_sample",
            zip(Map.of("versionHistory.xml", "<VersionInformation/>")));

        exports(launcher, server().build()).exportHistory("OWNERS", "technical", "OWNERS Group 1");

        assertThat(execCommand(launcher.last().spec()))
            .contains("--exportWorkflowGroup 'OWNERS Group 1' --includeHistory");
    }

    @Test
    void withoutCliHomeOrOnWindowsTheCliIsUnavailableAndNothingIsLaunched() throws IOException {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("export_modules_sample");
        EffectiveNodeConfig noHome = TestNodeConfig.node().build();

        ToolError missingHome = errorOf(() -> exports(launcher, noHome).exportModules("OWNERS"));
        ToolError windows = errorOf(() -> exports(launcher, server().build(), credentials(),
            true).exportModules("OWNERS"));

        assertThat(missingHome.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(windows.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void missingCredentialsAreAnAuthFailureWithoutLaunch() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("export_modules_sample");
        NodeCredentials none = new NodeCredentials(DEV, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

        ToolError error = errorOf(() -> exports(launcher, server().build(), none, false)
            .exportModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.nextStep()).contains("INUBIT_DEV_NODE1_PASSWORD");
        assertThat(launcher.launchCount()).isZero();
    }

    @Test
    void theExportsShareTheServersCredentialGuard() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("login_failed");
        CliExportRunner exports = exports(launcher, server().build());

        errorOf(() -> exports.exportModules("OWNERS"));
        ToolError cached = errorOf(() -> exports.exportHistory("OWNERS", "technical", "G"));

        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.message()).contains("avoid account lockout");
        assertThat(launcher.launchCount()).isEqualTo(1);
    }

    @Test
    void anEntryAboveTheCapIsAnUnexpectedResponse() throws IOException {
        Path zip = tempRoot.resolve("big.zip");
        Files.write(zip, zip(Map.of("module/module.xml", "x".repeat(1_000))));

        ToolError error = errorOf(() -> CliExportRunner.readEntry(DEV, zip, "module/module.xml",
            100));

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).contains("too large");
        assertThat(CliExportRunner.readEntry(DEV, zip, "module/module.xml", 1_000)).hasSize(1_000);
        assertThat(CliExportRunner.MAX_ENTRY_BYTES).isEqualTo(64L << 20);
    }

    @Test
    void aTemporaryDirectoryThatCannotBePassedToStartCliMakesTheCliUnavailable()
        throws IOException {
        Path odd = Files.createDirectories(tempRoot.resolve("tmp~dir"));
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("export_modules_sample");
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false,
            new CliResources("acme"));
        CliExportRunner exports = new CliExportRunner(server().build(), credentials(), guard,
            runner, new CliOutputClassifier(new SecretScrubber()), odd);

        ToolError error = errorOf(() -> exports.exportModules("OWNERS"));

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.nextStep()).contains("java.io.tmpdir");
        assertThat(error.node()).contains(DEV);
        assertThat(launcher.launchCount()).isZero();
        try (Stream<Path> files = Files.list(odd)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void closingTheCliResourcesStopsARunningExportAndDeletesItsDirectory() throws Exception {
        FakeProcessLauncher launcher = writing("export_modules_sample", new byte[] {1})
            .hanging();
        CliResources resources = new CliResources("acme");
        CliRunner runner = new CliRunner(launcher, Map.of("PATH", "/usr/bin"), false, resources);
        CliExportRunner exports = new CliExportRunner(server().build(), credentials(), guard,
            runner, new CliOutputClassifier(new SecretScrubber()), tempRoot);
        AtomicReference<ToolError> error = new AtomicReference<>();
        Thread call = Thread.ofVirtual().start(() -> error.set(errorOf(
            () -> exports.exportModules("OWNERS"))));
        while (launcher.launchCount() == 0 || leftovers().isEmpty()) {
            Thread.sleep(10);
        }

        resources.close();
        call.join(Duration.ofSeconds(10));

        assertThat(call.isAlive()).isFalse();
        assertThat(launcher.last().destroyed()).isTrue();
        assertThat(error.get().message()).contains("shutting down");
        assertThat(leftovers()).isEmpty();
    }

    @Test
    void theHistoryCheckRejectsWhatTheExportWouldRejectWithoutLaunching() {
        FakeProcessLauncher launcher = FakeProcessLauncher.replaying("export_history_sample");
        CliExportRunner exports = exports(launcher, server().build());

        exports.checkHistoryExport("OWNERS", "technical", "GRP-41");
        exports.checkModuleExport("OWNERS");
        ToolError quoting = errorOf(() -> exports.checkHistoryExport("OWNERS", "technical", "a'b"));
        ToolError noCli = errorOf(() -> exports(launcher, TestNodeConfig.node().build())
            .checkModuleExport("OWNERS"));

        assertThat(quoting.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(noCli.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
    }
}
