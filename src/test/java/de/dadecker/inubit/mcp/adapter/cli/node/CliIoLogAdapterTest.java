package de.dadecker.inubit.mcp.adapter.cli.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.node.IoDirection;
import de.dadecker.inubit.mcp.domain.model.node.IoLogBounds;
import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.port.IoLogPort.IoLogRead;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T027 (feature 024, FR-020 … FR-023, research D-8, D-9, D-20): {@code read_io_log}'s port through
 * the INUBIT client helper. A fake launcher reads the request from stdin, writes the
 * {@code .list} and {@code .message} of a {@code node-tools/v8_1/io-log} case (or of the test) to
 * the request's {@code listOut} and {@code messageOut}, and answers with the case's frames,
 * carrying the request's nonce. The request names the message by its reference only; nothing is
 * left below the temporary root after the call (or, for a read message, after its close);
 * argument array, environment, logs, results and errors are checked for leaks.
 */
@Timeout(60)
class CliIoLogAdapterTest {

    private static final NodeId NODE = NodeId.parse("test/node1");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final String FIXTURES = "node-tools/v8_1/io-log/";
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final String PROCESS = "4711";
    private static final String WORKFLOW = "Orders-Inbound";
    private static final Duration BUDGET = Duration.ofSeconds(20);
    private static final String UNEXPECTED = "read_io_log got an unexpected answer from"
        + " test/node1; the unsupported INUBIT interface may have changed after an INUBIT update";
    private static final String UNEXPECTED_NEXT_STEP =
        "Use the Workbench's input/output logging view; report the INUBIT version";
    private static final String IN_1 = "4711@@@Order Connector(Module-0003)@@@Input.1";
    private static final String OUT_1 = "4711@@@Order Connector(Module-0003)@@@Output.1";
    private static final String IN_2 = "4711@@@Audit Logger(Module-0007)@@@Input.2";
    private static final String OUT_2 = "4711@@@Audit Logger(Module-0007)@@@Output.2";
    private static final String LISTING = IN_1 + "\n" + OUT_1 + "\n" + IN_2 + "\n" + OUT_2 + "\n";
    /** The server's environment, with credentials the helper must never get (leak check). */
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD,
        "INUBIT_TRUST_STORE_PASSWORD", TestSecrets.TRUST_STORE_PASSWORD);

    @TempDir
    Path root;

    private Path tempRoot;
    private Path home;
    private Path jdk;
    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();
    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private FakeProcessLauncher launcher;
    private CredentialGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createDirectories(root.resolve("tmp"));
        home = client();
        jdk = jdk();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
        resources.close();
        for (ILoggingEvent event : appender.list) {
            TestSecrets.assertNoLeak(event.getFormattedMessage());
        }
    }

    // --- the reconstructed listing cases (T026) ----------------------------------------------

    @Test
    void theListingCaseListsTheFourEntriesInServerOrderAndTheRequestHasNoRef()
        throws IOException {
        launcher = replaying("listing");
        EffectiveNodeConfig server = http().build();

        IoLogListing listing = adapter(server).list(PROCESS, WORKFLOW, V81, deadline());

        assertThat(listing.answeringNode()).isEqualTo("node1");
        assertThat(listing.entries()).extracting(IoLogEntry::entry)
            .containsExactly(IN_1, OUT_1, IN_2, OUT_2);
        assertThat(listing.entries().get(0)).isEqualTo(new IoLogEntry(IN_1, "Order Connector",
            Optional.of("Module-0003"), IoDirection.INPUT, 1, IoMessageRef.of(IN_1)));
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsOnlyKeys("qe", "op", "nonce", "variant", "url", "trustStore",
            "trustStorePassword", "hostnameVerification", "processId", "workflow", "ref",
            "listOut", "messageOut", "user", "password");
        assertThat(request).containsEntry("op", "ioLog")
            .containsEntry("processId", PROCESS)
            .containsEntry("workflow", WORKFLOW)
            .containsEntry("ref", "")
            .containsEntry("messageOut", "")
            .containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(Path.of(request.get("listOut"))).startsWithRaw(tempRoot);
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutRequestData(launcher.last().spec());
        TestSecrets.assertNoLeak(listing.toString());
        assertTempRootEmpty();
    }

    @Test
    void theEmptyListingCaseHasNoEntries() throws IOException {
        launcher = replaying("empty_listing");

        IoLogListing listing = adapter(http().build()).list(PROCESS, WORKFLOW, V81, deadline());

        assertThat(listing.answeringNode()).isEqualTo("node1");
        assertThat(listing.entries()).isEmpty();
        assertTempRootEmpty();
    }

    @Test
    void anEntryOfAnotherProcessIsAnUnexpectedResponseThatDoesNotQuoteIt() throws IOException {
        launcher = replaying("foreign_process");

        ToolError error = listFailure(adapter(http().build()), deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.nextStep()).isEqualTo(UNEXPECTED_NEXT_STEP);
        assertThat(error.toString()).doesNotContain("4712", "Order Connector");
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inubitsNotAllowedAccessIsForbiddenWithoutEchoingInubitsText(boolean read)
        throws IOException {
        launcher = replaying("list_error");
        CliIoLogAdapter adapter = adapter(http().build());

        ToolError error = read
            ? readFailure(adapter, IoMessageRef.of(IN_1), deadline())
            : listFailure(adapter, deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.message())
            .isEqualTo("test/node1 refused the input/output logging for the configured user");
        assertThat(error.likelyCause()).isEqualTo("The INUBIT user of this node lacks the right"
            + " to read the input/output logging of modules");
        assertThat(error.nextStep()).isEqualTo("Give the user the right the Workbench's"
            + " input/output logging view needs");
        assertThat(error.excerpt()).isEmpty();
        assertThat(error.toString()).doesNotContain(USER, "NotAllowedAccess", "not allowed");
        assertTempRootEmpty();
    }

    // --- the reconstructed read cases (T026) -------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "read_text, 8Ms_5EiSD7RMDCC3y_IHWQ, " + IN_1,
        "read_binary, AuWK7KgUfUwRitANZEk6DQ, " + OUT_1,
        "read_latin1, j32YwU5QsSIUJv-hUcf7Ag, " + IN_2,
        "read_empty, EsM8_uUIQX6y0OMatY9-PQ, " + OUT_2})
    void aMatchedMessageIsTheHelpersFileUntilTheReadIsClosed(String fixtureCase, String ref,
        String entry) throws IOException {
        launcher = replaying(fixtureCase);
        byte[] expected = FakeProcessLauncher.resource(FIXTURES + fixtureCase + ".message");

        IoLogRead read = adapter(http().build()).read(PROCESS, WORKFLOW, new IoMessageRef(ref),
            V81, deadline());

        IoLogRead.Message message = (IoLogRead.Message) read;
        assertThat(message.listing().answeringNode()).isEqualTo("node1");
        assertThat(message.listing().entries()).hasSize(4);
        assertThat(message.entry().entry()).isEqualTo(entry);
        assertThat(message.entry().ref()).isEqualTo(new IoMessageRef(ref));
        assertThat(message.size()).isEqualTo(expected.length);
        assertThat(message.file()).startsWithRaw(tempRoot);
        assertThat(Files.readAllBytes(message.file())).isEqualTo(expected);

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsEntry("op", "ioLog")
            .containsEntry("processId", PROCESS)
            .containsEntry("workflow", WORKFLOW)
            .containsEntry("ref", ref);
        assertThat(Path.of(request.get("messageOut"))).startsWithRaw(tempRoot);
        assertThat(request.values()).as("the entry text is never sent, only its reference")
            .noneMatch(value -> value.contains("@@@") || value.contains("Module-000"));
        assertLaunchedWithoutRequestData(launcher.last().spec());

        assertThat(tempRootEntries()).as("the private directory stays until the close")
            .isNotEmpty();
        read.close();
        assertTempRootEmpty();
        read.close();
    }

    @Test
    void anUnlistedReferenceIsNoMatchWithTheListingAndLeavesNothing() throws IOException {
        launcher = replaying("no_match");

        IoLogRead read = adapter(http().build()).read(PROCESS, WORKFLOW,
            new IoMessageRef("c4rUwmJf1jCgErmFirSnMA"), V81, deadline());

        assertThat(read).isInstanceOf(IoLogRead.NoMatch.class);
        assertThat(read.listing().entries()).extracting(IoLogEntry::entry)
            .containsExactly(IN_1, OUT_1, IN_2, OUT_2);
        assertThat(decode(launcher.last().stdinBytes()))
            .containsEntry("ref", "c4rUwmJf1jCgErmFirSnMA");
        assertTempRootEmpty();
    }

    // --- the io rules (D-8, D-9, D-20) -------------------------------------------------------

    @Test
    void aListingAbove16MibIsAnUnexpectedResponse() throws IOException {
        launcher = answering(fields -> {
            try (RandomAccessFile file =
                     new RandomAccessFile(fields.get("listOut"), "rw")) {
                file.setLength(IoLogBounds.MAX_LIST_BYTES + 1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return answer(fields, "io list ok 1");
        });

        ToolError error = listFailure(adapter(http().build()), deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.likelyCause()).contains("16777216 bytes");
        assertTempRootEmpty();
    }

    @Test
    void aListingOfExactly16MibIsRead() throws IOException {
        String entry = IN_1 + "\n";
        int count = (int) (IoLogBounds.MAX_LIST_BYTES / entry.length());
        // the last entry fills the listing up to exactly 16 MiB
        String pad = "4711@@@" + "m".repeat((int) (IoLogBounds.MAX_LIST_BYTES
            - (long) (count - 1) * entry.length()) - "4711@@@@@@Input.1\n".length())
            + "@@@Input.1\n";
        launcher = answering(fields -> {
            write(fields.get("listOut"), (entry.repeat(count - 1) + pad)
                .getBytes(StandardCharsets.UTF_8));
            return answer(fields, "io list ok " + count);
        });

        IoLogListing listing = adapter(http().build()).list(PROCESS, WORKFLOW, V81, deadline());

        assertThat(listing.entries()).hasSize(count);
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "io list ok 3",
        "io list ok 5",
        "io list ok 4|io match none",
        "io list ok 4|io list ok 4",
        "",
        "io match none",
        "part message ok 0"})
    void listingFramesThatBreakTheRulesAreAnUnexpectedResponse(String frames)
        throws IOException {
        launcher = answering(fields -> {
            write(fields.get("listOut"), LISTING.getBytes(StandardCharsets.UTF_8));
            return answer(fields, operationFrames(frames));
        });

        ToolError error = listFailure(adapter(http().build()), deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertTempRootEmpty();
    }

    @Test
    void aMissingListingFileIsAnUnexpectedResponse() throws IOException {
        launcher = answering(fields -> answer(fields, "io list ok 0"));

        ToolError error = listFailure(adapter(http().build()), deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertTempRootEmpty();
    }

    @Test
    void anotherIoListErrorIsAnUnexpectedResponseWithAScrubbedExcerpt() throws IOException {
        launcher = answering(fields -> answer(fields, "io list error"
            + " com.inubit.ibis.utils.InubitException Listing%20failed%20for%20"
            + enc(TestSecrets.CONFIGURED_PASSWORD)));

        ToolError error = listFailure(adapter(http().build()), deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.nextStep()).isEqualTo(UNEXPECTED_NEXT_STEP);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).startsWith("Listing failed for "));
        assertTempRootEmpty();
    }

    @Test
    void aPartMessageErrorIsAnUnexpectedResponseAndLeavesNothing() throws IOException {
        launcher = answering(fields -> {
            write(fields.get("listOut"), LISTING.getBytes(StandardCharsets.UTF_8));
            return answer(fields, "io list ok 4", "io match " + enc(IN_1),
                "part message error com.inubit.ibis.utils.InubitException Download%20failed");
        });

        ToolError error = readFailure(adapter(http().build()), IoMessageRef.of(IN_1),
            deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.excerpt()).hasValueSatisfying(
            excerpt -> assertThat(excerpt).startsWith("Download failed"));
        assertTempRootEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        // the reported size does not match the file
        "4; io list ok 4|io match IN_1|part message ok 5",
        // no message file
        "-1; io list ok 4|io match IN_1|part message ok 0",
        // the helper matched another entry than the requested reference
        "4; io list ok 4|io match OUT_1|part message ok 4",
        // the helper matched an entry that is not listed
        "4; io list ok 4|io match 4711%40%40%40X%28Y%29%40%40%40Input.9|part message ok 4",
        // the helper found no match although the reference is listed
        "-1; io list ok 4|io match none",
        // no match frame, a part of another kind, frames after the part, no part
        "4; io list ok 4|part message ok 4",
        "4; io list ok 4|io match IN_1|part data ok 4",
        "4; io list ok 4|io match IN_1|part message ok 4|io match none",
        "4; io list ok 4|io match IN_1",
        "-1; io list ok 4|io match none|part message ok 0"})
    void readFramesThatBreakTheRulesAreAnUnexpectedResponse(int messageBytes, String frames)
        throws IOException {
        launcher = answering(fields -> {
            write(fields.get("listOut"), LISTING.getBytes(StandardCharsets.UTF_8));
            if (messageBytes >= 0) {
                write(fields.get("messageOut"), new byte[messageBytes]);
            }
            return answer(fields, operationFrames(frames.replace("IN_1", enc(IN_1))
                .replace("OUT_1", enc(OUT_1))));
        });

        ToolError error = readFailure(adapter(http().build()), IoMessageRef.of(IN_1),
            deadline());

        assertThat(error.code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
        assertThat(error.message()).isEqualTo(UNEXPECTED);
        assertThat(error.toString()).doesNotContain("Order Connector");
        assertTempRootEmpty();
    }

    // --- the budget settings ----------------------------------------------------------------

    @Test
    void aHangingListingTimesOutNamingCliTimeout() throws IOException {
        launcher = answering(fields -> answer(fields, "io list ok 0")).hanging();

        ToolError error = listFailure(adapter(http().build()),
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliTimeout ");
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertTempRootEmpty();
    }

    @Test
    void aHangingReadTimesOutNamingCliExportTimeoutAndLeavesNothing() throws IOException {
        launcher = answering(fields -> {
            write(fields.get("listOut"), LISTING.getBytes(StandardCharsets.UTF_8));
            write(fields.get("messageOut"), new byte[16]);
            return answer(fields, "io list ok 4", "io match " + enc(IN_1),
                "part message ok 16");
        }).hanging();

        ToolError error = readFailure(adapter(http().build()), IoMessageRef.of(IN_1),
            System.nanoTime() + Duration.ofMillis(500).toNanos());

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.nextStep()).contains("raise cliExportTimeout ");
        assertThat(launcher.last().destroyed()).as("process stopped").isTrue();
        assertTempRootEmpty();
    }

    // --- helpers ----------------------------------------------------------------------------

    private CliIoLogAdapter adapter(EffectiveNodeConfig server) {
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        NodeHelperRunner helper = new NodeHelperRunner(server, () -> credentials, guard, runner,
            TestCliRunners.helperHost(PARENT, resources, tempRoot),
            new ProcessRun(launcher, resources), limiter);
        return new CliIoLogAdapter(helper, scrubber, server.secretLabels());
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id(NODE.value()).baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private static long deadline() {
        return System.nanoTime() + BUDGET.toNanos();
    }

    /** The error of a listing; it carries the node and leaks nothing. */
    private static ToolError listFailure(CliIoLogAdapter adapter, long deadline) {
        return failure(catchThrowableOfType(ToolErrorException.class,
            () -> adapter.list(PROCESS, WORKFLOW, V81, deadline)));
    }

    /** The error of a read; it carries the node and leaks nothing. */
    private static ToolError readFailure(CliIoLogAdapter adapter, IoMessageRef ref,
        long deadline) {
        return failure(catchThrowableOfType(ToolErrorException.class,
            () -> adapter.read(PROCESS, WORKFLOW, ref, V81, deadline)));
    }

    private static ToolError failure(ToolErrorException thrown) {
        assertThat(thrown).as("the call fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    private List<Path> tempRootEntries() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            return entries.toList();
        }
    }

    private void assertTempRootEmpty() throws IOException {
        assertThat(tempRootEntries()).as("nothing left below the temporary directory").isEmpty();
    }

    /** The argv names the JVM, its options and the helper class; nothing of the request. */
    private static void assertLaunchedWithoutRequestData(LaunchSpec spec) {
        String argv = String.join(" ", spec.command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(argv).doesNotContain(USER, "ioLog", WORKFLOW, "inubit.example.test");
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
    }

    /**
     * Replays {@code <case>.frames}/{@code .exit} of the io-log fixtures with the nonce, after
     * writing the case's {@code .list} and {@code .message} (if it has them) to the request's
     * {@code listOut} and {@code messageOut}.
     */
    private static FakeProcessLauncher replaying(String fixtureCase) {
        String frames = new String(FakeProcessLauncher.resource(FIXTURES + fixtureCase
            + ".frames"), StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(FakeProcessLauncher.resource(FIXTURES + fixtureCase
            + ".exit"), StandardCharsets.UTF_8).strip());
        return answering(fields -> {
            copyIfPresent(fixtureCase + ".list", fields.get("listOut"));
            copyIfPresent(fixtureCase + ".message", fields.get("messageOut"));
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")), exit);
        });
    }

    private static void copyIfPresent(String fixtureFile, String target) {
        if (CliIoLogAdapterTest.class.getClassLoader()
            .getResource("fixtures/" + FIXTURES + fixtureFile) != null) {
            write(target, FakeProcessLauncher.resource(FIXTURES + fixtureFile));
        }
    }

    private static void write(String target, byte[] bytes) {
        try {
            Files.write(Path.of(target), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FakeProcessLauncher answering(
        Function<Map<String, String>, Answer> responder) {
        return FakeProcessLauncher.of("", "", 0)
            .responding(request -> responder.apply(decode(request)));
    }

    /** The frames of {@code frames}, separated by {@code |}; none for an empty text. */
    private static String[] operationFrames(String frames) {
        return frames.isEmpty() ? new String[0] : frames.split("\\|");
    }

    /**
     * A complete run (exit 0) with the operation frames {@code frames} between {@code node ok
     * node1} and {@code end}; the frames are written as given, their tokens already encoded.
     */
    private static Answer answer(Map<String, String> fields, String... frames) {
        List<String> all = new ArrayList<>(List.of("start SOAP_81", "login ok", "node ok node1"));
        all.addAll(List.of(frames));
        all.add("end");
        StringBuilder out = new StringBuilder("noise before the frames\n");
        for (String frame : all) {
            out.append("#QE1:").append(fields.get("nonce")).append(' ').append(frame)
                .append('\n');
        }
        return new Answer(out.toString(), 0);
    }

    /** One token, percent-encoded as the helper writes it. */
    private static String enc(String token) {
        return URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The request's {@code key=value} lines up to {@code end}, percent-decoded. */
    private static Map<String, String> decode(byte[] request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(request, StandardCharsets.US_ASCII).split("\n")) {
            if (line.equals("end")) {
                break;
            }
            int equals = line.indexOf('=');
            fields.put(line.substring(0, equals), percentDecode(line.substring(equals + 1)));
        }
        return fields;
    }

    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                bytes.writeBytes(HexFormat.of().parseHex(value, i + 1, i + 3));
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** A client home with {@code lib/ibis.jar}, {@code lib/shared/} and {@code conf/}. */
    private Path client() throws IOException {
        Path client = root.resolve("client");
        Files.createDirectories(client.resolve("lib").resolve("shared"));
        Files.createDirectories(client.resolve("conf"));
        Files.createFile(client.resolve("lib").resolve("ibis.jar"));
        return client;
    }

    /** A JDK directory with an executable {@code bin/java} and {@code bin/java.exe}. */
    private Path jdk() throws IOException {
        Path directory = root.resolve("jdk");
        Path bin = Files.createDirectories(directory.resolve("bin"));
        for (String name : List.of("java", "java.exe")) {
            Path java = Files.createFile(bin.resolve(name));
            if (!Platform.windows()) {
                Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
            }
        }
        return directory;
    }
}
