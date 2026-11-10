package de.dadecker.inubit.mcp.adapter.cli.queue;

import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperTest.call;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperTest.encode;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperTest.outputs;
import static de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryHelperTest.request;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.StartCliEnvironment;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.testing.FakeInubitClient;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged queue-entry helper of feature 020 (research D-3, D-4) in a real child JVM: its
 * class file is taken from the helper resource directory on the class path into
 * {@code <tmp>/helper}, a {@link FakeInubitClient} is the client home, and the D-3 command runs
 * through {@link SystemProcessLauncher} and {@link ProcessRun}. The vendor fakes read their
 * script from {@code <home>/conf} and record their calls there.
 */
@Timeout(90)
class QueueEntryHelperProcessTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String HELPER_LABEL = "the queue-entry helper";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String URL_81 = "https://inubit.example.test:8443/ibis/servlet/IBIS";
    private static final String URL_90 = "https://inubit.example.test:8443";
    private static final String USER = "jdoe";
    private static final String FILE = "Order-0001.xml";
    private static final String OBJECT_TYPE = "queue_manager@@@Workflow-0001@@@error";
    private static final byte[] DATA = {'P', 'K', 3, 4, 0, (byte) 0xFF, '\n'};
    private static final byte[] VARIABLES = ("<Properties version=\"4.1\"><Property name="
        + "\"apiToken\">" + TestSecrets.CREDENTIAL_PAYLOAD_VALUE + "</Property></Properties>")
        .getBytes(StandardCharsets.UTF_8);
    private static final String NOISE = "INFO  [main] IBISClient - connecting to "
        + "inubit.example.test";
    /** A frame-like noise line of another nonce, which a reader of this run must ignore. */
    private static final String FOREIGN_FRAME = "#QE1:ffffffffffffffffffffffffffffffff end";

    @TempDir
    Path tmp;

    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());

    @AfterEach
    void closeResources() {
        resources.close();
    }

    @Test
    void soap81ReadsBothPartsWithCredentialsOnStdinOnlyAndIgnoresVendorNoise() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript()
                .printsOnLogin(NOISE)
                .printsOnLogin(FOREIGN_FRAME)
                .file(FILE, DATA)
                .file(FILE + ".wfsp", VARIABLES));
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");
        LaunchSpec spec = launchSpec(client);

        CliResult result = runHelper(spec, request(lines -> outputs(lines, data, variables)));

        assertThat(result.exitCode()).isZero();
        assertThat(frames(result.stdout())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("part", "data", "ok", Integer.toString(DATA.length)),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(noise(result.stdout())).as("vendor noise reaches stdout, outside the frames")
            .containsExactly(NOISE, FOREIGN_FRAME);
        assertThat(data).hasBinaryContent(DATA);
        assertThat(variables).hasBinaryContent(VARIABLES);
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", "/var/qe-0001/trust.p12",
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("MultipartDownloadThread.<init>", "IBISSoapClient", USER, FILE, OBJECT_TYPE,
                "null", "null"),
            call("MultipartDownloadThread.setOutputFile", data.toString()),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("MultipartDownloadThread.<init>", "IBISSoapClient", USER, FILE + ".wfsp",
                OBJECT_TYPE, "null", "null"),
            call("MultipartDownloadThread.setOutputFile", variables.toString()),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("IBISSoapClient.logoutUser", USER));
        assertArgvCarriesOnlyJvmOptionsAndTheClassName(spec, client, data, variables);
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    @Test
    void rest90EndsTheJvmDespiteTheClientsLingeringNonDaemonThread() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript()
                .leavesNonDaemonThread()
                .printsOnLogin(NOISE)
                .file(FILE, DATA)
                .file(FILE + ".wfsp", VARIABLES));
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");

        CliResult result = runHelper(launchSpec(client), request(lines -> {
            outputs(lines, data, variables);
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
        }));

        assertThat(result.exitCode()).as("System.exit ended the JVM within the timeout").isZero();
        assertThat(frames(result.stdout())).containsExactly(
            List.of("start", "REST_90"),
            List.of("login", "ok"),
            List.of("part", "data", "ok", Integer.toString(DATA.length)),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(noise(result.stdout())).containsExactly(NOISE);
        assertThat(data).hasBinaryContent(DATA);
        assertThat(variables).hasBinaryContent(VARIABLES);
        assertThat(client.calls()).contains(
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD));
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    @Test
    void aFailedLoginWithALingeringThreadEndsWithExitThree() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript()
                .leavesNonDaemonThread()
                .loginFails(new Failure("java.io.IOException",
                    "login of " + USER + " refused, password " + TestSecrets.CONFIGURED_PASSWORD)));
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");

        CliResult result = runHelper(launchSpec(client), request(lines -> {
            outputs(lines, data, variables);
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
        }));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(frames(result.stdout())).containsExactly(
            List.of("start", "REST_90"),
            List.of("login", "error", "java.io.IOException",
                "login of " + USER + " refused, password ******"));
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    // --- helpers --------------------------------------------------------------------------

    private Path out(String name) throws IOException {
        return Files.createDirectories(tmp.resolve("out")).resolve(name);
    }

    /**
     * The D-3 command: JVM options, the class path {@code <tmp>/helper}, {@code <home>/lib/*},
     * {@code <home>/lib/shared/*} and the class name; the client home is the working directory.
     */
    private LaunchSpec launchSpec(FakeInubitClient client) throws IOException {
        Path helper = installHelper();
        Path vendorTmp = Files.createDirectories(tmp.resolve("vendor"));
        Path home = client.home();
        String javaHome = System.getProperty("java.home");
        List<String> command = List.of(
            Path.of(javaHome, "bin", Platform.windows() ? "java.exe" : "java").toString(),
            "-Xmx512m",
            "-Djdk.tls.client.protocols=TLSv1.2,TLSv1.3",
            "-Dsecretkey.path=" + home.resolve("conf"),
            "-Djava.io.tmpdir=" + vendorTmp,
            "-cp",
            String.join(File.pathSeparator, helper.toString(),
                home.resolve("lib").resolve("*").toString(),
                home.resolve("lib").resolve("shared").resolve("*").toString()),
            QueueEntryHelperPackagingTest.HELPER_CLASS);
        return new LaunchSpec(command, environment(javaHome), home);
    }

    /** Copies the packaged helper class from the class path to {@code <tmp>/helper}. */
    private Path installHelper() throws IOException {
        Path helper = tmp.resolve("helper");
        Path classFile = helper.resolve(QueueEntryHelperPackagingTest.HELPER_FILE);
        Files.createDirectories(classFile.getParent());
        try (InputStream in = QueueEntryHelperProcessTest.class.getClassLoader()
            .getResourceAsStream(QueueEntryHelperPackagingTest.RESOURCE_DIRECTORY
                + QueueEntryHelperPackagingTest.HELPER_FILE)) {
            assertThat(in).as("the packaged helper on the class path").isNotNull();
            Files.copy(in, classFile);
        }
        return helper;
    }

    private static Map<String, String> environment(String javaHome) {
        if (Platform.windows()) {
            return StartCliEnvironment.windows(System.getenv(), javaHome,
                System.getenv("SystemRoot") + "\\system32");
        }
        return StartCliEnvironment.posix(System.getenv(), javaHome);
    }

    private CliResult runHelper(LaunchSpec spec, String request) {
        return new ProcessRun(new SystemProcessLauncher(), resources)
            .run(spec, request.getBytes(StandardCharsets.UTF_8), TIMEOUT, HELPER_LABEL);
    }

    private static void assertArgvCarriesOnlyJvmOptionsAndTheClassName(LaunchSpec spec,
        FakeInubitClient client, Path data, Path variables) {
        List<String> arguments = spec.command().subList(1, spec.command().size());
        assertThat(arguments.get(arguments.size() - 1))
            .isEqualTo(QueueEntryHelperPackagingTest.HELPER_CLASS);
        assertThat(arguments.subList(0, arguments.size() - 1)).allSatisfy(argument ->
            assertThat(argument.startsWith("-") || argument.contains(File.pathSeparator))
                .as("JVM option or class path: %s", argument).isTrue());
        String argv = String.join(" ", spec.command());
        for (String requestValue : List.of(USER, URL_81, "inubit.example.test", FILE,
            "Workflow-0001", TestSecrets.CONFIGURED_PASSWORD, TestSecrets.TRUST_STORE_PASSWORD,
            data.toString(), variables.toString())) {
            assertThat(argv).as("request values travel on stdin only")
                .doesNotContain(requestValue);
        }
        assertThat(spec.workingDirectory()).isEqualTo(client.home());
    }

    /** The decoded tokens of this run's frames, in order; other lines are skipped. */
    private static List<List<String>> frames(String stdout) {
        String prefix = "#QE1:" + NONCE + " ";
        List<List<String>> frames = new ArrayList<>();
        stdout.lines().filter(line -> line.startsWith(prefix)).forEach(line -> frames.add(
            Arrays.stream(line.substring(prefix.length()).split(" ", -1))
                .map(token -> URLDecoder.decode(token, StandardCharsets.UTF_8)).toList()));
        return frames;
    }

    /** The stdout lines that are not frames of this run. */
    private static List<String> noise(String stdout) {
        return stdout.lines().filter(line -> !line.startsWith("#QE1:" + NONCE + " ")).toList();
    }
}
