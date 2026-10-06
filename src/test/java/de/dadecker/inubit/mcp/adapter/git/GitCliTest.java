package de.dadecker.inubit.mcp.adapter.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.PathChange.Kind;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T009 (research D-1, FR-006, FR-007): the workspace history through the real {@code git} in a
 * temporary directory, isolated from hooks, signing and the user's git configuration.
 */
class GitCliTest {

    private static final List<String> FIXED_OPTIONS = List.of(
        "-c", "commit.gpgsign=false", "-c", "core.autocrlf=false", "-c", "core.quotepath=false",
        "-c", "user.name=INUBIT MCP (acme)", "-c", "user.email=inubit-mcp@localhost");

    @TempDir
    Path root;

    private final List<ProcessLauncher.LaunchSpec> launches = new CopyOnWriteArrayList<>();
    private final Map<String, String> parentEnvironment = new HashMap<>();
    private GitCli git;

    @BeforeEach
    void setUp() {
        parentEnvironment.putAll(Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin"),
            "HOME", root.toString(), "INUBIT_ACME_DEV_PASSWORD", "must-not-reach-git",
            "GIT_DIR", "/tmp/elsewhere"));
        SystemProcessLauncher system = new SystemProcessLauncher();
        git = new GitCli(root, "acme", spec -> {
            launches.add(spec);
            return system.launch(spec);
        }, parentEnvironment);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Runs plain git (outside the class under test) to inspect the repository. */
    private String inspect(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("git %s: %s", String.join(" ", arguments), output)
            .isZero();
        return output;
    }

    @Test
    void initCreatesTheRepositoryAndIgnoresTheLocalAreasIdempotently() throws IOException {
        git.init();
        git.init();

        assertThat(root.resolve(".git")).isDirectory();
        assertThat(Files.readAllLines(root.resolve(".gitignore")))
            .contains(".tests/", ".reports/", ".lock");
        write(".tests/dev/out.xml", "x");
        write(".reports/export-1.txt", "x");
        write(".lock", "");
        assertThat(git.status()).extracting(PathChange::path).containsExactly(".gitignore");
    }

    @Test
    void commitAllRecordsEveryChangeOnceAndReturnsEmptyWhenNothingChanged()
        throws IOException, InterruptedException {
        git.init();
        write("dev/OWNERS/workflows/GRP-01/Workflow-0001.xml", "<Workflow/>\n");
        write("dev/OWNERS/modules/XSLT Converter/Größe 1/module.xml", "<Properties/>\n");

        Optional<HistoryEntry> first = git.commitAll("export dev/node1: GRP-01 (3 files)");

        assertThat(first).isPresent();
        assertThat(first.get().message()).isEqualTo("export dev/node1: GRP-01 (3 files)");
        assertThat(first.get().changes()).containsExactlyInAnyOrder(
            new PathChange(".gitignore", Kind.ADDED),
            new PathChange("dev/OWNERS/workflows/GRP-01/Workflow-0001.xml", Kind.ADDED),
            new PathChange("dev/OWNERS/modules/XSLT Converter/Größe 1/module.xml", Kind.ADDED));
        assertThat(inspect("rev-parse", "--short", "HEAD").strip())
            .isEqualTo(first.get().commit());
        assertThat(inspect("log", "-1", "--format=%an <%ae>|%cn <%ce>").strip()).isEqualTo(
            "INUBIT MCP (acme) <inubit-mcp@localhost>|INUBIT MCP (acme) <inubit-mcp@localhost>");
        assertThat(git.commitAll("again")).isEmpty();
        assertThat(git.status()).isEmpty();
    }

    @Test
    void statusAndCommitsReportAddedModifiedAndDeletedFiles() throws IOException {
        git.init();
        write("dev/a.xml", "a");
        write("dev/b.xml", "b");
        git.commitAll("first");
        write("dev/a.xml", "a2");
        Files.delete(root.resolve("dev/b.xml"));
        write("dev/c d.xml", "c");

        assertThat(git.status()).containsExactlyInAnyOrder(
            new PathChange("dev/a.xml", Kind.MODIFIED), new PathChange("dev/b.xml", Kind.DELETED),
            new PathChange("dev/c d.xml", Kind.ADDED));
        assertThat(git.commitAll("local changes: 3 files").orElseThrow().changes())
            .containsExactlyInAnyOrder(new PathChange("dev/a.xml", Kind.MODIFIED),
                new PathChange("dev/b.xml", Kind.DELETED),
                new PathChange("dev/c d.xml", Kind.ADDED));
    }

    @Test
    void hooksSigningAndLineEndingSettingsOfTheRepositoryHaveNoEffect()
        throws IOException, InterruptedException {
        git.init();
        Path marker = root.resolve("hook-ran");
        Path hook = root.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwx------"));
        inspect("config", "core.hooksPath", ".git/hooks");
        inspect("config", "commit.gpgsign", "true");
        inspect("config", "core.autocrlf", "true");
        write("dev/crlf.xml", "line1\r\nline2\r\n");

        HistoryEntry entry = git.commitAll("export").orElseThrow();

        assertThat(marker).doesNotExist();
        assertThat(inspect("cat-file", "-p", entry.commit() + ":dev/crlf.xml"))
            .isEqualTo("line1\r\nline2\r\n");
    }

    @Test
    void restoreBringsASubtreeBackToTheLastEntryAndLeavesTheRestAlone() throws IOException {
        git.init();
        write("dev/OWNERS/workflows/G/W1.xml", "w1");
        write("dev/OWNERS/workflows/G/W2.xml", "w2");
        write("test/OWNERS/workflows/G/W1.xml", "t1");
        git.commitAll("export");
        write("dev/OWNERS/workflows/G/W1.xml", "broken");
        Files.delete(root.resolve("dev/OWNERS/workflows/G/W2.xml"));
        write("dev/OWNERS/workflows/G/W3.xml", "half written");
        write("dev/OWNERS/workflows/G/new/deep.xml", "half written");
        write("test/OWNERS/workflows/G/W1.xml", "kept");

        git.restore(Path.of("dev/OWNERS/workflows/G"));

        assertThat(root.resolve("dev/OWNERS/workflows/G/W1.xml")).hasContent("w1");
        assertThat(root.resolve("dev/OWNERS/workflows/G/W2.xml")).hasContent("w2");
        assertThat(root.resolve("dev/OWNERS/workflows/G/W3.xml")).doesNotExist();
        assertThat(root.resolve("dev/OWNERS/workflows/G/new")).doesNotExist();
        assertThat(root.resolve("test/OWNERS/workflows/G/W1.xml")).hasContent("kept");
        assertThat(git.status()).containsExactly(
            new PathChange("test/OWNERS/workflows/G/W1.xml", Kind.MODIFIED));
    }

    @Test
    void restoringASubtreeThatWasNeverCommittedRemovesIt() throws IOException {
        git.init();
        git.commitAll("init");
        write("dev/OWNERS/modules/T/M/module.xml", "half written");

        git.restore(Path.of("dev/OWNERS/modules/T/M"));

        assertThat(root.resolve("dev/OWNERS/modules/T/M")).doesNotExist();
        assertThat(git.status()).isEmpty();
    }

    @Test
    void restoreOnlyTakesSubtreesInsideTheWorkspace() {
        git.init();
        for (String subtree : List.of("", "/etc", "../outside", "dev/../..", ".git")) {
            assertThatIllegalArgumentException().as(subtree)
                .isThrownBy(() -> git.restore(Path.of(subtree)));
        }
    }

    @Test
    void everyCallUsesTheFixedOptionsAndAMinimalEnvironment() throws IOException {
        git.init();
        write("dev/a.xml", "a");
        git.commitAll("export");
        git.status();
        git.restore(Path.of("dev"));

        assertThat(launches).hasSizeGreaterThanOrEqualTo(5).allSatisfy(spec -> {
            assertThat(spec.command().get(0)).isEqualTo("git");
            assertThat(spec.command().get(1)).isEqualTo("-c");
            assertThat(spec.command().get(2)).startsWith("core.hooksPath=");
            Path hooks = Path.of(spec.command().get(2).substring("core.hooksPath=".length()));
            assertThat(hooks).isAbsolute().isEmptyDirectory();
            assertThat(spec.command().subList(3, 3 + FIXED_OPTIONS.size()))
                .isEqualTo(FIXED_OPTIONS);
            assertThat(spec.workingDirectory()).isEqualTo(root);
            assertThat(spec.environment()).containsEntry("GIT_TERMINAL_PROMPT", "0")
                .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null")
                .containsEntry("PATH", parentEnvironment.get("PATH"))
                .doesNotContainKeys("INUBIT_ACME_DEV_PASSWORD", "GIT_DIR", "HOME");
        });
    }

    @Test
    void theHistoryIsNeverTransmitted() {
        // FR-007: there is no method for a remote, push, fetch or clone
        assertThat(Arrays.stream(GitCli.class.getDeclaredMethods()).map(Method::getName)
            .map(name -> name.toLowerCase(Locale.ROOT)))
            .noneMatch(name -> name.contains("remote") || name.contains("push")
                || name.contains("fetch") || name.contains("clone") || name.contains("pull"));
        assertThat(launches).noneMatch(spec -> spec.command().stream().anyMatch(argument ->
            List.of("remote", "push", "fetch", "clone", "pull").contains(argument)));
    }

    @Test
    void aMissingGitIsAPreconditionFailure() {
        GitCli missing = new GitCli(root, "acme", new SystemProcessLauncher(), parentEnvironment,
            root.resolve("no-such-git").toString(), Duration.ofSeconds(30));

        assertThatThrownBy(missing::init).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message()).contains("git not found");
        });
    }

    @Test
    void aGitThatDoesNotFinishIsStoppedAndReportedAsTimeout() {
        FakeProcessLauncher hanging = FakeProcessLauncher.of("", "", 0).hanging();
        GitCli slow = new GitCli(root, "acme", hanging, parentEnvironment, "git",
            Duration.ofMillis(200));

        assertThatThrownBy(slow::status).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertThat(e.error().code()).isEqualTo(ErrorCode.TIMEOUT));
        assertThat(hanging.last().destroyed()).isTrue();
    }

    @Test
    void aFailingGitCommandIsAPreconditionFailureNamingTheCommand() {
        GitCli failing = new GitCli(root, "acme", FakeProcessLauncher.of("",
            "fatal: not a git repository", 128), parentEnvironment, "git",
            Duration.ofSeconds(30));

        assertThatThrownBy(failing::status).isInstanceOfSatisfying(ToolErrorException.class,
            e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("git status", root.toString());
                assertThat(e.error().excerpt()).contains("fatal: not a git repository");
            });
    }
}
