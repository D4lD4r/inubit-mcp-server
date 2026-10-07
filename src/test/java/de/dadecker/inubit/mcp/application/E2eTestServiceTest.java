package de.dadecker.inubit.mcp.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.adapter.soap.SoapE2eClient;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePreview;
import de.dadecker.inubit.mcp.domain.model.E2eRun;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LogEntry;
import de.dadecker.inubit.mcp.domain.model.LogQuery;
import de.dadecker.inubit.mcp.domain.model.LogType;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ProcessInstance;
import de.dadecker.inubit.mcp.domain.model.ProcessQuery;
import de.dadecker.inubit.mcp.domain.model.ProcessRows;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.Severity;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePolicy;
import de.dadecker.inubit.mcp.domain.port.LogPort;
import de.dadecker.inubit.mcp.domain.port.ProcessQueryPort;
import de.dadecker.inubit.mcp.testing.MutableClock;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * T023 (feature 004, US5, FR-022 – FR-024, research D-17, D-25 H8, M9): {@code run_e2e_test}
 * with the real SOAP client against WireMock and fake log and process ports — the policy
 * (FREE, CONFIRM, FORBIDDEN), the confined envelope path and endpoint path, the refusal of a
 * real {@code wsse:Password}, the response file and the optional excerpt, the correlation by
 * test id or by workflow and time window (uncertain), diagnostics kept on timeout, old
 * response files removed, and the audit with the payload hash only.
 */
@Timeout(60)
class E2eTestServiceTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final String PATH = "/ibis/ws/Service-01";
    private static final String ENVELOPE = "<soapenv:Envelope xmlns:soapenv="
        + "\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><order id=\"42\"/>"
        + "</soapenv:Body></soapenv:Envelope>\n";
    private static WireMockServer wireMock;

    @TempDir
    Path root;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));
    private final List<AuditRecord> audit = new CopyOnWriteArrayList<>();
    private final List<LogQuery> logQueries = new CopyOnWriteArrayList<>();
    private final List<ProcessQuery> processQueries = new CopyOnWriteArrayList<>();
    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final List<ProcessInstance> instances = new CopyOnWriteArrayList<>();
    private E2ePolicy policy = E2ePolicy.FREE;
    private boolean logsUnavailable;
    private SoapE2eClient client;

    @BeforeAll
    static void start() {
        wireMock = TestCertificates.httpsWireMock(TestCertificates.get().localhostKeyStore());
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        wireMock.resetAll();
        Files.createDirectories(root.resolve("samples"));
        Files.writeString(root.resolve("samples/order.xml"), ENVELOPE);
        client = new SoapE2eClient(TestNodeConfig.node().id(DEV.value())
            .baseUrl("https://localhost:" + wireMock.httpsPort())
            .trustStore(TestCertificates.get().trustStore()).build(),
            URI.create("https://localhost:" + wireMock.httpsPort()), Optional.empty(),
            Optional.empty());
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    /** Feature 005 (T027): the node is no development stage, its group receives deployments. */
    private boolean deploymentTarget;

    private E2eTestService service() {
        DevelopmentPolicy dev = new DevelopmentPolicy(DEV, false, !deploymentTarget,
            WritePolicy.Confirmation.SERVER, Duration.ofMinutes(5), policy,
            Optional.of(URI.create("https://localhost:" + wireMock.httpsPort())));
        LogPort logs = new LogPort() {
            @Override
            public void validate(LogQuery query) {
            }

            @Override
            public Page<LogEntry> query(LogQuery query) {
                if (logsUnavailable) {
                    throw new ToolErrorException(ToolError.of(ErrorCode.UNREACHABLE, "down",
                        "c", "s").withNode(DEV));
                }
                logQueries.add(query);
                List<LogEntry> items = logged.stream().filter(entry -> entry.logType()
                    == query.logType()).filter(entry -> query.text().map(text -> entry.message()
                        .orElse("").contains(text)).orElse(true))
                    .filter(entry -> query.workflow().map(w -> entry.workflow().orElse("")
                        .equals(w)).orElse(true)).limit(query.limit()).toList();
                return new Page<>(items, 0, query.limit(), OptionalLong.of(items.size()), false,
                    false, OptionalInt.empty());
            }
        };
        ProcessQueryPort processes = new ProcessQueryPort() {
            @Override
            public void validate(ProcessQuery query) {
            }

            @Override
            public Page<ProcessInstance> find(ProcessQuery query) {
                processQueries.add(query);
                List<ProcessInstance> items = instances.stream().filter(instance -> query
                    .workflow().map(w -> instance.workflow().orElse("").equals(w)).orElse(true))
                    .limit(query.limit()).toList();
                return new Page<>(items, 0, query.limit(), OptionalLong.of(items.size()), false,
                    false, OptionalInt.empty());
            }

            @Override
            public ProcessRows findByProcessId(String processId, Instant now,
                Duration threshold) {
                return new ProcessRows(instances.stream().filter(instance -> instance
                    .processId().equals(processId)).toList(), 1);
            }
        };
        return new E2eTestService(new E2eTestService.Dependencies(root,
            root.resolve("backups"), "acme",
            new DevelopmentGuard(new TargetResolver(List.of(DEV)), Map.of(DEV, dev)::get,
                node -> { }, group -> deploymentTarget ? Optional.of(
                    de.dadecker.inubit.mcp.domain.model.DeployMode.EXECUTE) : Optional.empty(),
                Duration.ofMinutes(30)), node -> client, node -> logs, node -> processes,
            node -> Duration.ofMinutes(15),
            node -> new ImportService.Account("jdoe", "inubit-dev-1.example.test"),
            new WriteChallengeRegistry(clock), audit::add, clock, UUID::randomUUID));
    }

    private static E2eTestService.E2eRequest request(String envelope, String path,
        Optional<String> workflow, int timeout, boolean excerpt, Optional<String> code) {
        return new E2eTestService.E2eRequest("dev/node1", envelope, path,
            Optional.of("urn:order"), workflow, timeout, excerpt, code, Optional.empty());
    }

    private static E2eTestService.E2eRequest request() {
        return request("samples/order.xml", PATH, Optional.of("Workflow-0001"), 10, false,
            Optional.empty());
    }

    private static E2eRun completed(E2eTestService.Response response) {
        assertThat(response).isInstanceOf(E2eTestService.Response.Completed.class);
        return ((E2eTestService.Response.Completed) response).run();
    }

    private ToolError refusal(E2eTestService service, E2eTestService.E2eRequest request) {
        ToolErrorException exception = catchThrowableOfType(ToolErrorException.class,
            () -> service.run(request));
        assertThat(exception).as("expected a refusal").isNotNull();
        assertThat(audit).last().satisfies(record ->
            assertThat(record.outcome()).isEqualTo(AuditOutcome.REFUSED));
        return exception.error();
    }

    private static ProcessInstance instance(String processId, String workflow,
        ProcessState state) {
        return new ProcessInstance(DEV, processId, Optional.empty(), state, state.name(),
            Instant.parse("2026-10-07T10:00:01Z"), Duration.ZERO, false,
            Optional.of(workflow), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("jdoe"), Optional.empty());
    }

    private static LogEntry entry(LogType type, Severity severity, String processId,
        String message) {
        return new LogEntry(DEV, type, Optional.of(Instant.parse("2026-10-07T10:00:01Z")),
            severity, Optional.empty(), Optional.of("Workflow-0001"), Optional.empty(),
            Optional.of(processId), Optional.of(message), Map.of());
    }

    @Test
    void theEnvelopeIsSentAndCorrelatedByTheTestId() throws IOException {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));
        logged.add(entry(LogType.SYSTEM_LOG, Severity.ERROR, "4711",
            "Mapping failed (test id placeholder)"));
        E2eTestService service = service();

        E2eRun run = completed(service.run(request()));

        // the fake log port matches the text filter, so the test id must be what was sent
        String testId = run.testId();
        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(binaryEqualTo(
            ENVELOPE.getBytes(StandardCharsets.UTF_8))).withHeader("X-Inubit-Mcp-Test-Id",
            equalTo(testId)));
        assertThat(run.status()).contains(200);
        assertThat(run.timedOut()).isFalse();
        assertThat(run.excerpt()).isEmpty();
        assertThat(run.responseFile()).contains(".tests/e2e/" + run.auditId()
            + ".response.xml");
        assertThat(Files.readString(root.resolve(run.responseFile().orElseThrow())))
            .isEqualTo("<ok/>");
        assertThat(logQueries).isNotEmpty().allSatisfy(query -> {
            assertThat(query.since()).isPresent();
            assertThat(query.until()).isPresent();
        });
        assertThat(logQueries).anySatisfy(query -> assertThat(query.text()).contains(testId));
        assertThat(audit).extracting(AuditRecord::capability).containsOnly("run_e2e_test");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);
        assertThat(audit.get(1).inputs()).containsEntry("path", PATH)
            .containsEntry("envelope", "samples/order.xml").containsKey("payloadSha256")
            .containsEntry("testId", testId);
        assertThat(audit.toString()).doesNotContain("order id");
    }

    @Test
    void aNodeOfAGroupThatReceivesDeploymentsRunsTheTestAsItsE2eTestsAllows() {
        // feature 005 (T027)
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("<ok/>")));
        deploymentTarget = true;

        E2eRun run = completed(service().run(request()));

        assertThat(run.status()).contains(200);
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.EXECUTED);
        policy = E2ePolicy.FORBIDDEN;
        assertThat(refusal(service(), request()).code()).isEqualTo(ErrorCode.E2E_FORBIDDEN);
    }

    @Test
    void foundByTheTestIdTheProcessesAndErrorsAreThoseOfItsProcessIds() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        instances.add(instance("4711", "Workflow-0001", ProcessState.ERROR));
        instances.add(instance("4712", "Workflow-0001", ProcessState.ACTIVE));
        E2eTestService service = service();
        // the log entry names the test id; the service generates it, so log it on send
        wireMock.addMockServiceRequestListener((request, response) -> logged.add(entry(
            LogType.SYSTEM_LOG, Severity.ERROR, "4711", "Header X-Inubit-Mcp-Test-Id="
                + request.getHeader("X-Inubit-Mcp-Test-Id"))));

        E2eRun run = completed(service.run(request()));

        assertThat(run.correlation()).isEqualTo(E2eRun.Correlation.BY_TEST_ID);
        assertThat(run.processes()).extracting(ProcessInstance::processId)
            .containsExactly("4711");
        assertThat(run.errors()).singleElement().satisfies(error ->
            assertThat(error.message().orElseThrow()).contains(run.testId()));
        assertThat(run.logEntries()).hasSize(1);
    }

    @Test
    void withoutAMatchTheWorkflowsInstancesInTheWindowAreUncertain() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        instances.add(instance("4711", "Workflow-0001", ProcessState.ERROR));
        instances.add(instance("4800", "Workflow-0002", ProcessState.ERROR));

        E2eRun run = completed(service().run(request()));

        assertThat(run.correlation()).isEqualTo(E2eRun.Correlation.TIME_WINDOW_UNCERTAIN);
        assertThat(run.processes()).extracting(ProcessInstance::processId)
            .containsExactly("4711");
        assertThat(processQueries).singleElement().satisfies(query -> {
            assertThat(query.workflow()).contains("Workflow-0001");
            assertThat(query.since()).contains(Instant.parse("2026-10-07T09:59:55Z"));
            assertThat(query.until()).contains(Instant.parse("2026-10-07T10:00:30Z"));
        });
    }

    @Test
    void theExcerptIsReturnedOnlyOnRequestAndAtMost2Kb() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withBody("<r>" + "ä".repeat(3000) + "</r>")));

        E2eRun run = completed(service().run(request("samples/order.xml", PATH,
            Optional.empty(), 10, true, Optional.empty())));

        assertThat(run.excerpt()).hasValueSatisfying(excerpt -> {
            assertThat(excerpt.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(
                2048);
            assertThat(excerpt).startsWith("<r>ää");
        });
        assertThat(run.truncated()).isTrue();
        assertThat(run.correlation()).isEqualTo(E2eRun.Correlation.TIME_WINDOW_UNCERTAIN);
        assertThat(run.warnings()).anyMatch(w -> w.contains("workflow"));
    }

    @Test
    void underConfirmThePreviewSendsNothingAndTheCodeBindsThePayload() throws IOException {
        policy = E2ePolicy.CONFIRM;
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        E2eTestService service = service();

        E2ePreview preview = ((E2eTestService.Response.Challenge) service.run(request()))
            .preview();

        assertThat(preview.endpoint()).isEqualTo("https://localhost:" + wireMock.httpsPort()
            + PATH);
        assertThat(preview.payloadBytes()).isEqualTo(ENVELOPE.getBytes(StandardCharsets.UTF_8)
            .length);
        assertThat(preview.soapAction()).contains("urn:order");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        Files.writeString(root.resolve("samples/order.xml"), ENVELOPE.replace("42", "43"));
        ToolError error = refusal(service, request("samples/order.xml", PATH,
            Optional.of("Workflow-0001"), 10, false, Optional.of(preview.confirmationCode())));
        assertThat(error.code()).isEqualTo(ErrorCode.CONFIRMATION_INVALID);
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        E2ePreview again = ((E2eTestService.Response.Challenge) service.run(request()))
            .preview();
        E2eRun run = completed(service.run(request("samples/order.xml", PATH,
            Optional.of("Workflow-0001"), 10, false, Optional.of(again.confirmationCode()))));
        assertThat(run.status()).contains(200);
    }

    @Test
    void forbiddenTestsAreRefusedAndNothingIsSent() {
        policy = E2ePolicy.FORBIDDEN;

        ToolError error = refusal(service(), request());

        assertThat(error.code()).isEqualTo(ErrorCode.E2E_FORBIDDEN);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void envelopePathsOutsideTheWorkspaceAndEndpointPathsThatEscapeAreRefused()
        throws IOException {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), ENVELOPE);
        Files.createDirectories(root.resolve(".meta"));
        Files.writeString(root.resolve(".meta/x.xml"), ENVELOPE);
        Files.createDirectories(root.resolve(".reports"));
        Files.writeString(root.resolve(".reports/x.xml"), ENVELOPE);
        Path outside = Files.createTempFile("e2e-outside", ".xml");
        Files.writeString(outside, ENVELOPE);
        Files.createSymbolicLink(root.resolve("samples/link.xml"), outside);
        // review I-A: a link inside the workspace that points into a hidden folder
        Files.createSymbolicLink(root.resolve("samples/inner.xml"), Path.of("../.git/config"));
        // a workspace configured around the profile directory would contain the backups
        Files.createDirectories(root.resolve("backups"));
        Files.writeString(root.resolve("backups/x.xml"), ENVELOPE);
        E2eTestService service = service();

        for (String envelope : List.of("../order.xml", outside.toString(), ".git/config",
            ".meta/x.xml", ".reports/x.xml", "samples/link.xml", "samples/missing.xml",
            "samples", "./.git/config", "./.meta/x.xml", "samples/inner.xml", ".GIT/config",
            "samples/./order.xml", "samples//order.xml", "./samples/order.xml",
            "backups/x.xml")) {
            ToolError error = refusal(service, request(envelope, PATH, Optional.empty(), 10,
                false, Optional.empty()));
            assertThat(error.code()).as(envelope).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        for (String path : List.of("../x", "https://evil.example.test/x", "/ibis/../../x")) {
            ToolError error = refusal(service, request("samples/order.xml", path,
                Optional.empty(), 10, false, Optional.empty()));
            assertThat(error.code()).as(path).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        for (int timeout : List.of(0, 121)) {
            assertThat(refusal(service, request("samples/order.xml", PATH, Optional.empty(),
                timeout, false, Optional.empty())).code()).isEqualTo(ErrorCode.INVALID_INPUT);
        }
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        Files.delete(outside);
    }

    @Test
    void anEnvelopeWithARealWsSecurityPasswordIsRefused() throws IOException {
        String security = "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/"
            + "oasis-200401-wss-wssecurity-secext-1.0.xsd\"><wsse:UsernameToken>"
            + "<wsse:Username>e2e</wsse:Username><wsse:Password Type=\"#PasswordText\">%s"
            + "</wsse:Password></wsse:UsernameToken></wsse:Security>";
        Files.writeString(root.resolve("samples/secret.xml"), ENVELOPE.replace("<soapenv:Body>",
            "<soapenv:Header>" + security.formatted("synthetic-e2e-0001")
                + "</soapenv:Header><soapenv:Body>"));
        Files.writeString(root.resolve("samples/placeholder.xml"), ENVELOPE.replace(
            "<soapenv:Body>", "<soapenv:Header>" + security.formatted("${secret:Password}")
                + "</soapenv:Header><soapenv:Body>"));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        E2eTestService service = service();

        ToolError error = refusal(service, request("samples/secret.xml", PATH,
            Optional.empty(), 10, false, Optional.empty()));

        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(error.toString()).contains("Password").doesNotContain("synthetic-e2e-0001");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        assertThat(completed(service.run(request("samples/placeholder.xml", PATH,
            Optional.empty(), 10, false, Optional.empty()))).status()).contains(200);
    }

    @Test
    void aPasswordInCdataOrInAnotherCaseIsRefusedAsWell() throws IOException {
        // review m-e
        E2eTestService service = service();
        int i = 0;
        for (String header : List.of(
            "<wsse:Password><![CDATA[synthetic-e2e-0002]]></wsse:Password>",
            "<wsse:password>synthetic-e2e-0003</wsse:password>",
            "<PASSWORD Type=\"x\">synthetic-e2e-0004</PASSWORD>",
            "<wsse:Password>\n  <![CDATA[ synthetic-e2e-0005 ]]>\n</wsse:Password>")) {
            String name = "samples/secret-" + i++ + ".xml";
            Files.writeString(root.resolve(name), ENVELOPE.replace("<soapenv:Body>",
                "<soapenv:Header>" + header + "</soapenv:Header><soapenv:Body>"));
            ToolError error = refusal(service, request(name, PATH, Optional.empty(), 10, false,
                Optional.empty()));
            assertThat(error.code()).as(header).isEqualTo(ErrorCode.INVALID_INPUT);
            assertThat(error.toString()).doesNotContain("synthetic-e2e");
        }
        Files.writeString(root.resolve("samples/cdata-placeholder.xml"), ENVELOPE.replace(
            "<soapenv:Body>", "<soapenv:Header><wsse:Password><![CDATA[${secret:Password}]]>"
                + "</wsse:Password></soapenv:Header><soapenv:Body>"));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        assertThat(completed(service.run(request("samples/cdata-placeholder.xml", PATH,
            Optional.empty(), 10, false, Optional.empty()))).status()).contains(200);
    }

    @Test
    void aTimeoutKeepsTheDiagnostics() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
            .withFixedDelay(3000)));
        instances.add(instance("4711", "Workflow-0001", ProcessState.ACTIVE));

        E2eRun run = completed(service().run(request("samples/order.xml", PATH,
            Optional.of("Workflow-0001"), 1, false, Optional.empty())));

        assertThat(run.timedOut()).isTrue();
        assertThat(run.status()).isEmpty();
        assertThat(run.responseFile()).isEmpty();
        assertThat(run.processes()).extracting(ProcessInstance::processId)
            .containsExactly("4711");
        assertThat(audit).last().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.EXECUTED);
            assertThat(record.reason()).hasValueSatisfying(reason ->
                assertThat(reason).contains("timed out"));
        });
    }

    @Test
    void aBrokenConnectionAfterSendingMayHaveDeliveredTheMessage() {
        // review m-f: the request may have reached INUBIT before the connection broke
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(
            com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        ToolErrorException error = catchThrowableOfType(ToolErrorException.class,
            () -> service().run(request()));

        assertThat(error.error().code()).isEqualTo(ErrorCode.UNREACHABLE);
        assertThat(error.error().message()).contains("may or may not have been delivered");
        assertThat(audit).extracting(AuditRecord::outcome).containsExactly(AuditOutcome.PENDING,
            AuditOutcome.FAILED);
    }

    @Test
    void unreadableLogsAreAWarningNotAFailure() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        logsUnavailable = true;

        E2eRun run = completed(service().run(request()));

        assertThat(run.status()).contains(200);
        assertThat(run.warnings()).anyMatch(w -> w.contains("UNREACHABLE"));
    }

    @Test
    void responseFilesOlderThan30DaysAreRemoved() throws IOException {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        Path directory = Files.createDirectories(root.resolve(".tests/e2e"));
        Path old = Files.writeString(directory.resolve("old.response.xml"), "<old/>");
        Files.setLastModifiedTime(old, FileTime.from(clock.instant().minus(Duration.ofDays(31))));
        Path recent = Files.writeString(directory.resolve("recent.response.xml"), "<new/>");
        Files.setLastModifiedTime(recent, FileTime.from(clock.instant().minus(
            Duration.ofDays(29))));

        completed(service().run(request()));

        assertThat(old).doesNotExist();
        assertThat(recent).exists();
    }
}
