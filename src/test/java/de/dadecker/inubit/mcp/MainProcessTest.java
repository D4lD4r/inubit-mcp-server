package de.dadecker.inubit.mcp;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the real entry point {@link Main#main} in a child JVM (C1): Logback is configured with
 * the production {@code logback.xml} and {@code -Dlogback.debug=true}, so its status messages,
 * which Logback prints to {@code System.out}, would corrupt the protocol stream unless the stdout
 * guard is installed before any logger exists. The child gets a fake environment only.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MainProcessTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "process-test-Pw-0815";

    @TempDir
    Path home;

    /** The child of the running test; killed afterwards, so that a hang cannot outlive it. */
    private Process child;

    @AfterEach
    void killChild() {
        if (child != null && child.isAlive()) {
            child.destroyForcibly();
        }
    }

    /** What a child JVM printed: the protocol lines, anything after them, and stderr. */
    private record Run(int exit, List<String> lines, String rest, String stderr) {
    }

    @Test
    void stdoutCarriesOnlyJsonRpcEvenWithLogbackDebugOutput() throws Exception {
        Run run = runMain("-Dlogback.debug=true");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.rest()).as("nothing after the response, no partial line").isEmpty();
        assertThat(run.lines()).isNotEmpty().allSatisfy(json -> {
            assertThat(json).as("protocol line").startsWith("{");
            JsonNode message = JSON.readTree(json);
            assertThat(message.path("jsonrpc").asString()).as(json).isEqualTo("2.0");
        });
        assertThat(run.lines().getLast()).contains("\"serverInfo\"");
        assertThat(run.stderr()).as("Logback debug output went to stderr").contains("logback");
        assertThat(run.stderr()).as("config warnings are printed to stderr").contains("V9_X");
        assertThat(run.stderr() + String.join("\n", run.lines())).doesNotContain(PASSWORD);
    }

    @Test
    void aNormalStartPrintsNoLogbackStatusNoise() throws Exception {
        Run run = runMain();

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.lines()).singleElement().asString().startsWith("{\"jsonrpc\":\"2.0\"");
        assertThat(run.stderr()).doesNotContain("|-INFO in ch.qos.logback");
    }

    @Test
    void blankAndMalformedLinesBeforeARequestNeitherStopTheSessionNorTheExitOnEof()
        throws Exception {
        // 002 US2 review: the SDK stopped reading at the first line it could not parse, so a
        // blank line kept the process alive after stdin was closed
        Run run = runMainAfter("\n  \r\nnot json at all\n{\"no\":\"json-rpc\"}\n");

        assertThat(run.exit()).as(run.stderr()).isZero();
        assertThat(run.lines()).last().asString().contains("\"id\":1", "\"serverInfo\"");
        assertThat(run.stderr()).doesNotContain("Error processing inbound message")
            .doesNotContain("not json at all");
    }

    private Run runMain(String... jvmOptions) throws Exception {
        return runMainAfter("", jvmOptions);
    }

    /** @param preamble raw input sent before the {@code initialize} request */
    private Run runMainAfter(String preamble, String... jvmOptions) throws Exception {
        Path config = home.resolve("config.yaml");
        Files.writeString(config, """
            profile:
              name: acme
            auditDirectory: %s
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                    versionLine: V9_X
            """.formatted(home.resolve("audit")));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(),
            "-cp", System.getProperty("java.class.path"),
            "-Dlogback.configurationFile=logback.xml", "-Duser.home=" + home));
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of(Main.class.getName(), "--config", config.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("INUBIT_ACME_DEV_USERNAME", "process-test-user");
        environment.put("INUBIT_ACME_DEV_PASSWORD", PASSWORD);
        Process process = builder.start();
        child = process;
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
            () -> readAll(process.getErrorStream()));

        OutputStream stdin = process.getOutputStream();
        stdin.write(preamble.getBytes(StandardCharsets.UTF_8));
        stdin.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}\n")
            .getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        BufferedReader stdout = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = stdout.readLine()) != null) {
            lines.add(line);
            if (line.contains("\"id\":1")) {
                break;
            }
        }
        stdin.close();
        String rest = readAll(process.getInputStream());
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertThat(exited).as("the server exits after stdin is closed").isTrue();
        String errors = stderr.get(30, TimeUnit.SECONDS);

        return new Run(process.exitValue(), lines, rest, errors);
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
