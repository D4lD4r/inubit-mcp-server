package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchedProcess;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.testing.ArgvRecorder;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature 013 (T012, FR-005, research R-7): the kill-on-close job of the server on Windows. The
 * order of the calls and their failure handling run on every platform against a scripted
 * {@link JobApi}. On Windows a helper JVM ({@link BoundHelper}) binds itself with the real calls,
 * starts the substitute StartCLI, whose recorder starts a sleeping child, and is killed with
 * {@code destroyForcibly}: the child must be gone within 2 s, and the job handle must not be
 * inheritable. The test JVM itself is never bound.
 */
@Timeout(60)
class WindowsJobTest {

    private static final long JOB = 0x4711;
    private static final int ERROR = 1450;

    /**
     * Records the calls; the function named {@code failing} fails with {@link #ERROR}. Uses no
     * JNA: no test outside Windows loads its native library ({@code CredentialStoresTest}).
     */
    private static final class ScriptedJobApi implements JobApi {

        final List<String> calls = new ArrayList<>();
        private final String failing;

        ScriptedJobApi(String failing) {
            this.failing = failing;
        }

        private boolean succeeds(String function, long job) {
            calls.add(function + (job == JOB ? "" : " of " + job));
            return !function.equals(failing);
        }

        @Override
        public long create() {
            if (failing.equals("load")) {
                throw new UnsatisfiedLinkError("Unable to load library 'Kernel32'");
            }
            return succeeds("CreateJobObjectW", JOB) ? JOB : 0;
        }

        @Override
        public boolean limitKillOnClose(long job) {
            return succeeds("SetInformationJobObject", job);
        }

        @Override
        public boolean assignCurrentProcess(long job) {
            return succeeds("AssignProcessToJobObject", job);
        }

        @Override
        public void close(long job) {
            succeeds("CloseHandle", job);
        }

        @Override
        public int lastError() {
            calls.add("GetLastError");
            return ERROR;
        }
    }

    // --- every platform -----------------------------------------------------------------------

    @Test
    void bindsTheProcessToANewKillOnCloseJobAndKeepsItsHandleOpen() {
        ScriptedJobApi api = new ScriptedJobApi("");

        WindowsJob job = WindowsJob.bind(api);

        assertThat(job.state()).isEqualTo(new ProcessContainment.Bound());
        assertThat(api.calls).containsExactly("CreateJobObjectW", "SetInformationJobObject",
            "AssignProcessToJobObject");
        assertThat(job.handle()).isEqualTo(JOB);
    }

    @Test
    void aJobThatCannotBeCreatedLeavesTheProcessUnbound() {
        ScriptedJobApi api = new ScriptedJobApi("CreateJobObjectW");

        WindowsJob job = WindowsJob.bind(api);

        assertThat(job.state()).isEqualTo(new ProcessContainment.Unavailable(
            "CreateJobObjectW failed (Windows error " + ERROR + ")"));
        assertThat(api.calls).containsExactly("CreateJobObjectW", "GetLastError");
        assertThat(job.handle()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SetInformationJobObject", "AssignProcessToJobObject"})
    void aFailingStepClosesTheJobAndNamesTheFunctionAndTheError(String failing) {
        ScriptedJobApi api = new ScriptedJobApi(failing);

        WindowsJob job = WindowsJob.bind(api);

        assertThat(job.state()).isEqualTo(new ProcessContainment.Unavailable(
            failing + " failed (Windows error " + ERROR + ")"));
        assertThat(api.calls).as("the error is read before the handle is closed")
            .endsWith(failing, "GetLastError", "CloseHandle").doesNotHaveDuplicates();
        assertThat(job.handle()).isZero();
    }

    @Test
    void withoutKernel32TheProcessIsUnbound() {
        WindowsJob job = WindowsJob.bind(new ScriptedJobApi("load"));

        assertThat(job.state()).isEqualTo(new ProcessContainment.Unavailable(
            "Kernel32 could not be loaded (UnsatisfiedLinkError)"));
        assertThat(job.handle()).isZero();
    }

    // --- Windows ------------------------------------------------------------------------------

    /**
     * The server stand-in: binds itself with {@link WindowsJob}, prints {@code bound} (else
     * {@code unavailable: <reason>} and exits) and whether the job handle is inheritable, starts
     * the substitute StartCLI {@code args[0]} as the server does (the interpreter through
     * {@link SystemProcessLauncher}, stdin closed) and sleeps until it is killed. Uses nothing of
     * JUnit, so it runs with the test class path alone.
     */
    public static final class BoundHelper {

        private static final int HANDLE_FLAG_INHERIT = 0x1;

        /** {@code GetHandleInformation}, independent of the code under test. */
        interface HandleApi extends StdCallLibrary {

            boolean GetHandleInformation(Pointer handle, IntByReference flags);
        }

        public static void main(String[] args) throws Exception {
            WindowsJob job = WindowsJob.bindCurrentProcess();
            if (job.state() instanceof ProcessContainment.Unavailable(String reason)) {
                System.out.println("unavailable: " + reason);
                System.out.flush();
                return;
            }
            System.out.println("bound");
            IntByReference flags = new IntByReference();
            HandleApi api = Native.load("Kernel32", HandleApi.class);
            System.out.println(api.GetHandleInformation(new Pointer(job.handle()), flags)
                ? "inherit=" + ((flags.getValue() & HANDLE_FLAG_INHERIT) != 0)
                : "GetHandleInformation failed (Windows error " + Native.getLastError() + ")");
            System.out.flush();
            Path script = Path.of(args[0]);
            LaunchedProcess substitute = new SystemProcessLauncher().launch(new LaunchSpec(
                List.of(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString(),
                    "/d", "/v:off", "/s", "/c", "\"\"" + script + "\"\""),
                Map.of("SystemRoot", System.getenv("SystemRoot")),
                script.getParent().getParent()));
            substitute.stdin().close();
            Thread.sleep(Duration.ofMinutes(2));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aForcedlyKilledServerTakesTheProcessesOfStartCliWithIt(@TempDir Path cliHome,
        @TempDir Path logs) throws Exception {
        FakeStartCli cli = FakeStartCli.install(cliHome, Map.of(
            ArgvRecorder.SLEEP_MILLIS, "120000", ArgvRecorder.CHILD_SLEEP_MILLIS, "120000"));
        Path errors = logs.resolve("helper.err");
        Process helper = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp",
            System.getProperty("java.class.path"), BoundHelper.class.getName(),
            cli.script().toString())
            .redirectError(errors.toFile()).start();
        List<ProcessHandle> tree = new ArrayList<>();
        try {
            helper.getOutputStream().close();
            BufferedReader out = new BufferedReader(new InputStreamReader(
                helper.getInputStream(), StandardCharsets.UTF_8));
            assertThat(out.readLine()).as(() -> stderrOf(errors)).isEqualTo("bound");
            assertThat(out.readLine()).as(() -> stderrOf(errors)).isEqualTo("inherit=false");
            FakeStartCli.Recorded record = awaitRecord(cli, helper, errors);
            ProcessHandle recorder = ProcessHandle.of(record.pid()).orElseThrow();
            ProcessHandle child = ProcessHandle.of(record.childPid()).orElseThrow();
            tree.addAll(List.of(recorder, child));
            tree.addAll(helper.descendants().toList());

            helper.destroyForcibly();

            assertThat(child.onExit()).as("the sleeping child").succeedsWithin(
                Duration.ofSeconds(2));
            assertThat(recorder.onExit()).as("the substitute's JVM").succeedsWithin(
                Duration.ofSeconds(2));
        } finally {
            // whatever survived a failure; waited for, so that the temporary directories, the
            // working directory of the interpreter and the recorder, can be deleted
            tree.addAll(helper.descendants().toList());
            tree.add(helper.toHandle());
            tree.forEach(ProcessHandle::destroyForcibly);
            tree.forEach(process -> process.onExit()
                .completeOnTimeout(process, 10, TimeUnit.SECONDS).join());
        }
    }

    /** The substitute's record, written once its child runs; fails early if the helper died. */
    private static FakeStartCli.Recorded awaitRecord(FakeStartCli cli, Process helper,
        Path errors) throws IOException, InterruptedException {
        long start = System.nanoTime();
        while (!Files.exists(cli.recordFile())) {
            assertThat(helper.isAlive()).as(() -> "helper exited: " + stderrOf(errors)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no record yet")
                .isLessThan(Duration.ofSeconds(30));
            Thread.sleep(50);
        }
        FakeStartCli.Recorded record = cli.record();
        assertThat(record.childPid()).as("the recorder started its child").isNotNull();
        return record;
    }

    private static String stderrOf(Path errors) {
        try {
            return Files.exists(errors) ? Files.readString(errors, StandardCharsets.ISO_8859_1)
                : "";
        } catch (IOException e) {
            return e.toString();
        }
    }
}
