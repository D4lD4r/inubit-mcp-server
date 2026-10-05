package de.dadecker.inubit.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 review I1: the real server ({@link Main}) in a child JVM runs a module export through a
 * fake {@code startcli.sh} that forks a long {@code sleep}. When the MCP client closes stdin, or
 * the server gets SIGTERM, no StartCLI process may survive and the export directory must be gone.
 * A stale export directory of a dead server process is swept at startup.
 */
@Timeout(90)
class ExportShutdownProcessTest {

    private static final String PASSWORD = "export-shutdown-Pw-4711";
    private static WireMockServer wireMock;

    @TempDir
    Path home;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
        wireMock.stubFor(get(urlPathEqualTo("/ibis/rest/system/info"))
            .willReturn(RestFixtures.response("system_info", "xml")));
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @Test
    void closingStdinStopsTheExportAndDeletesItsDirectory() throws Exception {
        long dead = deadPid();
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

    /** {@code inubit-mcp-export-<middle>-1} with a file, in the server's temp directory. */
    private Path staleExport(String middle) throws IOException {
        Path directory = Files.createDirectories(tmp().resolve(CliResources.EXPORT_PREFIX
            + middle + "-1"));
        Files.writeString(directory.resolve("modules.zip"), "stale");
        return directory;
    }

    @Test
    void sigtermStopsTheExportAndDeletesItsDirectory() throws Exception {
        Child child = startExport();
        // pure SIGTERM: stdin stays open, only the JVM shutdown hook cleans up
        // (Process.destroy() would also close the pipes, i.e. EOF + SIGTERM)
        assertThat(child.process().toHandle().destroy()).isTrue();

        child.assertCleanShutdown();
    }

    @Test
    void eofAndSigtermTogetherStopTheExportAndDeleteItsDirectory() throws Exception {
        // Phase 6 review W2: the main thread (stdin EOF) and the shutdown hook (SIGTERM) close
        // the CLI resources concurrently; the JVM must not exit before the trees are stopped
        Child child = startExport();
        child.stdin().close();
        assertThat(child.process().toHandle().destroy()).isTrue();

        child.assertCleanShutdown();
    }

    private Path tmp() throws IOException {
        return Files.createDirectories(home.resolve("tmp"));
    }

    private record Child(Process process, OutputStream stdin, Path cliHome, Path tmp,
        Path stderr) {

        void assertCleanShutdown() throws Exception {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("server exits").isTrue();
            String errors = Files.readString(stderr);
            for (String name : List.of("startcli.pid", "sleep.pid")) {
                long pid = Long.parseLong(Files.readString(cliHome.resolve(name)).strip());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (alive(pid) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(alive(pid)).as(name + " still running; stderr: " + errors).isFalse();
            }
            try (Stream<Path> files = Files.list(tmp)) {
                assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(CliResources.EXPORT_PREFIX + "acme-"))
                    .toList())
                    .as(errors).isEmpty();
            }
            assertThat(errors).doesNotContain(PASSWORD);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static long deadPid() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        process.waitFor();
        return process.pid();
    }

    private Child startExport() throws Exception {
        Path cliHome = Files.createDirectories(home.resolve("client"));
        Path script = Files.createDirectories(cliHome.resolve("bin")).resolve("startcli.sh");
        Files.writeString(script, """
            #!/bin/sh
            echo $$ > "%1$s/startcli.pid.tmp" && mv "%1$s/startcli.pid.tmp" "%1$s/startcli.pid"
            sleep 300 &
            echo $! > "%1$s/sleep.pid.tmp" && mv "%1$s/sleep.pid.tmp" "%1$s/sleep.pid"
            wait
            """.formatted(cliHome));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
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
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("INUBIT_ACME_DEV_USERNAME", "export-test-user");
        environment.put("INUBIT_ACME_DEV_PASSWORD", PASSWORD);
        // stderr to a file: it stays readable whatever happens to the pipes
        Path stderr = home.resolve("server-stderr.log");
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        CompletableFuture.runAsync(() -> readAll(process.getInputStream()));
        OutputStream stdin = process.getOutputStream();
        send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
        send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"list_inventory\",\"arguments\":{\"target\":\"dev/node1\","
            + "\"kind\":\"MODULE\"}}}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(cliHome.resolve("sleep.pid")) && System.nanoTime() < deadline) {
            assertThat(process.isAlive()).as("server died early").isTrue();
            Thread.sleep(50);
        }
        assertThat(cliHome.resolve("sleep.pid")).as("the fake export runs").exists();
        try (Stream<Path> files = Files.list(tmp)) {
            // 002 research D-8: inubit-mcp-export-<profile>-<pid>-<random>
            assertThat(files.filter(path -> path.getFileName().toString()
                .startsWith(CliResources.EXPORT_PREFIX + "acme-" + process.pid() + "-"))
                .toList()).as("the export directory exists while StartCLI runs").isNotEmpty();
        }
        return new Child(process, stdin, cliHome, tmp, stderr);
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
