package de.dadecker.inubit.mcp.adapter.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.HistoryEntry;
import de.dadecker.inubit.mcp.domain.model.PathChange;
import de.dadecker.inubit.mcp.domain.model.PathChange.Kind;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.VersionHistoryPort;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
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
        "-c", "gc.autoDetach=false", "-c", "maintenance.autoDetach=false",
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
                .containsEntry("GIT_LITERAL_PATHSPECS", "1")
                .containsEntry("LC_ALL", "C")
                .containsEntry("PATH", parentEnvironment.get("PATH"))
                .doesNotContainKeys("INUBIT_ACME_DEV_PASSWORD", "GIT_DIR", "HOME");
        });
    }

    @Test
    void theHistoryIsNeverTransmitted() throws IOException {
        git.init();
        write("dev/a.xml", "a");
        git.commitAll("export");
        git.status();
        git.restore(Path.of("dev"));

        assertThat(launches).hasSizeGreaterThanOrEqualTo(5);
        // FR-007: there is no method for a remote, push, fetch or clone
        assertThat(Arrays.stream(GitCli.class.getDeclaredMethods()).map(Method::getName)
            .map(name -> name.toLowerCase(Locale.ROOT)))
            .noneMatch(name -> name.contains("remote") || name.contains("push")
                || name.contains("fetch") || name.contains("clone") || name.contains("pull"));
        assertThat(launches).noneMatch(spec -> spec.command().stream().anyMatch(argument ->
            List.of("remote", "push", "fetch", "clone", "pull").contains(argument)));
    }

    @Test
    void theAutomaticMaintenanceACommitTriggersHasFinishedWhenTheCallReturns()
        throws IOException, InterruptedException, NoSuchAlgorithmException {
        git.init();
        // Tiny thresholds in the repository's own configuration: the gc strategy (older git)
        // repacks once objects/17 holds two loose objects, the geometric strategy every time;
        // a repository-local autoDetach must not move the maintenance to the background either.
        inspect("config", "gc.auto", "1");
        inspect("config", "maintenance.geometric-repack.auto", "-1");
        inspect("config", "maintenance.autoDetach", "true");
        for (String content : contentsWhoseBlobIdStartsWith17(2)) {
            write("dev/" + content.strip() + ".txt", content);
        }

        git.commitAll("export").orElseThrow();

        Path objects = root.resolve(".git/objects");
        assertThat(objects.resolve("maintenance.lock")).as("maintenance still running")
            .doesNotExist();
        assertThat(root.resolve(".git/gc.pid")).as("gc still running").doesNotExist();
        assertThat(objects.resolve("17")).as("loose objects not yet packed")
            .satisfiesAnyOf(directory -> assertThat(directory).doesNotExist(),
                directory -> assertThat(directory).isEmptyDirectory());
        try (Stream<Path> packs = Files.list(objects.resolve("pack"))) {
            assertThat(packs.map(Path::getFileName).map(Path::toString))
                .anyMatch(name -> name.endsWith(".pack"))
                .noneMatch(name -> name.startsWith("tmp_"));
        }
    }

    private static List<String> contentsWhoseBlobIdStartsWith17(int count)
        throws NoSuchAlgorithmException {
        List<String> contents = new ArrayList<>();
        for (int i = 0; contents.size() < count; i++) {
            String content = "gc-" + i + "\n";
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            if ((sha1.digest(bytes)[0] & 0xff) == 0x17) {
                contents.add(content);
            }
        }
        return contents;
    }

    @Test
    void everyCallIsBoundedByThirtySeconds() {
        assertThat(git.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aGitThatCannotBeStartedForAnotherReasonIsNotReportedAsMissing() {
        GitCli broken = new GitCli(root, "acme", FakeProcessLauncher.of("", "", 0)
            .failingToLaunch(new IOException("Cannot run program \"git\": error=13, Permission"
                + " denied")), parentEnvironment, "git", Duration.ofSeconds(30));

        assertThatThrownBy(broken::status).isInstanceOfSatisfying(ToolErrorException.class, e -> {
            assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
            assertThat(e.error().message()).contains("git could not be started")
                .doesNotContain("not found");
        });
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

    // --- feature 004 (T007, research D-3, D-25): server-state trailer and read methods ------

    private static final GroupId DEV = new GroupId("dev");
    private static final Map<String, String> DEV_STATE = Map.of("Server-State", "dev");

    private String commit(String message, Map<String, String> trailers) {
        return git.commitAll(message, trailers).orElseThrow().commit();
    }

    @Test
    void commitAllWritesTheServerStateTrailerAfterTheMessage()
        throws IOException, InterruptedException {
        git.init();
        write("dev/a.xml", "a");

        HistoryEntry entry = git.commitAll("export dev/node1: diagram group G (2 files)",
            DEV_STATE).orElseThrow();

        assertThat(entry.message()).isEqualTo("export dev/node1: diagram group G (2 files)");
        assertThat(inspect("log", "-1", "--format=%s").strip())
            .isEqualTo("export dev/node1: diagram group G (2 files)");
        assertThat(inspect("log", "-1", "--format=%(trailers:key=Server-State,valueonly)")
            .strip()).isEqualTo("dev");
        assertThat(inspect("log", "-1", "--format=%B").strip()).isEqualTo(
            "export dev/node1: diagram group G (2 files)\n\nServer-State: dev");
    }

    @Test
    void aCommitWithoutTrailersHasNone() throws IOException, InterruptedException {
        git.init();
        write("dev/a.xml", "a");

        git.commitAll("local changes: 2 files");

        assertThat(inspect("log", "-1", "--format=%B").strip())
            .isEqualTo("local changes: 2 files");
    }

    @Test
    void trailersAreValidated() {
        git.init();
        for (Map<String, String> trailers : List.of(Map.of("Server-State", "dev\nInjected: x"),
            Map.of("Server State", "dev"), Map.of("Server-State", ""),
            Map.of("Server-State", "dev\u0000"))) {
            assertThatIllegalArgumentException().as(trailers.toString())
                .isThrownBy(() -> git.commitAll("export", trailers));
        }
    }

    @Test
    void messagesWithControlCharactersAreRefusedSoThatNoTrailerCanBeForged() throws IOException {
        // review I1: a "\n\nServer-State: dev" paragraph would forge a server state; the
        // separators of the log format would break lastServerState
        git.init();
        write("dev/a.xml", "a");
        for (String message : List.of("export\n\nServer-State: dev", "a\rb", "a\u001db",
            "a\u001eb", "a\u001fb", "a\u0000b", "a\tb")) {
            assertThatIllegalArgumentException().as(message)
                .isThrownBy(() -> git.commitAll(message, DEV_STATE));
            assertThatIllegalArgumentException().as(message)
                .isThrownBy(() -> git.commitAll(message));
        }
        assertThat(git.lastServerState(DEV, "dev/a.xml")).isEmpty();
        assertThat(git.commitAll("export dev/node1: Größe (1 files)", DEV_STATE)).isPresent();
    }

    @Test
    void showFailsForACommitThatIsNotInTheHistory() throws IOException {
        // review M5: an unknown commit is not the same as a missing file
        git.init();
        write("dev/a.xml", "a");
        commit("export", DEV_STATE);

        assertThatIllegalArgumentException().isThrownBy(() -> git.show(
            "0123456789abcdef0123456789abcdef01234567", "dev/a.xml"))
            .withMessageContaining("not in the workspace history");
    }

    @Test
    void theLastServerStateIsTheNewestCommitWithTheGroupsTrailerThatTouchedThePath()
        throws IOException, InterruptedException {
        git.init();
        write("dev/OWNERS/a.xml", "a1");
        write("dev/OWNERS/b.xml", "b1");
        String first = commit("export dev/node1: diagram group G (3 files)", DEV_STATE);
        write("dev/OWNERS/a.xml", "a2");
        commit("local changes: 1 files", Map.of());
        write("dev/OWNERS/b.xml", "b2");
        String second = commit("export dev/node1: diagram group G (1 files)", DEV_STATE);
        write("dev/OWNERS/a.xml", "a3");
        String other = commit("import test/node1: G (1 artifacts)",
            Map.of("Server-State", "test"));

        assertThat(git.lastServerState(DEV, "dev/OWNERS/a.xml")).contains(full(first));
        assertThat(git.lastServerState(DEV, "dev/OWNERS/b.xml")).contains(full(second));
        assertThat(git.lastServerState(new GroupId("test"), "dev/OWNERS/a.xml"))
            .contains(full(other));
        assertThat(git.lastServerState(DEV, "dev/OWNERS")).contains(full(second));
        assertThat(git.lastServerState(DEV, "dev/OWNERS/never.xml")).isEmpty();
    }

    @Test
    void withoutAnyTrailerTheLastExportOfTheGroupIsTheServerState()
        throws IOException, InterruptedException {
        // research D-25 (H3): histories of feature 003 have no trailer yet
        git.init();
        write("dev/OWNERS/a.xml", "a1");
        String export = commit("export dev/node1: diagram group G (2 files)", Map.of());
        write("dev/OWNERS/a.xml", "a2");
        commit("local changes: 1 files", Map.of());
        write("dev/OWNERS/a.xml", "a3");
        commit("export dev-2/node1: diagram group G (1 files)", Map.of());
        write("dev/OWNERS/a.xml", "a4");
        commit("export test/node1: diagram group G (1 files)", Map.of());

        assertThat(git.lastServerState(DEV, "dev/OWNERS/a.xml")).contains(full(export));
    }

    @Test
    void aTrailerOfAnotherGroupIsNoFallback() throws IOException {
        git.init();
        write("dev/OWNERS/a.xml", "a1");
        commit("export dev/node1: diagram group G (2 files)", Map.of("Server-State", "test"));

        assertThat(git.lastServerState(DEV, "dev/OWNERS/a.xml")).isEmpty();
    }

    @Test
    void withoutABaseTheScopeMustBeExportedFirst() throws IOException {
        git.init();
        assertThat(git.lastServerState(DEV, "dev/OWNERS/a.xml")).as("no commit yet").isEmpty();
        write("dev/OWNERS/a.xml", "a1");
        commit("local changes: 2 files", Map.of());

        assertThatThrownBy(() -> git.serverStateOf(DEV, "dev/OWNERS/a.xml"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                assertThat(e.error().message()).contains("dev/OWNERS/a.xml");
                assertThat(e.error().nextStep()).contains("export the scope first");
            });
    }

    @Test
    void localChangesAreTheFilesWhoseNewestEntryIsNoServerStateOfTheGroup() throws IOException {
        // feature 004 (T010, research D-4, D-25): candidates of a change set with their own base
        git.init();
        write("dev/OWNERS/a.xml", "a1");
        write("dev/OWNERS/b c.xml", "b1");
        write("dev/OWNERS/same.xml", "s1");
        write("dev/OWNERS/old.xml", "o1");
        String first = full(commit("export dev/node1: G (4 files)", Map.of()));
        write("dev/OWNERS/old.xml", "o2");
        String second = full(commit("import dev/node1: G (1 artifacts)", DEV_STATE));
        write("dev/OWNERS/a.xml", "a2");
        Files.delete(root.resolve("dev/OWNERS/b c.xml"));
        write("dev/OWNERS/new.xml", "n1");
        write("dev/OTHER/x.xml", "x1");
        commit("local changes: 4 files", Map.of());

        List<VersionHistoryPort.LocalChange> changes = git.localChanges(DEV, "dev/OWNERS");

        assertThat(changes).containsExactlyInAnyOrder(
            new VersionHistoryPort.LocalChange("dev/OWNERS/a.xml", Kind.MODIFIED,
                Optional.of(first)),
            new VersionHistoryPort.LocalChange("dev/OWNERS/b c.xml", Kind.DELETED,
                Optional.of(first)),
            new VersionHistoryPort.LocalChange("dev/OWNERS/new.xml", Kind.ADDED,
                Optional.empty()));
        assertThat(git.lastServerState(DEV, "dev/OWNERS/old.xml")).contains(second);
        assertThat(git.localChanges(DEV, "dev/OTHER")).containsExactly(
            new VersionHistoryPort.LocalChange("dev/OTHER/x.xml", Kind.ADDED, Optional.empty()));
        assertThat(git.localChanges(DEV, "dev/NONE")).isEmpty();
    }

    @Test
    void aFileAddedAndDeletedLocallyIsNoChange() throws IOException {
        git.init();
        write("dev/OWNERS/a.xml", "a1");
        commit("export dev/node1: G", DEV_STATE);
        write("dev/OWNERS/tmp.xml", "t");
        commit("local changes: 1 files", Map.of());
        Files.delete(root.resolve("dev/OWNERS/tmp.xml"));
        commit("local changes: 1 files", Map.of());

        assertThat(git.localChanges(DEV, "dev/OWNERS")).isEmpty();
    }

    @Test
    void showReturnsTheContentOfAFileAtARevision() throws IOException {
        git.init();
        write("dev/OWNERS/a.xml", "<a>1</a>\n");
        String first = commit("export dev/node1: G", DEV_STATE);
        write("dev/OWNERS/a.xml", "<a>Größe 2</a>\n");
        String second = commit("local changes: 1 files", Map.of());

        assertThat(git.show(first, "dev/OWNERS/a.xml"))
            .hasValueSatisfying(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("<a>1</a>\n"));
        assertThat(git.show(second, "dev/OWNERS/a.xml"))
            .hasValueSatisfying(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("<a>Größe 2</a>\n"));
        assertThat(git.show(first, "dev/OWNERS/missing.xml")).isEmpty();
        assertThat(git.show(first, "dev/OWNERS")).as("a directory is no file").isEmpty();
    }

    @Test
    void showAndChangedPathsTakeOnlyCommitIdsAndPathsInsideTheWorkspace() throws IOException {
        git.init();
        write("dev/a.xml", "a");
        String first = commit("export", DEV_STATE);
        for (String rev : List.of("--output=/tmp/x", "HEAD~1", "main", "", "abc")) {
            assertThatIllegalArgumentException().as(rev)
                .isThrownBy(() -> git.show(rev, "dev/a.xml"));
            assertThatIllegalArgumentException().as(rev)
                .isThrownBy(() -> git.changedPaths(rev, "dev"));
        }
        for (String path : List.of("", "/etc/passwd", "../outside", ".git/config")) {
            assertThatIllegalArgumentException().as(path)
                .isThrownBy(() -> git.show(first, path));
            assertThatIllegalArgumentException().as(path)
                .isThrownBy(() -> git.changedPaths(first, path));
            assertThatIllegalArgumentException().as(path)
                .isThrownBy(() -> git.lastServerState(DEV, path));
        }
    }

    @Test
    void changedPathsListsAddedModifiedDeletedAndRenamedFilesBelowTheSubtree()
        throws IOException {
        git.init();
        write("dev/OWNERS/workflows/G/a.xml", "a1");
        write("dev/OWNERS/workflows/G/b.xml", "b1");
        write("dev/OWNERS/workflows/G/d.xml", "d1");
        write("dev/OWNERS/workflows/H/x.xml", "x1");
        String base = commit("export dev/node1: G", DEV_STATE);
        write("dev/OWNERS/workflows/G/a.xml", "a2");
        Files.delete(root.resolve("dev/OWNERS/workflows/G/b.xml"));
        write("dev/OWNERS/workflows/G/c new.xml", "c1");
        Files.move(root.resolve("dev/OWNERS/workflows/G/d.xml"),
            root.resolve("dev/OWNERS/workflows/G/e.xml"));
        write("dev/OWNERS/workflows/H/x.xml", "x2");
        commit("local changes: 6 files", Map.of());

        assertThat(git.changedPaths(base, "dev/OWNERS/workflows/G")).containsExactlyInAnyOrder(
            new PathChange("dev/OWNERS/workflows/G/a.xml", Kind.MODIFIED),
            new PathChange("dev/OWNERS/workflows/G/b.xml", Kind.DELETED),
            new PathChange("dev/OWNERS/workflows/G/c new.xml", Kind.ADDED),
            new PathChange("dev/OWNERS/workflows/G/d.xml", Kind.DELETED),
            new PathChange("dev/OWNERS/workflows/G/e.xml", Kind.ADDED));
        assertThat(git.changedPaths(base, "dev/OWNERS/workflows/H")).containsExactly(
            new PathChange("dev/OWNERS/workflows/H/x.xml", Kind.MODIFIED));
        assertThat(git.changedPaths(full(base), "dev/OWNERS/workflows/G/a.xml")).containsExactly(
            new PathChange("dev/OWNERS/workflows/G/a.xml", Kind.MODIFIED));
    }

    private String full(String commit) {
        try {
            return inspect("rev-parse", commit).strip();
        } catch (IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
