package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliConcurrencyLimiter;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher.Answer;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperInstallation;
import de.dadecker.inubit.mcp.adapter.rest.v81.QueueEntryRowFixtures;
import de.dadecker.inubit.mcp.application.NodeVersions;
import de.dadecker.inubit.mcp.application.QueueEntryService;
import de.dadecker.inubit.mcp.application.TargetResolver;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.config.SourcedValue;
import de.dadecker.inubit.mcp.domain.model.BinarySignature;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Data;
import de.dadecker.inubit.mcp.domain.model.QueueEntryResult.Variable;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.infra.ResultJson;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeVersionDetection;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T026 (feature 020, research D-18, D-19, FR-009…FR-012, SC-003): the INUBIT 8.1 contract of
 * {@code read_queue_entry} over the content cases of {@code fixtures/queue-entry/v8_1/}. Each case
 * runs the real {@link CliQueueEntryAdapter} and {@link QueueEntryService}: the lookup answers the
 * case's {@code <case>.rows.json} through the real queueLog row parser, a fake launcher reads the
 * helper request from stdin, copies {@code <case>.data}/{@code <case>.wfsp} where the request says
 * and replays {@code <case>.frames}/{@code <case>.exit} with the request's nonce. No process, no
 * network. Every result, request, argument array and log line is checked for leaks.
 */
@Timeout(60)
class QueueEntryV81ContractTest {

    /**
     * The content cases; exactly the {@code v8_1/*.rows.json} on disk: the neutralized 8.1.17
     * recording {@code error_entry} (tasks.md T027, T035) and the synthetic cases (T026).
     */
    static final List<String> CASES = List.of("error_entry", "binary_zip", "utf16_text",
        "latin1_text", "secrets_everywhere", "many_variables", "long_values",
        "missing_variables", "cutoff_tag", "too_large");

    /** The variables of the recorded {@code error_entry}, in the order INUBIT stored them. */
    private static final List<String> ERROR_ENTRY_VARIABLES = List.of("ISFileSize",
        "ISCurrentTime", "ISWorkflowName", "ISModuleName", "ISModuleId", "ISProcessId",
        "ISUserName", "ISServerName", "ISNodeName", "ISGlobalProcessId",
        "ISOverwriteSubWorkflowPriority", "_hidden.ISCreateModuleRunId", "connection",
        "content-type", "as2-version", "as2-from", "as2-to", "message-id",
        "disposition-notification-to", "disposition-notification-options", "mime-version",
        "cache-control", "pragma", "user-agent", "host", "accept", "content-length",
        "ASMessageID", "ASMessageTo", "ASMessageFrom", "ASMessageDate", "ASMessageMDNOptions",
        "ASMessageMICALG", "EncryptionAlgorithm", "KeyEncryptionAlgorithm",
        "KeyEncryptionHashAlgorithm", "ASMessageMIC", "HashingAlgorithm", "SignatureAlgorithm",
        "ASMessageError", "isSigned", "isEncrypted", "ASMessageContentType",
        "ASMessageContentTransferEncoding", "var.inboundMessage", "var.testMode.processId",
        "var.testMode.file", "var.testMode", "demux.totalIterations", "demux.iterator");

    private static final Path DIRECTORY = Path.of("src/test/resources/fixtures/queue-entry/v8_1");
    private static final String RESOURCES = "/fixtures/queue-entry/v8_1/";
    private static final String ROWS = ".rows.json";
    private static final NodeId NODE = NodeId.parse("int/a");
    private static final String FIXTURE_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String USER = "jdoe";
    private static final String NOT_FOUND = "INUBIT did not find the content of this entry";
    /** Every synthetic secret of the cases ends like this (or is the Basic credential). */
    private static final List<String> SECRET_MARKERS = List.of("secret-0001", "secret 0001",
        "YWNtZTpiYXNpYy1zZWNyZXQtMDAwMQ");
    private static final Map<String, String> PARENT = Map.of(
        "PATH", "/usr/bin:/bin",
        "HOME", "/home/test",
        "INUBIT_PASSWORD", TestSecrets.CONFIGURED_PASSWORD);

    @TempDir
    Path root;

    private Path tempRoot;
    private Path home;
    private Path jdk;
    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private final SecretScrubber scrubber = new SecretScrubber();
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("de.dadecker.inubit");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

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
            String message = event.getFormattedMessage();
            assertNoSecret(message);
            assertThat(message).as("FR-018: no content in the log")
                .doesNotContain("Order-0001", "Grüße", "user-0001");
        }
    }

    @Test
    void theCasesAreExactlyTheContentCasesOnDisk() throws IOException {
        TreeSet<String> onDisk;
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            onDisk = files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(ROWS))
                .map(name -> name.substring(0, name.length() - ROWS.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }

        assertThat(onDisk).containsExactlyInAnyOrderElementsOf(CASES);
    }

    @Test
    void theRecordedErrorEntryHasItsMetadataTextAndAllVariablesUnmasked() {
        Run run = run("error_entry");

        byte[] bytes = fixture("error_entry.data");
        QueueEntryResult.Entry entry = run.result().entry();
        assertThat(entry.processId()).isEqualTo("100000027");
        assertThat(entry.globalProcessId()).contains("100000027");
        assertThat(entry.module()).contains("Module-0002(10001002)");
        assertThat(entry.moduleType()).contains("Throw");
        assertThat(entry.rawState()).isEqualTo("Error");
        assertThat(entry.since()).isEqualTo(Instant.ofEpochMilli(1_790_861_373_734L));
        assertThat(entry.sizeBytes()).isEqualTo(bytes.length);
        assertThat(entry.errorTexts()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "userDefined1", "AS2-TEST-ERROR",
            "userDefined2", "Wrong data type for Order-0001 receiver"));
        String stored = new String(bytes, StandardCharsets.UTF_8);
        Data data = run.result().data();
        assertThat(data.kind()).contains("text");
        assertThat(data.charset()).contains("UTF-8");
        assertThat(data.sizeBytes()).contains((long) bytes.length);
        assertThat(data.totalChars()).contains(stored.length());
        assertThat(data.truncated()).contains(false);
        assertThat(data.text()).as("no secret-named value in the recording: nothing masked")
            .contains(stored);
        assertThat(stored).contains("<faultstring>Validation-Error(s) in SOAP-Request-Message"
            + " of Order-0001</faultstring>", "Cannot find the declaration of element 'Error'.");
        QueueEntryResult.Variables variables = run.result().variables();
        assertThat(variables.totalCount()).contains(ERROR_ENTRY_VARIABLES.size());
        assertThat(variables.returnedCount()).contains(ERROR_ENTRY_VARIABLES.size());
        assertThat(variables.truncated()).contains(false);
        List<Variable> items = items(run);
        assertThat(items).extracting(Variable::name)
            .containsExactlyElementsOf(ERROR_ENTRY_VARIABLES);
        assertThat(items.subList(0, 12)).allSatisfy(item -> assertThat(item.system())
            .as(item.name()).isTrue());
        assertThat(items.subList(12, items.size())).allSatisfy(item -> assertThat(item.system())
            .as(item.name()).isFalse());
        assertThat(items).allSatisfy(item -> assertThat(item.masked()).as(item.name()).isFalse());
        assertThat(run.json()).doesNotContain("${secret:");
        Map<String, String> types = items.stream().filter(item -> item.type().isPresent())
            .collect(Collectors.toMap(Variable::name, item -> item.type().orElseThrow()));
        assertThat(types).containsExactlyInAnyOrderEntriesOf(Map.of(
            "ISCurrentTime", "Long", "ISOverwriteSubWorkflowPriority", "Boolean",
            "_hidden.ISCreateModuleRunId", "Boolean", "isSigned", "Boolean",
            "isEncrypted", "Boolean", "var.inboundMessage", "XmlDocument",
            "demux.totalIterations", "Integer", "demux.iterator", "Integer"));
        Map<String, Variable> byName = items.stream()
            .collect(Collectors.toMap(Variable::name, item -> item));
        assertThat(byName.get("ISFileSize").value()).contains(String.valueOf(bytes.length));
        assertThat(byName.get("ISWorkflowName").value()).contains("Workflow-0001");
        assertThat(byName.get("ISModuleName").value()).contains("Module-0001");
        assertThat(byName.get("ISProcessId").value()).contains("100000027");
        assertThat(byName.get("ISServerName").value()).contains("inubit-dev-1.example.test");
        assertThat(byName.get("message-id").value())
            .contains("<100000027.10001.1790861373334@ip-192-0-2-86>");
        assertThat(byName.get("var.inboundMessage").value()).contains(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Error>authentication-failed"
                + "</Error><ErrorMessage>authentication-failed</ErrorMessage></Error>");
        Variable empty = byName.get("var.testMode.processId");
        assertThat(empty.kind()).isEqualTo("empty");
        assertThat(empty.value()).isEmpty();
        assertThat(empty.sizeBytes()).contains(0L);
    }

    @Test
    void zipDataAndBinaryVariablesAreSizeAndKindOnly() {
        Run run = run("binary_zip");

        Data data = run.result().data();
        assertThat(data.available()).isTrue();
        assertThat(data.kind()).contains("binary");
        assertThat(data.detected()).contains(BinarySignature.ZIP);
        assertThat(data.sizeBytes()).contains((long) fixture("binary_zip.data").length);
        assertThat(data.hint()).contains(Data.BINARY_HINT);
        assertThat(data.text()).isEmpty();
        assertThat(data.charset()).isEmpty();
        List<Variable> items = items(run);
        assertThat(items).extracting(Variable::name)
            .containsExactly("orderId", "attachment", "logo");
        assertThat(items.get(0).value()).contains("Order-0001");
        assertBinary(items.get(1), BinarySignature.PDF,
            "%PDF-1.4 synthetic attachment of Order-0001");
        assertBinary(items.get(2), BinarySignature.GIF, "GIF89a synthetic logo");
        assertThat(run.json()).doesNotContain("<order", "synthetic attachment");
    }

    @Test
    void utf16TextIsBinaryOnPurpose() {
        Run run = run("utf16_text");

        Data data = run.result().data();
        assertThat(data.kind()).contains("binary");
        assertThat(data.detected()).isEmpty();
        assertThat(data.sizeBytes()).contains((long) fixture("utf16_text.data").length);
        assertThat(data.hint()).contains(Data.BINARY_HINT);
        assertThat(data.text()).isEmpty();
        assertThat(items(run)).extracting(Variable::name).containsExactly("orderId");
    }

    @Test
    void latin1TextIsDecodedAndItsCharsetNamed() {
        Run run = run("latin1_text");

        byte[] bytes = fixture("latin1_text.data");
        Data data = run.result().data();
        assertThat(data.kind()).contains("text");
        assertThat(data.charset()).contains("ISO-8859-1");
        assertThat(data.sizeBytes()).contains((long) bytes.length);
        assertThat(data.text()).contains(new String(bytes, StandardCharsets.ISO_8859_1));
        assertThat(data.text().orElseThrow()).contains("Grüße an die Straße 1");
        assertThat(data.truncated()).contains(false);
        assertThat(items(run)).extracting(Variable::name, item -> item.value().orElseThrow())
            .containsExactly(tuple("orderId", "Order-0001"),
                tuple("note", "Grüße"));
    }

    @Test
    void everyMaskingRuleHoldsInDataVariablesAndRow() {
        Run run = run("secrets_everywhere");

        String text = run.result().data().text().orElseThrow();
        assertThat(run.result().data().charset()).contains("UTF-8");
        assertThat(text).contains(
            "POST /orders HTTP/1.1\r\nHost: shop.example.test\r\n",
            "Authorization: ${secret:authorization}\r\n",
            "Proxy-Authorization: ${secret:authorization}\r\n",
            "X-Api-Key: ${secret:data.X-Api-Key}\r\n",
            "Cookie: ${secret:data.Cookie}\r\n",
            "Content-Type: text/xml; charset=UTF-8\r\n",
            "<wsse:Username>user-0001</wsse:Username>",
            "<wsse:Password Type=\"PasswordText\">${secret:data.Password}</wsse:Password>",
            "<login user=\"user-0001\" password=\"${secret:data.password}\"/>",
            "<clientSecret>${secret:data.clientSecret}</clientSecret>",
            "<apiToken/>",
            "<note>Configured password: " + SecretScrubber.MASK + "</note>",
            "hook?token=${secret:data.token}&amp;order=Order-0001",
            "db_password=\"${secret:data.db_password}\"; mode=test",
            "\"apiKey\": \"${secret:data.apiKey}\"",
            "\"credentials\": \"${secret:data.credentials}\",",
            "\"auth\": \"Bearer ${secret:authorization}\"");
        List<Variable> items = items(run);
        assertThat(items).extracting(Variable::name).containsExactly("ISWorkflowName",
            "orderId", "apiToken", "password:old", "requestBody", "httpHeaders", "comment");
        assertThat(items.get(0).system()).isTrue();
        assertThat(items.get(2).value()).contains("${secret:variables.apiToken}");
        assertThat(items.get(2).masked()).isTrue();
        assertThat(items.get(3).value()).contains("${secret:variables.password_old}");
        assertThat(items.get(3).masked()).isTrue();
        assertThat(items.get(4).value()).contains("<auth><user>user-0001</user><password>"
            + "${secret:data.password}</password></auth>");
        assertThat(items.get(4).masked()).isFalse();
        assertThat(items.get(5).value())
            .contains("Authorization: ${secret:authorization}\nAccept: text/xml");
        assertThat(items.get(6).value()).contains(SecretScrubber.MASK + " was rejected");
        assertThat(run.result().entry().errorTexts()).containsEntry("userDefined1",
            "Login of user-0001 failed with " + SecretScrubber.MASK);
        String raw = new String(fixture("secrets_everywhere.data"), StandardCharsets.UTF_8);
        assertThat(raw).as("the fixture holds what must not come back")
            .contains(TestSecrets.CONFIGURED_PASSWORD, "element-secret-0001");
    }

    @Test
    void twoHundredOfTwoHundredFiftyVariablesAreReturned() {
        Run run = run("many_variables");

        QueueEntryResult.Variables variables = run.result().variables();
        assertThat(variables.totalCount()).contains(250);
        assertThat(variables.returnedCount()).contains(200);
        assertThat(variables.truncated()).contains(true);
        List<Variable> items = items(run);
        assertThat(items).hasSize(200);
        assertThat(items.subList(0, 12)).allSatisfy(item -> assertThat(item.system())
            .as(item.name()).isTrue());
        assertThat(items.subList(12, 200)).allSatisfy(item -> assertThat(item.system())
            .as(item.name()).isFalse());
        assertThat(items.get(0).name()).isEqualTo("ISFileSize");
        assertThat(items.get(11).name()).isEqualTo("_hidden.retryCount");
        assertThat(items.get(11).type()).contains("Integer");
        assertThat(items.get(199).name()).isEqualTo("var188");
        assertThat(items.get(199).value()).contains("value 188 of Order-0001");
    }

    @Test
    void longTextAndValuesAreCutWithoutSplittingASurrogatePair() {
        Run run = run("long_values");

        String full = new String(fixture("long_values.data"), StandardCharsets.UTF_8);
        Data data = run.result().data();
        assertThat(data.sizeBytes()).contains((long) fixture("long_values.data").length);
        assertThat(data.totalChars()).contains(full.length());
        assertThat(data.returnedChars()).contains(19_999);
        assertThat(data.truncated()).contains(true);
        assertThat(data.text()).contains(full.substring(0, 19_999));
        List<Variable> items = items(run);
        assertThat(run.result().variables().totalCount()).contains(26);
        assertThat(run.result().variables().truncated()).contains(false);
        Variable longName = items.get(0);
        assertThat(longName.name()).hasSize(200).startsWith("longNameNNN");
        assertThat(longName.nameTruncated()).contains(true);
        assertThat(longName.value()).contains("short");
        assertThat(longName.truncated()).isEmpty();
        assertCut(items.get(1), 1_999, 2_501);
        assertThat(items.get(1).value().orElseThrow()).isEqualTo("a".repeat(1_999));
        IntStream.range(2, 20).forEach(i -> assertCut(items.get(i), 2_000, 2_500));
        assertCut(items.get(20), 1_996, 2_500);
        IntStream.range(21, 26).forEach(i -> assertCut(items.get(i), 0, 2_500));
        assertThat(items.stream().mapToInt(item -> item.value().orElseThrow().length()).sum())
            .isEqualTo(40_000);
    }

    @Test
    void missingVariablesLeaveTheDataAndAReason() {
        Run run = run("missing_variables");

        Data data = run.result().data();
        assertThat(data.available()).isTrue();
        assertThat(data.text()).contains(new String(fixture("missing_variables.data"),
            StandardCharsets.UTF_8));
        QueueEntryResult.Variables variables = run.result().variables();
        assertThat(variables.available()).isFalse();
        assertThat(variables.reason()).contains(NOT_FOUND);
        assertThat(variables.items()).isEmpty();
        assertThat(run.rowReads()).as("the rows are read again after not found").isEqualTo(2);
    }

    @Test
    void aCutOffSecretTagIsMaskedAndACutNeverRevealsIt() {
        Run run = run("cutoff_tag");

        String text = run.result().data().text().orElseThrow();
        assertThat(text).contains("<order id=\"Order-0001\">\n<customer>acme</customer>\n")
            .endsWith("<password>${secret:data.password}");
        assertThat(items(run).get(1).value())
            .contains("<request><apiKey>${secret:data.apiKey}</apiK");
        int cut = text.indexOf("${secret:data.password}") + 5;

        Run shortened = run("cutoff_tag", OptionalInt.of(cut));

        Data data = shortened.result().data();
        assertThat(data.text()).contains(text.substring(0, cut));
        assertThat(data.truncated()).contains(true);
        assertThat(data.totalChars()).contains(text.length());
    }

    @Test
    void tooLargeDataIsNotRequestedAndTheVariablesAreRead() {
        Run run = run("too_large");

        assertThat(run.requests()).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("parts", "variables").containsEntry("dataOut", "");
        });
        Data data = run.result().data();
        assertThat(data.available()).isFalse();
        assertThat(data.reason()).contains("too large (10485761 bytes; limit 10 MiB)");
        assertThat(run.result().entry().sizeBytes()).isEqualTo(10_485_761L);
        assertThat(items(run)).extracting(Variable::name, Variable::system)
            .containsExactly(tuple("orderId", false),
                tuple("ISFileSize", true));
        assertThat(run.rowReads()).isEqualTo(1);
    }

    // --- the run ------------------------------------------------------------------------------

    /** The result of one case, its JSON, the helper requests and the number of row reads. */
    private record Run(QueueEntryResult result, String json, List<Map<String, String>> requests,
        int rowReads) {
    }

    private Run run(String name) {
        return run(name, OptionalInt.empty());
    }

    /**
     * Reads the case through service and adapter and checks what every case shares: the entry
     * of the row, the warning, the helper request, no secret in the argument array, the
     * environment or the result, nothing left in the temporary directory.
     */
    private Run run(String name, OptionalInt maxChars) {
        String frames = new String(fixture(name + ".frames"), StandardCharsets.UTF_8);
        int exit = Integer.parseInt(new String(fixture(name + ".exit"),
            StandardCharsets.UTF_8).strip());
        List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0).responding(stdin -> {
            Map<String, String> fields = decode(stdin);
            requests.add(fields);
            if (!fields.get("dataOut").isEmpty()) {
                optionalFixture(name + ".data").ifPresent(data -> write(fields.get("dataOut"),
                    data));
            }
            optionalFixture(name + ".wfsp").ifPresent(wfsp -> write(fields.get("variablesOut"),
                wfsp));
            return new Answer(frames.replace(FIXTURE_NONCE, fields.get("nonce")), exit);
        });
        byte[] answer = fixture(name + ROWS);
        QueueEntryRow row = QueueEntryRowFixtures.queueEntries(NODE, answer).rows().get(0);
        AtomicInteger rowReads = new AtomicInteger();
        NodeVersions versions = new NodeVersions(Clock.systemUTC(), node -> VersionSetting.AUTO,
            node -> Duration.ofMinutes(10));
        FakeVersionDetection detection = new FakeVersionDetection(versions).answer(NODE, "8.1.17");
        CliQueueEntryAdapter adapter = adapter(launcher);
        QueueEntryService service = new QueueEntryService(new TargetResolver(List.of(NODE)),
            node -> true, FakeVersionDetection.gateWithoutClients(versions, detection,
                CapabilityMatrix.production()),
            node -> rows(answer, rowReads), node -> adapter, scrubber::scrub);

        QueueEntryResult result = service.read(NODE.value(), row.processId(), Optional.empty(),
            maxChars);

        String json = ResultJson.mapper().writeValueAsString(result);
        assertNoSecret(json);
        assertThat(result.node()).isEqualTo(NODE);
        assertThat(result.entry().processId()).isEqualTo(row.processId());
        assertThat(result.entry().fileName()).isEqualTo(row.fileName());
        assertThat(result.entry().workflow()).isEqualTo("Workflow-0001");
        assertThat(result.entry().state()).isEqualTo(ProcessState.ERROR);
        assertThat(result.warnings()).first().isEqualTo(QueueEntryResult.WARNING);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request).containsEntry("variant", "SOAP_81")
                .containsEntry("objectType", "queue_manager@@@Workflow-0001@@@error")
                .containsEntry("fileName", row.fileName())
                .containsEntry("user", USER)
                .containsEntry("password", TestSecrets.CONFIGURED_PASSWORD);
            assertThat(request.get("nonce")).matches("[0-9a-f]{32}");
        });
        String argv = String.join(" ", launcher.last().spec().command());
        TestSecrets.assertNoLeak(argv);
        TestSecrets.assertNoLeak(launcher.last().spec().environment().toString());
        assertThat(argv).doesNotContain(USER, row.fileName());
        assertTempRootEmpty();
        return new Run(result, json, requests, rowReads.get());
    }

    /** {@link ProcessQueryPort#queueEntries} answering the case's queueLog rows. */
    private static ProcessQueryPort rows(byte[] answer, AtomicInteger reads) {
        return new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration hangingThreshold) {
                throw new AssertionError("only queueEntries is expected");
            }

            @Override
            public QueueEntries queueEntries(String processId) {
                reads.incrementAndGet();
                return QueueEntryRowFixtures.queueEntries(NODE, answer);
            }
        };
    }

    private CliQueueEntryAdapter adapter(FakeProcessLauncher launcher) {
        EffectiveNodeConfig server = TestNodeConfig.node().id(NODE.value())
            .baseUrl("http://inubit.example.test:8000").allowInsecureHttp(true).cliHome(home)
            .cliJavaHome(jdk).build();
        NodeCredentials credentials = new NodeCredentials(NODE,
            Optional.of(new SourcedValue<>(USER, "test")),
            Optional.of(new SourcedValue<>(scrubber.register(TestSecrets.CONFIGURED_PASSWORD),
                "test")),
            Optional.empty(), Optional.empty());
        CredentialGuard guard = new CredentialGuard(server.secretLabels(), Clock.systemUTC());
        CliRunner runner = TestCliRunners.forHost(launcher, PARENT, resources);
        HelperInstallation.Host host = Platform.windows()
            ? new HelperInstallation.Host(true, PARENT,
                new FixedSystem(new CliSystem.Read(TestCliRunners.SYSTEM_DIRECTORY),
                    Charset.forName("IBM850")),
                ProcessContainment.Bound::new, resources, tempRoot)
            : new HelperInstallation.Host(false, PARENT,
                new FixedSystem(new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8),
                ProcessContainment.Bound::new, resources, tempRoot);
        return new CliQueueEntryAdapter(server, () -> credentials, guard, runner, host,
            new ProcessRun(launcher, resources),
            new CliConcurrencyLimiter(CapabilityMatrix.production()), scrubber);
    }

    // --- assertions ---------------------------------------------------------------------------

    private static void assertNoSecret(String text) {
        TestSecrets.assertNoLeak(text);
        assertThat(text).doesNotContain(SECRET_MARKERS);
    }

    private static List<Variable> items(Run run) {
        assertThat(run.result().variables().available()).as("variables read").isTrue();
        return run.result().variables().items().orElseThrow();
    }

    private static void assertBinary(Variable item, BinarySignature signature, String value) {
        assertThat(item.kind()).as(item.name()).isEqualTo("binary");
        assertThat(item.detected()).as(item.name()).contains(signature);
        assertThat(item.sizeBytes()).as(item.name())
            .contains((long) value.getBytes(StandardCharsets.UTF_8).length);
        assertThat(item.value()).as(item.name()).isEmpty();
        assertThat(item.masked()).as(item.name()).isFalse();
    }

    private static void assertCut(Variable item, int shown, int total) {
        assertThat(item.value().orElseThrow()).as(item.name()).hasSize(shown);
        assertThat(item.truncated()).as(item.name()).contains(true);
        assertThat(item.totalChars()).as(item.name()).contains(total);
    }

    private void assertTempRootEmpty() {
        try (Stream<Path> entries = Files.list(tempRoot)) {
            assertThat(entries).as("nothing left below the temporary directory").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- fixtures and request -----------------------------------------------------------------

    private static byte[] fixture(String name) {
        return optionalFixture(name).orElseThrow(() -> new IllegalArgumentException(
            "No fixture " + RESOURCES + name));
    }

    private static Optional<byte[]> optionalFixture(String name) {
        try (InputStream in = QueueEntryV81ContractTest.class.getResourceAsStream(
            RESOURCES + name)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
