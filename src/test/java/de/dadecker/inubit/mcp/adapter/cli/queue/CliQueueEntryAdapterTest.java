package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.NodeSecrets;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.rest.TlsTestServer;
import de.dadecker.inubit.mcp.config.CredentialNames;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.QueueEntryBounds;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent;
import de.dadecker.inubit.mcp.domain.model.RawEntryContent.PartResult;
import de.dadecker.inubit.mcp.domain.model.RawVariable;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The queue-entry helper as {@link de.dadecker.inubit.mcp.domain.port.QueueEntryContentPort}
 * (feature 020, research D-3…D-8, D-12, D-14): a fake launcher reads the request from stdin,
 * writes the case's files where the request says and answers with frames carrying the request's
 * nonce. After every outcome nothing is left below the temporary root; argument array,
 * environment, logs, errors and part reasons are checked for leaks (SC-003).
 */
@Timeout(60)
class CliQueueEntryAdapterTest {

    private static final NodeId NODE = NodeId.parse("int/a");
    private static final InubitVersion V81 = InubitVersion.parse("8.1.17").orElseThrow();
    private static final InubitVersion V90 = InubitVersion.parse("9.0.10").orElseThrow();
    private static final String WORKFLOW = "Workflow-0001";
    private static final String FILE = "module-0001(1001)@100000001@"
        + "00000000-0a00-0000-0000-000000000001_1790861373736";
    private static final QueueEntryRef REF = new QueueEntryRef(NODE, WORKFLOW, FILE, 772);
    private static final Set<EntryPart> BOTH = Set.of(EntryPart.DATA, EntryPart.VARIABLES);
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final byte[] DATA = "<order id=\"Order-0001\"/>".getBytes(StandardCharsets.UTF_8);
    private static final String WFSP = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<Properties version=\"4.1\"><Property name=\"orderId\">Order-0001</Property>"
        + "<Property name=\"retries\" type=\"Integer\">3</Property></Properties>";
    private static final List<RawVariable> VARIABLES = List.of(
        new RawVariable("orderId", Optional.empty(), "Order-0001"),
        new RawVariable("retries", Optional.of("Integer"), "3"));
    private static final TestCertificates CERTS = TestCertificates.get();
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
    private final CliConcurrencyLimiter limiter =
        new CliConcurrencyLimiter(CapabilityMatrix.production());
    private final SecretScrubber scrubber = new SecretScrubber();
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
        logger.setLevel(Level.DEBUG);
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

    // --- success ---------------------------------------------------------------------------

    @Test
    void aCompleteReadReturnsTheDataAndTheParsedVariables() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length, WFSP.length());
        });
        EffectiveNodeConfig server = http().build();

        RawEntryContent content = adapter(server).read(REF, V81, BOTH);

        assertThat(content.data()).isInstanceOfSatisfying(PartResult.Read.class,
            read -> assertThat((byte[]) read.value()).isEqualTo(DATA));
        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertThat(guard.confirmed()).as("the login was accepted").isTrue();

        Map<String, String> request = decode(launcher.last().stdinBytes());
        assertThat(request).containsEntry("variant", "SOAP_81")
            .containsEntry("url", server.cli().url().toString())
            .containsEntry("trustStore", "")
            .containsEntry("trustStorePassword", "")
            .containsEntry("hostnameVerification", "true")
            .containsEntry("objectType", "queue_manager@@@Workflow-0001@@@error")
            .containsEntry("fileName", FILE)
            .containsEntry("parts", "data,variables")
            .containsEntry("user", USER)
            .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
        assertThat(request.get("nonce")).matches("[0-9a-f]{32}");
        Path directory = Path.of(request.get("variablesOut")).getParent().getParent();
        assertThat(directory.getParent()).isEqualTo(tempRoot);
        assertThat(Path.of(request.get("dataOut"))).isEqualTo(directory.resolve("out")
            .resolve("data.bin"));
        assertThat(Path.of(request.get("variablesOut"))).isEqualTo(directory.resolve("out")
            .resolve("variables.wfsp"));
        assertThat(launcher.last().stdinClosed()).isTrue();
        assertLaunchedWithoutSecrets(launcher.last().spec());
        assertTempRootEmpty();
    }

    @Test
    void nineZeroUsesTheRestVariant() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "REST_90", DATA.length, WFSP.length());
        });

        RawEntryContent content = adapter(http().build()).read(REF, V90, BOTH);

        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertThat(decode(launcher.last().stdinBytes())).containsEntry("variant", "REST_90");
        assertTempRootEmpty();
    }

    @Test
    void aVariablesOnlyReadRequestsNoDataPart() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return answer(0, fields, "start SOAP_81", "login ok",
                "part variables ok " + WFSP.length(), "end");
        });

        RawEntryContent content = adapter(http().build())
            .read(REF, V81, Set.of(EntryPart.VARIABLES));

        assertThat(content.data()).isInstanceOf(PartResult.NotRequested.class);
        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertThat(decode(launcher.last().stdinBytes())).containsEntry("parts", "variables")
            .containsEntry("dataOut", "");
        assertTempRootEmpty();
    }

    @Test
    void aDataOnlyReadReportsTheVariablesAsNotRequested() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length, WFSP.length());
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, Set.of(EntryPart.DATA));

        assertThat(content.data()).isInstanceOf(PartResult.Read.class);
        assertThat(content.variables()).isInstanceOf(PartResult.NotRequested.class);
        assertTempRootEmpty();
    }

    // --- the 10 MiB limit and empty files (D-12) -------------------------------------------

    @Test
    void aWfspOverTenMebibytesIsTooLargeAndADataFileOfExactlyTheLimitIsRead() throws IOException {
        int limit = Math.toIntExact(QueueEntryBounds.MAX_DATA_BYTES);
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), new byte[limit]);
            write(fields.get("variablesOut"), new byte[limit + 1]);
            return success(fields, "SOAP_81", limit, limit + 1);
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isInstanceOfSatisfying(PartResult.Read.class,
            read -> assertThat((byte[]) read.value()).hasSize(limit));
        assertThat(content.variables()).isEqualTo(new PartResult.TooLarge<>(limit + 1L));
        assertTempRootEmpty();
    }

    @Test
    void aDataFileOverTenMebibytesIsTooLarge() throws IOException {
        long size = QueueEntryBounds.MAX_DATA_BYTES + 1;
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), new byte[Math.toIntExact(size)]);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", size, WFSP.length());
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isEqualTo(new PartResult.TooLarge<>(size));
        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertTempRootEmpty();
    }

    @Test
    void withoutAFileTheDataStreamIsEmptyAndTheVariablesHaveAnUnexpectedFormat()
        throws IOException {
        // the helper reports the length of the file the vendor wrote, 0 if it wrote none
        launcher = FakeProcessLauncher.of("", "", 0).responding(
            request -> success(decode(request), "SOAP_81", 0, 0));

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isInstanceOfSatisfying(PartResult.Read.class,
            read -> assertThat((byte[]) read.value()).isEmpty());
        assertThat(content.variables()).isEqualTo(
            new PartResult.Failed<>(WfspParser.UNEXPECTED_FORMAT));
        assertTempRootEmpty();
    }

    @Test
    void aMissingFileWithAReportedLengthFailsThatPart() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(
            request -> success(decode(request), "SOAP_81", DATA.length, WFSP.length()));

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isEqualTo(
            new PartResult.Failed<>(CliQueueEntryAdapter.LENGTH_MISMATCH));
        assertThat(content.variables()).isEqualTo(
            new PartResult.Failed<>(CliQueueEntryAdapter.LENGTH_MISMATCH));
        assertTempRootEmpty();
    }

    @Test
    void aFileOfAnotherLengthThanReportedFailsThatPart() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length + 1, WFSP.length() - 1);
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isEqualTo(
            new PartResult.Failed<>(CliQueueEntryAdapter.LENGTH_MISMATCH));
        assertThat(content.variables()).isEqualTo(
            new PartResult.Failed<>(CliQueueEntryAdapter.LENGTH_MISMATCH));
        TestSecrets.assertNoLeak(CliQueueEntryAdapter.LENGTH_MISMATCH);
        assertThat(CliQueueEntryAdapter.LENGTH_MISMATCH).doesNotContain("Order-0001", FILE);
        assertTempRootEmpty();
    }

    @Test
    void aFileOverTenMebibytesIsTooLargeWhateverLengthWasReported() throws IOException {
        long size = QueueEntryBounds.MAX_DATA_BYTES + 1;
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), new byte[Math.toIntExact(size)]);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length, WFSP.length());
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isEqualTo(new PartResult.TooLarge<>(size));
        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertTempRootEmpty();
    }

    @Test
    void anEmptyDataFileIsAnEmptyDataStream() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), new byte[0]);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", 0, WFSP.length());
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isInstanceOfSatisfying(PartResult.Read.class,
            read -> assertThat((byte[]) read.value()).isEmpty());
        assertTempRootEmpty();
    }

    // --- parts not found or failed (D-5, D-9) -----------------------------------------------

    @Test
    void theMissingFileMarkerMakesBothPartsNotFound() throws IOException {
        launcher = replaying("v8_1/not_found_file");

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isEqualTo(new PartResult.NotFound<>());
        assertThat(content.variables()).isEqualTo(new PartResult.NotFound<>());
        assertTempRootEmpty();
    }

    @Test
    void anotherPartErrorFailsThatPartWithAMaskedReason() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return answer(0, fields, "start SOAP_81", "login ok",
                "part data error java.io.IOException @Start@InternalError@@@password="
                    + TestSecrets.CONFIGURED_PASSWORD + "@End@",
                "part variables ok " + WFSP.length(), "end");
        });

        RawEntryContent content = adapter(http().build()).read(REF, V81, BOTH);

        assertThat(content.data()).isInstanceOfSatisfying(PartResult.Failed.class, failed -> {
            assertThat(failed.reason()).startsWith("INUBIT reported an error: ");
            TestSecrets.assertNoLeak(failed.reason());
        });
        assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
        assertTempRootEmpty();
    }

    // --- failures of the whole call: nothing returned, the directory gone ----------------

    @Test
    void aHangingHelperTimesOutWithoutPartialContentAndTheDirectoryIsGone() throws IOException {
        AtomicBoolean written = new AtomicBoolean();
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            written.set(true);
            return new Answer(frame(fields.get("nonce"), "start SOAP_81")
                + frame(fields.get("nonce"), "login ok")
                + frame(fields.get("nonce"), "part data ok " + DATA.length), 0);
        }).hanging();

        ToolError error = failure(adapter(http().cliTimeout(Duration.ofMillis(500)).build()),
            V81);

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).matches(
            "The queue-entry helper did not finish within \\d+ ms on int/a");
        assertThat(written).as("the helper had written a part").isTrue();
        assertThat(launcher.last().destroyed()).isTrue();
        assertTempRootEmpty();
    }

    @Test
    void aTlsMismatchLaunchesNothing() throws Exception {
        launcher = FakeProcessLauncher.of("", "", 0);
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port(),
                TestCertificates.fingerprint(CERTS.selfsignedCertificate()));

            ToolError error = failure(adapter(server), V81);

            assertThat(error.code()).isEqualTo(ErrorCode.TLS_ERROR);
            assertThat(error.message()).endsWith("; the queue-entry helper was not started");
        }
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void anHttpsNodeGetsTheCallsOwnTrustStoreForTheRun() throws Exception {
        AtomicBoolean trustStoreDuringTheRun = new AtomicBoolean();
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            trustStoreDuringTheRun.set(Files.isRegularFile(Path.of(fields.get("trustStore"))));
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length, WFSP.length());
        });
        try (TlsTestServer tls = TlsTestServer.start(CERTS.localhostKeyStore())) {
            EffectiveNodeConfig server = https(tls.port(),
                TestCertificates.fingerprint(CERTS.localhostCertificate()));

            RawEntryContent content = adapter(server).read(REF, V81, BOTH);

            assertThat(content.variables()).isEqualTo(new PartResult.Read<>(VARIABLES));
            assertThat(tls.handshakes()).isEqualTo(1);
        }
        Map<String, String> request = decode(launcher.last().stdinBytes());
        Path directory = Path.of(request.get("variablesOut")).getParent().getParent();
        assertThat(request.get("trustStore"))
            .isEqualTo(directory.resolve(HelperTrustStore.FILE_NAME).toString());
        assertThat(request.get("trustStorePassword")).isNotBlank();
        assertThat(String.join(" ", launcher.last().spec().command()))
            .doesNotContain(request.get("trustStorePassword"));
        assertThat(launcher.last().spec().environment().toString())
            .doesNotContain(request.get("trustStorePassword"));
        assertThat(trustStoreDuringTheRun).isTrue();
        assertTempRootEmpty();
    }

    @Test
    void theSlotWaitAndTheHelperShareOneDeadline() throws Exception {
        launcher = FakeProcessLauncher.of("", "", 0).hanging();
        EffectiveNodeConfig server = http().cliTimeout(Duration.ofSeconds(2)).build();
        CliConcurrencyLimiter.Permit first = limiter.acquireForHelper(server, Duration.ZERO);
        CliConcurrencyLimiter.Permit second = limiter.acquireForHelper(server, Duration.ZERO);
        Thread.ofVirtual().start(() -> {
            sleep(Duration.ofMillis(1200));
            first.close();
        });
        try {
            long start = System.nanoTime();
            ToolError error = failure(adapter(server), V81);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).startsWith("The queue-entry helper did not finish");
            assertThat(launcher.launchCount()).isEqualTo(1);
            assertThat(elapsed).as("one deadline of 2 s, not 2 s per step")
                .isGreaterThanOrEqualTo(Duration.ofMillis(1900))
                .isLessThan(Duration.ofMillis(2900));
        } finally {
            second.close();
        }
        assertTempRootEmpty();
    }

    @Test
    void noFreeSlotWithinTheDeadlineLaunchesNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0);
        EffectiveNodeConfig server = http().cliTimeout(Duration.ofMillis(300)).build();
        try (CliConcurrencyLimiter.Permit first = limiter.acquireForHelper(server, Duration.ZERO);
            CliConcurrencyLimiter.Permit second = limiter.acquireForHelper(server,
                Duration.ZERO)) {

            ToolError error = failure(adapter(server), V81);

            assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(error.message()).startsWith("No StartCLI slot on int/a became free");
        }
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aDeadlinePassedWhileWaitingForAnotherLoginLaunchesNothing() throws Exception {
        launcher = FakeProcessLauncher.of("", "", 0);
        CliQueueEntryAdapter adapter = adapter(http().cliTimeout(Duration.ofMillis(300)).build());
        CountDownLatch held = new CountDownLatch(1);
        Thread other = Thread.ofVirtual().start(() -> {
            try (CredentialGuard.Permit permit = guard.acquire()) {
                held.countDown();
                sleep(Duration.ofMillis(2000));
            }
        });
        assertThat(held.await(5, TimeUnit.SECONDS)).as("the other attempt holds the permit")
            .isTrue();

        ToolError error = failure(adapter, V81);
        other.join();

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).isEqualTo("cliTimeout (300 ms) on int/a passed before the"
            + " login; the queue-entry helper was not started");
        assertThat(error.message()).doesNotContain("within 0 ms");
        assertThat(error.nextStep()).contains("cliTimeout");
        assertThat(launcher.launchCount()).isZero();
        assertThat(guard.confirmed()).as("no login result").isFalse();
        Thread next = Thread.ofVirtual().start(() -> guard.acquire().close());
        assertThat(next.join(Duration.ofSeconds(2))).as("the permit was released").isTrue();
        assertTempRootEmpty();
    }

    @Test
    void aRejectedLoginBacksOffTheGuard() throws IOException {
        launcher = replaying("v8_1/login_failure");
        CliQueueEntryAdapter adapter = adapter(http().build());

        ToolError rejected = failure(adapter, V81);
        ToolError cached = failure(adapter, V81);

        assertThat(rejected.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(cached.message()).contains("authentication failed recently");
        assertThat(launcher.launchCount()).as("no login within the pause").isEqualTo(1);
        assertThat(guard.confirmed()).isFalse();
        assertTempRootEmpty();
    }

    @Test
    void unavailableCredentialsLaunchNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0);
        EffectiveNodeConfig server = http().build();
        NodeSecrets secrets = TestSecrets.credentialStore(List.of(server), Map.of(), scrubber)
            .get(NODE);

        ToolError error = failure(adapter(server, () -> secrets.current(secrets.timeout())),
            V81);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void incompleteCredentialsLaunchNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0);
        NodeCredentials userOnly = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")), Optional.empty(), Optional.empty(),
            Optional.empty());

        ToolError error = failure(adapter(http().build(), () -> userOnly), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.message()).isEqualTo("No INUBIT credentials are configured for int/a,"
            + " so the queue-entry helper cannot log in");
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aMissingClientIsUnavailableAndLaunchesNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0);
        EffectiveNodeConfig server = http().cliHome(root.resolve("no-client")).build();

        ToolError error = failure(adapter(server), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void anUnsupportedVersionLaunchesNothing() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0);

        ToolError error = failure(adapter(http().build()),
            InubitVersion.parse("9.1.0").orElseThrow());

        assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_VERSION);
        assertThat(launcher.launchCount()).isZero();
        assertTempRootEmpty();
    }

    @Test
    void aShutdownDuringTheRunStopsTheHelperAndRemovesTheDirectory() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            write(decode(request).get("dataOut"), DATA);
            Thread.ofVirtual().start(resources::close);
            return new Answer("", 0);
        }).hanging();

        ToolError error = failure(adapter(http().build()), V81);

        assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL);
        assertThat(error.message()).contains("shutting down");
        assertTempRootEmpty();
    }

    @Test
    void anInterruptedCallKillsTheHelperAndRemovesTheDirectory() throws Exception {
        launcher = FakeProcessLauncher.of("", "", 0).hanging();
        CliQueueEntryAdapter adapter = adapter(http().build());
        Thread caller = Thread.currentThread();
        Thread.ofVirtual().start(() -> {
            while (launcher.launchCount() == 0) {
                sleep(Duration.ofMillis(10));
            }
            caller.interrupt();
        });

        ToolError error = failure(adapter, V81);
        boolean interrupted = Thread.interrupted();

        assertThat(error.code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(error.message()).contains("was cancelled");
        assertThat(interrupted).as("the interrupt is kept").isTrue();
        assertTempRootEmpty();
    }

    @Test
    void aRefForAnotherNodeOrNoPartIsRefused() {
        launcher = FakeProcessLauncher.of("", "", 0);
        CliQueueEntryAdapter adapter = adapter(http().build());

        assertThatThrownBy(() -> adapter.read(new QueueEntryRef(NodeId.parse("int/b"), WORKFLOW,
            FILE, 1), V81, BOTH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.read(REF, V81, Set.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(launcher.launchCount()).isZero();
    }

    // --- the log (D-8) ----------------------------------------------------------------------

    @Test
    void theDebugLogNamesVariantClientAndJdkOnly() throws IOException {
        launcher = FakeProcessLauncher.of("", "", 0).responding(request -> {
            Map<String, String> fields = decode(request);
            write(fields.get("dataOut"), DATA);
            write(fields.get("variablesOut"), WFSP.getBytes(StandardCharsets.UTF_8));
            return success(fields, "SOAP_81", DATA.length, WFSP.length());
        });

        adapter(http().build()).read(REF, V81, BOTH);

        List<ILoggingEvent> events = appender.list.stream()
            .filter(event -> event.getLoggerName().equals(CliQueueEntryAdapter.class.getName()))
            .toList();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("int/a: queue-entry helper SOAP_81,"
                + " client " + home + ", JDK " + jdk);
        });
        String all = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()
            .toString();
        assertThat(all).doesNotContain(FILE, "Order-0001", USER);
    }

    // --- helpers ----------------------------------------------------------------------------

    private CliQueueEntryAdapter adapter(EffectiveNodeConfig server) {
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        return adapter(server, () -> credentials);
    }

    private CliQueueEntryAdapter adapter(EffectiveNodeConfig server,
        Supplier<NodeCredentials> credentials) {
        guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        HelperInstallation.Host host = Platform.windows()
            ? new HelperInstallation.Host(true, PARENT,
                new FixedSystem(new CliSystem.Read(TestCliRunners.SYSTEM_DIRECTORY),
                    Charset.forName("IBM850")),
                ProcessContainment.Bound::new, resources, tempRoot)
            : new HelperInstallation.Host(false, PARENT,
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, tempRoot);
        return new CliQueueEntryAdapter(server, credentials, guard, runner, host,
            new ProcessRun(launcher, resources), limiter, scrubber);
    }

    private TestNodeConfig http() {
        return TestNodeConfig.node().id("int/a").baseUrl("http://inubit.example.test:8000")
            .allowInsecureHttp(true).cliHome(home).cliJavaHome(jdk);
    }

    private EffectiveNodeConfig https(int port, String pin) {
        return TestNodeConfig.node().id("int/a").baseUrl("https://127.0.0.1:" + port)
            .trustStore(CERTS.trustStore()).pin(pin).cliHome(home).cliJavaHome(jdk).build();
    }

    /** The error of a read of both parts; it carries the node and leaks nothing. */
    private static ToolError failure(CliQueueEntryAdapter adapter, InubitVersion version) {
        ToolErrorException thrown = catchThrowableOfType(ToolErrorException.class,
            () -> adapter.read(REF, version, BOTH));
        assertThat(thrown).as("the read fails").isNotNull();
        ToolError error = thrown.error();
        assertThat(error.node()).contains(NODE);
        TestSecrets.assertNoLeak(error.toString());
        return error;
    }

    private void assertTempRootEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        }
    }

    private static void assertLaunchedWithoutSecrets(LaunchSpec spec) {
        String argv = String.join(" ", spec.command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(spec.environment().toString());
        assertThat(argv).doesNotContain(USER, FILE, WORKFLOW);
        assertThat(spec.command().get(spec.command().size() - 1))
            .isEqualTo(HelperInstallation.MAIN_CLASS);
    }

    /** Replays a recorded or reconstructed run with the nonce of the request. */
    private static FakeProcessLauncher replaying(String fixture) {
        String frames = fixtureText(fixture + ".frames");
        int exit = Integer.parseInt(fixtureText(fixture + ".exit").strip());
        return FakeProcessLauncher.of("", "", 0).responding(request -> new Answer(
            frames.replace(FIXTURE_NONCE, decode(request).get("nonce")), exit));
    }

    private static String fixtureText(String name) {
        try (InputStream in = CliQueueEntryAdapterTest.class.getResourceAsStream(
            "/fixtures/queue-entry/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Answer success(Map<String, String> fields, String variant, long dataBytes,
        long variablesBytes) {
        List<String> frames = new ArrayList<>(List.of("start " + variant, "login ok"));
        if (!fields.get("dataOut").isEmpty()) {
            frames.add("part data ok " + dataBytes);
        }
        frames.add("part variables ok " + variablesBytes);
        frames.add("end");
        return answer(0, fields, frames.toArray(String[]::new));
    }

    /** Frames of space-separated tokens; the tokens after the fourth are joined, then encoded. */
    private static Answer answer(int exit, Map<String, String> fields, String... frames) {
        StringBuilder out = new StringBuilder("noise before the frames\n");
        for (String frame : frames) {
            out.append(frame(fields.get("nonce"), frame));
        }
        return new Answer(out.toString(), exit);
    }

    private static String frame(String nonce, String frame) {
        String[] tokens = frame.split(" ", 5);
        StringBuilder line = new StringBuilder("#QE1:").append(nonce);
        for (String token : tokens) {
            line.append(' ').append(token.isEmpty() ? "-"
                : URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return line.append('\n').toString();
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

    private static void write(String file, byte[] content) {
        try {
            Files.write(Path.of(file), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
