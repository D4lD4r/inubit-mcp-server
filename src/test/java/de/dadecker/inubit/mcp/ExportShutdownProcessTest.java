package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.testing.ArgvRecorder;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 review I1: the real server ({@link Main}) in a child JVM runs a module export through a
 * fake StartCLI ({@link FakeStartCli}) that starts a long-sleeping child. When the MCP client
 * closes stdin, or the server gets SIGTERM, no StartCLI process may survive and the export
 * directory must be gone. A stale export directory of a dead server process is swept at startup.
 * The client probe {@code -v} of the version gate (feature 009, T019) is answered by a
 * {@code get_health} call against a fake that replays the version, before the sleeping fake is
 * installed; the known client result spares the export call a second probe. Feature 011: the node
 * reads its credentials from AWS Parameter Store, answered by a local stub of the SSM protocol
 * ({@code AWS_ENDPOINT_URL_SSM}), so that the test needs no credential store of the system and
 * runs the same on every machine.
 */
@Timeout(90)
class ExportShutdownProcessTest {

    private static final String PASSWORD = "export-shutdown-Pw-4711";
    private static WireMockServer wireMock;
    /** A plain-HTTP stub of AWS Systems Manager ({@code AmazonSSM.GetParameters}). */
    private static WireMockServer ssm;

    @TempDir
    Path home;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
        ssm = new WireMockServer(options().dynamicPort());
        ssm.start();
        ssm.stubFor(post(urlPathEqualTo("/"))
            .withHeader("X-Amz-Target", equalTo("AmazonSSM.GetParameters"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/x-amz-json-1.1").withBody("""
                    {"Parameters":[
                      {"Name":"/test/dev/username","Type":"SecureString",
                       "Value":"export-test-user"},
                      {"Name":"/test/dev/password","Type":"SecureString","Value":"%s"}],
                     "InvalidParameters":[]}""".formatted(PASSWORD))));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
        ssm.stop();
    }

    @Test
    void closingStdinStopsTheExportAndDeletesItsDirectory() throws Exception {
        long dead = Platform.deadPid();
        Path stale = staleExport("acme-" + dead);
        Path legacy = staleExport(String.valueOf(dead));
        Path otherProfile = staleExport("globex-" + dead);

        Child child = startExport();
        child.stdin().close();

        child.assertCleanShutdown();
        // 002 research D-8 (quickstart B4): the own profile's and legacy directories of dead
        // processes are swept at startup, another profile's never
        assertThat(stale).as("swept at startup").doesNotExist();
        assertThat(legacy).as("001 name, swept at startup").doesNotExist();
        assertThat(otherProfile).as("another profile's").exists();
    }

    /**
     * {@code inubit-mcp-export-<middle>-1} with a file, in the server's temp directory; created as
     * the server creates it (feature 013, R-8), so that on Windows the token user owns it.
     */
    private Path staleExport(String middle) throws IOException {
        Path directory = tmp().resolve(CliResources.EXPORT_PREFIX + middle + "-1");
        PrivateFiles.forHost().createDirectories(directory);
        Files.writeString(directory.resolve("modules.zip"), "stale");
        return directory;
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "SIGTERM is POSIX; Windows: the job kill"
        + " (aKilledServerLeavesNoStartCliAndTheNextStartSweepsItsDirectory)")
    void sigtermStopsTheExportAndDeletesItsDirectory() throws Exception {
        Child child = startExport();
        // pure SIGTERM: stdin stays open, only the JVM shutdown hook cleans up
        // (Process.destroy() would also close the pipes, i.e. EOF + SIGTERM)
        assertThat(child.process().toHandle().destroy()).isTrue();

        child.assertCleanShutdown();
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "SIGTERM is POSIX; Windows: the job kill"
        + " (aKilledServerLeavesNoStartCliAndTheNextStartSweepsItsDirectory)")
    void eofAndSigtermTogetherStopTheExportAndDeleteItsDirectory() throws Exception {
        // Phase 6 review W2: the main thread (stdin EOF) and the shutdown hook (SIGTERM) close
        // the CLI resources concurrently; the JVM must not exit before the trees are stopped
        Child child = startExport();
        child.stdin().close();
        assertThat(child.process().toHandle().destroy()).isTrue();

        child.assertCleanShutdown();
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX has no job object: a SIGKILL of the"
        + " server leaves StartCLI running there")
    void aKilledServerLeavesNoStartCliAndTheNextStartSweepsItsDirectory() throws Exception {
        // feature 013 (T019, FR-008, R-10): the job (T012) ends StartCLI with the server; its
        // export directory stays behind until the next start of the same profile sweeps it
        Child killed = startExport();
        String ownPrefix = CliResources.EXPORT_PREFIX + "acme-" + killed.process().pid() + "-";
        assertThat(killed.process().toHandle().destroyForcibly()).isTrue();
        assertThat(killed.process().waitFor(40, TimeUnit.SECONDS)).as("server killed").isTrue();
        killed.assertStartCliStopped();
        List<Path> left;
        try (Stream<Path> files = Files.list(killed.tmp())) {
            left = files.filter(path -> path.getFileName().toString().startsWith(ownPrefix))
                .toList();
        }
        assertThat(left).as("a killed server cannot clean up").isNotEmpty();

        Child next = startExport();
        next.stdin().close();

        next.assertCleanShutdown();
        assertThat(left).allSatisfy(directory -> assertThat(directory)
            .as("swept at the next start").doesNotExist());
    }

    private Path tmp() throws IOException {
        return Files.createDirectories(home.resolve("tmp"));
    }

    private record Child(Process process, OutputStream stdin, FakeStartCli cli, Path tmp,
        Path stderr) {

        void assertCleanShutdown() throws Exception {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("server exits").isTrue();
            String errors = assertStartCliStopped();
            try (Stream<Path> files = Files.list(tmp)) {
                assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(CliResources.EXPORT_PREFIX + "acme-"))
                    .toList())
                    .as(errors).isEmpty();
            }
            assertThat(errors).doesNotContain(PASSWORD);
        }

        /** Neither the fake StartCLI nor its child runs (any more); returns the server's stderr. */
        String assertStartCliStopped() throws Exception {
            String errors = Files.readString(stderr);
            FakeStartCli.Recorded record = cli.record();
            assertThat(record.childPid()).as("the fake started its child").isNotNull();
            for (Map.Entry<String, Long> entry : Map.of("startcli", record.pid(),
                "its child", record.childPid()).entrySet()) {
                String name = entry.getKey();
                long pid = entry.getValue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (alive(pid) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(alive(pid)).as(name + " still running; stderr: " + errors).isFalse();
            }
            return errors;
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private Child startExport() throws Exception {
        Path cliHome = Files.createDirectories(home.resolve("client"));
        FakeStartCli probe = FakeStartCli.replaying(cliHome, "version");
        Path tmp = tmp();
        Path config = home.resolve("config.yaml");
        Files.writeString(config, """
            profile:
              name: acme
            auditDirectory: %s
            defaults:
              cliHome: %s
              cliJavaHome: %s
              inventory:
                owner: INTEGRATION
            groups:
              - name: dev
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-central-1
                  parameters:
                    username: /test/dev/username
                    password: /test/dev/password
                nodes:
                  - name: node1
                    baseUrl: https://localhost:%d
                    versionLine: V8_1
                    tls:
                      trustStore: %s
            """.formatted(home.resolve("audit"), cliHome, System.getProperty("java.home"),
            wireMock.httpsPort(), TestCertificates.get().trustStore()));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(),
            "-cp", System.getProperty("java.class.path"),
            "-Dlogback.configurationFile=logback.xml", "-Duser.home=" + home,
            "-Djava.io.tmpdir=" + tmp, Main.class.getName(), "--config", config.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", Platform.searchPath());
        // feature 011: the credentials come from the SSM stub, with static test AWS credentials
        environment.put("AWS_ENDPOINT_URL_SSM", "http://localhost:" + ssm.port());
        environment.put("AWS_ACCESS_KEY_ID", "AKIDEXAMPLE");
        environment.put("AWS_SECRET_ACCESS_KEY", "test-aws-secret");
        // stderr to a file: it stays readable whatever happens to the pipes
        Path stderr = home.resolve("server-stderr.log");
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(),
            StandardCharsets.UTF_8));
        OutputStream stdin = process.getOutputStream();
        send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        // the client probe, against the replaying fake; its result is known from now on
        send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"get_health\",\"arguments\":{\"target\":\"dev/node1\"}}}");
        String line;
        do {
            line = stdout.readLine();
        } while (line != null && !line.contains("\"id\":2"));
        assertThat(line).as("the get_health response").isNotNull();
        assertThat(probe.record().args()).as("the client probe ran").containsExactly("-v");
        CompletableFuture.runAsync(() -> readAll(process.getInputStream()));
        FakeStartCli cli = FakeStartCli.install(cliHome, Map.of(
            ArgvRecorder.CHILD_SLEEP_MILLIS, "300000", ArgvRecorder.SLEEP_MILLIS, "300000"));
        Files.delete(cli.recordFile());
        send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"list_inventory\",\"arguments\":{\"target\":\"dev/node1\","
            + "\"kind\":\"MODULE\"}}}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(cli.recordFile()) && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("server died early").isTrue();
            Thread.sleep(50);
        }
        assertThat(cli.recordFile()).as("the fake export runs").exists();
        assertThat(ssm.getAllServeEvents()).as("the credentials came from the SSM stub")
            .isNotEmpty();
        try (Stream<Path> files = Files.list(tmp)) {
            // 002 research D-8: inubit-mcp-export-<profile>-<pid>-<random>
            assertThat(files.filter(path -> path.getFileName().toString()
                .startsWith(CliResources.EXPORT_PREFIX + "acme-" + process.pid() + "-"))
                .toList()).as("the export directory exists while StartCLI runs").isNotEmpty();
        }
        return new Child(process, stdin, cli, tmp, stderr);
    }

    private static void send(OutputStream stdin, String line) throws IOException {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
