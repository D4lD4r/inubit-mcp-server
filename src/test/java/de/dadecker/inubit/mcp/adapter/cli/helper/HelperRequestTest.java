package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ServerLogKind;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigName;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stdin request of the client helper (research D-4, data-model "Helper request"; feature
 * 024 D-2): its encoding for every operation, its validation and its {@code toString}, and a
 * round trip through the helper itself (loaded as in {@link ClientHelperTest}), so the server
 * writes exactly what the helper accepts. Public because
 * {@code QueueEntryHelperClassifierTest} (another package since feature 021, D-3) builds its
 * requests with {@link #request}.
 */
@Timeout(60)
public class HelperRequestTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final URI URL_81 =
        URI.create("https://inubit.example.test:8443/ibis/servlet/IBIS");
    private static final URI URL_90 = URI.create("http://inubit.example.test:8000");
    private static final Path TRUST_STORE = Path.of("/var/qe-0001/trust.p12");
    private static final Path DATA_OUT = Path.of("/var/qe-0001/out/data.bin");
    private static final Path VARIABLES_OUT = Path.of("/var/qe-0001/out/variables.wfsp");
    private static final String USER = "jdoe";
    private static final String OBJECT_TYPE = "queue_manager@@@Workflow-0001@@@error";
    private static final String FILE = "Order-0001.xml";
    private static final Path LOG_OUT = Path.of("/var/qe-0001/out/webservice.log");
    private static final Path LIST_OUT = Path.of("/var/qe-0001/out/io.list");
    private static final Path MESSAGE_OUT = Path.of("/var/qe-0001/out/message.bin");
    private static final String PROCESS_ID = "P-0001_a";
    private static final String WORKFLOW = "Workflow \u00fc@0001";
    private static final IoMessageRef REF = new IoMessageRef("AbCdEfGhIjKlMnOpQr_-01");

    private static URLClassLoader loader;
    private static Class<?> helper;

    @TempDir
    Path dir;

    @BeforeAll
    static void loadHelper() throws Exception {
        URL dir = ClientHelperPackagingTest.resourceDirectory().toUri().toURL();
        loader = new URLClassLoader(new URL[] {dir}, HelperRequestTest.class.getClassLoader());
        helper = Class.forName(ClientHelperPackagingTest.HELPER_CLASS, true, loader);
    }

    @AfterAll
    static void closeLoader() throws Exception {
        loader.close();
    }

    // --- encoding (D-4) --------------------------------------------------------------------

    @Test
    void encodesEveryKeyOnceInProtocolOrderWithPercentEncodedValues() {
        String text = new String(request(r -> { }).encode(), StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("qe=1\n"
            + "nonce=" + NONCE + "\n"
            + "variant=SOAP_81\n"
            + "url=https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS\n"
            + "trustStore=" + encoded(TRUST_STORE) + "\n"
            + "trustStorePassword=" + TestSecrets.TRUST_STORE_PASSWORD + "\n"
            + "hostnameVerification=true\n"
            + "objectType=queue_manager%40%40%40Workflow-0001%40%40%40error\n"
            + "fileName=Order-0001.xml\n"
            + "parts=data%2Cvariables\n"
            + "dataOut=" + encoded(DATA_OUT) + "\n"
            + "variablesOut=" + encoded(VARIABLES_OUT) + "\n"
            + "user=jdoe\n"
            + "password=" + TestSecrets.CONFIGURED_PASSWORD + "\n"
            + "end\n");
    }

    @Test
    void encodesAServerLogRequestWithItsOperationSecondAndNoFileName() {
        String text = new String(serverLogRequest(r -> { }).encode(), StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("qe=1\n"
            + "op=serverLog\n"
            + "nonce=" + NONCE + "\n"
            + "variant=SOAP_81\n"
            + "url=https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS\n"
            + "trustStore=" + encoded(TRUST_STORE) + "\n"
            + "trustStorePassword=" + TestSecrets.TRUST_STORE_PASSWORD + "\n"
            + "hostnameVerification=true\n"
            + "log=webservice\n"
            + "logOut=" + encoded(LOG_OUT) + "\n"
            + "user=jdoe\n"
            + "password=" + TestSecrets.CONFIGURED_PASSWORD + "\n"
            + "end\n");
    }

    @Test
    void anHttpRequestForVariablesOnlyLeavesTrustStoreAndDataOutEmpty() {
        String text = new String(request(r -> {
            r.variant = "REST_90";
            r.url = URL_90;
            r.trustStore = Optional.empty();
            r.hostnameVerification = false;
            r.dataOut = Optional.empty();
        }).encode(), StandardCharsets.UTF_8);

        assertThat(text).contains("\nvariant=REST_90\n", "\ntrustStore=\n",
            "\ntrustStorePassword=\n", "\nhostnameVerification=false\n", "\nparts=variables\n",
            "\ndataOut=\n");
    }

    @Test
    void reservedSpaceNewlineAndNonAsciiCharactersAreEscapedAsUtf8() {
        String text = new String(request(r -> {
            r.fileName = "Order 0001+ü%=\n€.xml";
            r.password = Secret.of("a b\nqe=2");
        }).encode(), StandardCharsets.UTF_8);

        assertThat(text)
            .contains("\nfileName=Order%200001%2B%C3%BC%25%3D%0A%E2%82%AC.xml\n")
            .contains("\npassword=a%20b%0Aqe%3D2\n")
            .as("unreserved characters stay as they are").contains("\nuser=jdoe\n")
            .as("a value cannot add a line").hasLineCount(15);
    }

    @Test
    void everyEncodeReturnsAFreshBuffer() {
        HelperRequest request = request(r -> { });
        byte[] first = request.encode();
        byte[] copy = first.clone();

        Arrays.fill(first, (byte) 0);

        assertThat(request.encode()).as("ProcessRun zero-fills the buffer it gets")
            .isEqualTo(copy);
    }

    @Test
    void newNonceIsThirtyTwoLowerCaseHexCharactersAndDiffersPerCall() {
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String nonce = HelperRequest.newNonce();
            assertThat(nonce).matches("[0-9a-f]{32}");
            nonces.add(nonce);
        }
        assertThat(nonces).hasSize(100);
    }

    // --- validation ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void anInvalidRequestIsRefusedNamingTheFieldButNoValue(String label,
        Consumer<Builder> edit, String field) {
        assertThatThrownBy(() -> request(edit))
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class)
            .hasMessageContaining(field)
            .satisfies(e -> TestSecrets.assertNoLeak(String.valueOf(e.getMessage())))
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(USER));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
            invalid("nonce too short", r -> r.nonce = "0123abcd", "nonce"),
            invalid("nonce upper case", r -> r.nonce = NONCE.toUpperCase(), "nonce"),
            invalid("nonce not hex", r -> r.nonce = "0123456789abcdef0123456789abcdeg", "nonce"),
            invalid("unknown variant", r -> r.variant = "SOAP_61", "variant"),
            invalid("empty objectType", r -> r.objectType = "", "objectType"),
            invalid("empty fileName", r -> r.fileName = "", "fileName"),
            invalid("empty user", r -> r.user = "", "user"),
            invalid("empty password", r -> r.password = Secret.of(""), "password"),
            invalid("empty trust-store password", r -> r.trustStore = Optional.of(
                new HelperRequest.TrustStore(TRUST_STORE, Secret.of(""))), "trustStorePassword"),
            invalid("password not valid Unicode", r -> r.password = Secret.of(
                TestSecrets.CONFIGURED_PASSWORD + "\uD800"), "password"),
            invalid("user not valid Unicode", r -> r.user = USER + "\uDC00", "user"),
            invalid("null url", r -> r.url = null, "url"),
            invalid("null password", r -> r.password = null, "password"),
            invalid("null variablesOut", r -> r.variablesOut = null, "variablesOut"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidServerLogRequests")
    void anInvalidServerLogRequestIsRefusedNamingTheFieldButNoValue(String label,
        Consumer<Builder> edit, String field) {
        assertThatThrownBy(() -> serverLogRequest(edit))
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class)
            .hasMessageContaining(field)
            .satisfies(e -> TestSecrets.assertNoLeak(String.valueOf(e.getMessage())))
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain(USER));
    }

    static Stream<Arguments> invalidServerLogRequests() {
        return Stream.of(
            invalid("trace is not read by the helper", r -> r.kind = ServerLogKind.TRACE, "kind"),
            invalid("null kind", r -> r.kind = null, "kind"),
            invalid("null logOut", r -> r.logOut = null, "logOut"),
            invalid("empty logOut", r -> r.logOut = Path.of(""), "logOut"),
            invalid("empty user", r -> r.user = "", "user"));
    }

    /** The percent-encoded form of a path as this platform writes it ({@code \} on Windows). */
    private static String encoded(Path path) {
        return path.toString().replace("/", "%2F").replace("\\", "%5C");
    }

    private static Arguments invalid(String label, Consumer<Builder> edit, String field) {
        return Arguments.of(label, edit, field);
    }

    // --- toString (FR-015, SC-003 leak check) ----------------------------------------------

    @Test
    void toStringNamesTheReadButNeitherUserNorPasswords() {
        HelperRequest request = request(r -> r.user = "jdoe-0001");

        assertThat(request.toString())
            .contains(NONCE, "SOAP_81", URL_81.toString(), OBJECT_TYPE, FILE,
                VARIABLES_OUT.toString())
            .doesNotContain("jdoe-0001", "user", "password");
        TestSecrets.assertNoLeak(request.toString());
        TestSecrets.assertNoLeak(String.valueOf(request.trustStore()));
    }

    @Test
    void toStringOfAServerLogRequestNamesTheLogButNeitherUserNorPasswords() {
        HelperRequest request = serverLogRequest(r -> r.user = "jdoe-0001");

        assertThat(request.toString())
            .contains(NONCE, "SOAP_81", URL_81.toString(), "WEBSERVICE", LOG_OUT.toString())
            .doesNotContain("jdoe-0001", "user", "password");
        TestSecrets.assertNoLeak(request.toString());
    }

    // --- round trip through the helper (D-4) -----------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void theHelperAcceptsTheEncodedRequestAndReceivesEveryValueUnchanged(String variant,
        URI url) throws Exception {
        String fileName = "Order 0001+ü%@=\n€.xml";
        String password = TestSecrets.CONFIGURED_PASSWORD + " %+&=\n€";
        String trustStorePassword = TestSecrets.TRUST_STORE_PASSWORD + "/+=";
        Path data = dir.resolve("out dir/data ü.bin");
        Path variables = dir.resolve("out dir/variables ü.wfsp");
        Files.createDirectories(data.getParent());
        FakeInubitScript script = new FakeInubitScript()
            .file(fileName, new byte[] {1, 2, 3})
            .file(fileName + ".wfsp", new byte[] {4, 5});
        HelperRequest request = request(r -> {
            r.variant = variant;
            r.url = url;
            r.trustStore = Optional.of(new HelperRequest.TrustStore(dir.resolve("trust ü.p12"),
                Secret.of(trustStorePassword)));
            r.fileName = fileName;
            r.dataOut = Optional.of(data);
            r.variablesOut = variables;
            r.user = "jdoe ü";
            r.password = Secret.of(password);
        });

        Run run = run(script, request.encode());

        assertThat(run.exit()).as(run.out()).isZero();
        HelperFrames frames = HelperFrames.parse(NONCE, run.out());
        assertThat(frames.problem()).isEmpty();
        assertThat(frames.frames()).containsExactly(new HelperFrames.Start(variant),
            new HelperFrames.LoginOk(), new HelperFrames.PartOk(HelperFrames.Part.DATA, 3),
            new HelperFrames.PartOk(HelperFrames.Part.VARIABLES, 2), new HelperFrames.End());
        assertThat(data).hasBinaryContent(new byte[] {1, 2, 3});
        assertThat(variables).hasBinaryContent(new byte[] {4, 5});
        List<Call> calls = script.calls();
        if (variant.equals("SOAP_81")) {
            assertThat(calls).contains(call("IBISSoapClient.<init>", url + "?isCli",
                dir.resolve("trust ü.p12").toString(), trustStorePassword, "null", "null",
                "true"));
        } else {
            assertThat(calls).contains(
                call("ConnectionData.setServerURL", url.toString()),
                call("ConnectionData.setUserName", "jdoe ü"),
                call("ConnectionData.setPassword", password),
                call("ConnectionData.setServerTrustStoreFile",
                    dir.resolve("trust ü.p12").toString()),
                call("ConnectionData.setServerTrustStorePassword", trustStorePassword),
                call("ConnectionData.setSkipHostnameVerification", "false"));
        }
        assertThat(calls).contains(
            call((variant.equals("SOAP_81") ? "IBISSoapClient" : "RestClient") + ".loginUserCli",
                "jdoe ü", password),
            call("MultipartDownloadThread.setOutputFile", data.toString()),
            call("MultipartDownloadThread.setOutputFile", variables.toString()));
        assertThat(calls).filteredOn(c -> c.name().equals("MultipartDownloadThread.<init>"))
            .extracting(c -> c.arguments().subList(1, 4))
            .containsExactly(List.of("jdoe ü", fileName, OBJECT_TYPE),
                List.of("jdoe ü", fileName + ".wfsp", OBJECT_TYPE));
        TestSecrets.assertNoLeak(run.out());
    }

    static Stream<Arguments> variants() {
        return Stream.of(Arguments.of("SOAP_81", URL_81),
            Arguments.of("REST_90", URI.create("https://inubit.example.test:8443")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void theHelperAcceptsTheEncodedServerLogRequestAndReceivesEveryValueUnchanged(String variant,
        URI url) throws Exception {
        String password = TestSecrets.CONFIGURED_PASSWORD + " %+&=\n€";
        String trustStorePassword = TestSecrets.TRUST_STORE_PASSWORD + "/+=";
        Path logOut = dir.resolve("out dir/webservice ü.log");
        Files.createDirectories(logOut.getParent());
        FakeInubitScript script = new FakeInubitScript().node("inubit-node-01")
            .file("webservice.log", new byte[] {1, 2, 3, 4});
        HelperRequest request = serverLogRequest(r -> {
            r.variant = variant;
            r.url = url;
            r.trustStore = Optional.of(new HelperRequest.TrustStore(dir.resolve("trust ü.p12"),
                Secret.of(trustStorePassword)));
            r.logOut = logOut;
            r.user = "jdoe ü";
            r.password = Secret.of(password);
        });

        Run run = run(script, request.encode());

        assertThat(run.exit()).as(run.out()).isZero();
        HelperFrames frames = HelperFrames.parse(NONCE, run.out());
        assertThat(frames.problem()).isEmpty();
        assertThat(frames.frames()).containsExactly(new HelperFrames.Start(variant),
            new HelperFrames.LoginOk(), new HelperFrames.NodeOk("inubit-node-01"),
            new HelperFrames.PartOk(HelperFrames.Part.LOG, 4), new HelperFrames.End());
        assertThat(logOut).hasBinaryContent(new byte[] {1, 2, 3, 4});
        List<Call> calls = script.calls();
        if (variant.equals("SOAP_81")) {
            assertThat(calls).contains(call("IBISSoapClient.<init>", url + "?isCli",
                dir.resolve("trust ü.p12").toString(), trustStorePassword, "null", "null",
                "true"));
        } else {
            assertThat(calls).contains(
                call("ConnectionData.setServerURL", url.toString()),
                call("ConnectionData.setUserName", "jdoe ü"),
                call("ConnectionData.setPassword", password),
                call("ConnectionData.setServerTrustStoreFile",
                    dir.resolve("trust ü.p12").toString()),
                call("ConnectionData.setServerTrustStorePassword", trustStorePassword),
                call("ConnectionData.setSkipHostnameVerification", "false"));
        }
        String client = variant.equals("SOAP_81") ? "IBISSoapClient" : "RestClient";
        assertThat(calls).contains(
            call(client + ".loginUserCli", "jdoe ü", password),
            call("ClusterClient.<init>", client, "jdoe ü", "fake-client-uid-0001", "null"),
            call("ClusterClient.clusterDownload", logOut.toString(), "inubit-node-01",
                "webservice.log"));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void theHelperAcceptsAnHttpRequestForVariablesOnly() throws Exception {
        Path variables = dir.resolve("variables.wfsp");
        FakeInubitScript script = new FakeInubitScript().file(FILE + ".wfsp", new byte[] {4});
        HelperRequest request = request(r -> {
            r.variant = "REST_90";
            r.url = URL_90;
            r.trustStore = Optional.empty();
            r.hostnameVerification = false;
            r.dataOut = Optional.empty();
            r.variablesOut = variables;
        });

        Run run = run(script, request.encode());

        assertThat(run.exit()).as(run.out()).isZero();
        assertThat(run.out()).isEqualTo("#QE1:" + NONCE + " start REST_90\n"
            + "#QE1:" + NONCE + " login ok\n"
            + "#QE1:" + NONCE + " part variables ok 1\n"
            + "#QE1:" + NONCE + " end\n");
        assertThat(script.calls()).noneMatch(c -> c.name().contains("TrustStore"))
            .contains(call("ConnectionData.setSkipHostnameVerification", "true"));
    }

    @Test
    void aLoginFailureOfTheHelperParsesWithItsChainAndMaskedMessages() throws Exception {
        FakeInubitScript script = new FakeInubitScript().loginFails(
            new Failure("java.io.IOException", "login with " + TestSecrets.CONFIGURED_PASSWORD
                + " failed: 50% done"),
            new Failure("java.lang.IllegalStateException", null));

        Run run = run(script, request(r -> { }).encode());

        assertThat(run.exit()).isEqualTo(3);
        HelperFrames frames = HelperFrames.parse(NONCE, run.out());
        assertThat(frames.problem()).isEmpty();
        assertThat(frames.ended()).as("a failed login has no end frame").isFalse();
        assertThat(frames.frames()).containsExactly(new HelperFrames.Start("SOAP_81"),
            new HelperFrames.LoginError(
                List.of("java.io.IOException", "java.lang.IllegalStateException"),
                "login with ****** failed: 50% done\n"));
        TestSecrets.assertNoLeak(frames.toString());
    }

    // --- the node-tool operations (feature 024, D-2, D-3) ----------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("nodeToolEncodings")
    void encodesANodeToolRequestWithItsOperationSecondAndItsKeysBeforeTheUser(String label,
        HelperRequest.Operation operation, String op, String keys) {
        String text = new String(request(r -> { }, operation).encode(), StandardCharsets.UTF_8);

        assertThat(text).isEqualTo("qe=1\n"
            + "op=" + op + "\n"
            + "nonce=" + NONCE + "\n"
            + "variant=SOAP_81\n"
            + "url=https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS\n"
            + "trustStore=" + encoded(TRUST_STORE) + "\n"
            + "trustStorePassword=" + TestSecrets.TRUST_STORE_PASSWORD + "\n"
            + "hostnameVerification=true\n"
            + keys
            + "user=jdoe\n"
            + "password=" + TestSecrets.CONFIGURED_PASSWORD + "\n"
            + "end\n");
    }

    static Stream<Arguments> nodeToolEncodings() {
        return Stream.of(
            Arguments.of("own node", new HelperRequest.OwnNode(), "ownNode", ""),
            Arguments.of("cluster nodes without details",
                new HelperRequest.ClusterNodes(Optional.empty()), "clusterNodes", "details=\n"),
            Arguments.of("cluster nodes with details",
                new HelperRequest.ClusterNodes(Optional.of("inubit-node-01.zone:a_b")),
                "clusterNodes", "details=inubit-node-01.zone%3Aa_b\n"),
            Arguments.of("node config", new HelperRequest.NodeConfig(List.of(
                    NodeConfigName.OPERATION_MODE, NodeConfigName.NODE_NAME,
                    NodeConfigName.RETRY_INTERVAL)), "nodeConfig",
                "names=OperationMode%2CNodeName%2CretryInterval\n"),
            Arguments.of("io log listing", ioLog(Optional.empty(), Optional.empty()), "ioLog",
                "processId=" + PROCESS_ID + "\n"
                    + "workflow=Workflow%20%C3%BC%400001\n"
                    + "ref=\n"
                    + "listOut=" + encoded(LIST_OUT) + "\n"
                    + "messageOut=\n"),
            Arguments.of("io log message", ioLog(Optional.of(REF), Optional.of(MESSAGE_OUT)),
                "ioLog",
                "processId=" + PROCESS_ID + "\n"
                    + "workflow=Workflow%20%C3%BC%400001\n"
                    + "ref=" + REF.value() + "\n"
                    + "listOut=" + encoded(LIST_OUT) + "\n"
                    + "messageOut=" + encoded(MESSAGE_OUT) + "\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNodeToolOperations")
    void anInvalidNodeToolOperationIsRefusedNamingTheFieldButNoValue(String label,
        ThrowingCallable create, String field) {
        assertThatThrownBy(create)
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class)
            .hasMessageContaining(field)
            .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("marker"));
    }

    static Stream<Arguments> invalidNodeToolOperations() {
        Optional<IoMessageRef> ref = Optional.of(REF);
        Optional<Path> messageOut = Optional.of(MESSAGE_OUT);
        return Stream.of(
            invalidOperation("null details", () -> new HelperRequest.ClusterNodes(null),
                "details"),
            invalidOperation("empty details instead of none",
                () -> new HelperRequest.ClusterNodes(Optional.of("")), "details"),
            invalidOperation("details with a space",
                () -> new HelperRequest.ClusterNodes(Optional.of("node marker")), "details"),
            invalidOperation("details with a slash",
                () -> new HelperRequest.ClusterNodes(Optional.of("node/marker")), "details"),
            invalidOperation("details of 129 characters", () -> new HelperRequest.ClusterNodes(
                Optional.of("marker" + "n".repeat(123))), "details"),
            invalidOperation("null names", () -> new HelperRequest.NodeConfig(null), "names"),
            invalidOperation("no names", () -> new HelperRequest.NodeConfig(List.of()), "names"),
            invalidOperation("a null name", () -> new HelperRequest.NodeConfig(Arrays.asList(
                NodeConfigName.OPERATION_MODE, null)), "names"),
            invalidOperation("a repeated name", () -> new HelperRequest.NodeConfig(List.of(
                NodeConfigName.NODE_NAME, NodeConfigName.ZONE_NAME, NodeConfigName.NODE_NAME)),
                "names"),
            invalidOperation("null processId", () -> new HelperRequest.IoLog(null, WORKFLOW,
                Optional.empty(), LIST_OUT, Optional.empty()), "processId"),
            invalidOperation("empty processId", () -> new HelperRequest.IoLog("", WORKFLOW,
                Optional.empty(), LIST_OUT, Optional.empty()), "processId"),
            invalidOperation("processId of 65 characters", () -> new HelperRequest.IoLog(
                "marker" + "p".repeat(59), WORKFLOW, Optional.empty(), LIST_OUT,
                Optional.empty()), "processId"),
            invalidOperation("processId with a dot", () -> new HelperRequest.IoLog("marker.0001",
                WORKFLOW, Optional.empty(), LIST_OUT, Optional.empty()), "processId"),
            invalidOperation("null workflow", () -> new HelperRequest.IoLog(PROCESS_ID, null,
                Optional.empty(), LIST_OUT, Optional.empty()), "workflow"),
            invalidOperation("empty workflow", () -> new HelperRequest.IoLog(PROCESS_ID, "",
                Optional.empty(), LIST_OUT, Optional.empty()), "workflow"),
            invalidOperation("workflow of 201 characters", () -> new HelperRequest.IoLog(
                PROCESS_ID, "marker" + "w".repeat(195), Optional.empty(), LIST_OUT,
                Optional.empty()), "workflow"),
            invalidOperation("workflow with a line break", () -> new HelperRequest.IoLog(
                PROCESS_ID, "Workflow\nmarker", Optional.empty(), LIST_OUT, Optional.empty()),
                "workflow"),
            invalidOperation("workflow with a C1 control character", () -> new HelperRequest.IoLog(
                PROCESS_ID, "Workflow\u0085marker", Optional.empty(), LIST_OUT,
                Optional.empty()), "workflow"),
            invalidOperation("workflow with the entry separator", () -> new HelperRequest.IoLog(
                PROCESS_ID, "Workflow@@@marker", Optional.empty(), LIST_OUT, Optional.empty()),
                "workflow"),
            invalidOperation("workflow with the download separator",
                () -> new HelperRequest.IoLog(PROCESS_ID, "Workflow###marker", Optional.empty(),
                    LIST_OUT, Optional.empty()), "workflow"),
            invalidOperation("workflow not valid Unicode", () -> new HelperRequest.IoLog(
                PROCESS_ID, "Workflow-marker\uD800", Optional.empty(), LIST_OUT,
                Optional.empty()), "workflow"),
            invalidOperation("null ref", () -> new HelperRequest.IoLog(PROCESS_ID, WORKFLOW, null,
                LIST_OUT, Optional.empty()), "ref"),
            invalidOperation("null listOut", () -> new HelperRequest.IoLog(PROCESS_ID, WORKFLOW,
                Optional.empty(), null, Optional.empty()), "listOut"),
            invalidOperation("empty listOut", () -> new HelperRequest.IoLog(PROCESS_ID, WORKFLOW,
                Optional.empty(), Path.of(""), Optional.empty()), "listOut"),
            invalidOperation("null messageOut", () -> new HelperRequest.IoLog(PROCESS_ID,
                WORKFLOW, ref, LIST_OUT, null), "messageOut"),
            invalidOperation("messageOut without ref", () -> new HelperRequest.IoLog(PROCESS_ID,
                WORKFLOW, Optional.empty(), LIST_OUT, messageOut), "messageOut"),
            invalidOperation("ref without messageOut", () -> new HelperRequest.IoLog(PROCESS_ID,
                WORKFLOW, ref, LIST_OUT, Optional.empty()), "messageOut"),
            invalidOperation("empty messageOut", () -> new HelperRequest.IoLog(PROCESS_ID,
                WORKFLOW, ref, LIST_OUT, Optional.of(Path.of(""))), "messageOut"));
    }

    @Test
    void theLimitsOfTheNodeToolOperationsAreAccepted() {
        String details = "inubit-node_01.zone:" + "n".repeat(108);
        String processId = "P".repeat(64);
        String workflow = "Workflow ü " + "w".repeat(189);

        assertThat(new HelperRequest.ClusterNodes(Optional.of(details)).details())
            .contains(details);
        assertThat(new HelperRequest.NodeConfig(List.of(NodeConfigName.values())).names())
            .containsExactly(NodeConfigName.values());
        HelperRequest.IoLog log = new HelperRequest.IoLog(processId, workflow, Optional.empty(),
            LIST_OUT, Optional.empty());
        assertThat(log.processId()).hasSize(64);
        assertThat(log.workflow()).hasSize(200);
    }

    @Test
    void toStringOfANodeToolRequestNamesTheOperationButNeitherUserNorPasswords() {
        HelperRequest request = request(r -> r.user = "jdoe-0001",
            ioLog(Optional.of(REF), Optional.of(MESSAGE_OUT)));

        assertThat(request.toString())
            .contains(NONCE, PROCESS_ID, WORKFLOW, REF.value(), LIST_OUT.toString(),
                MESSAGE_OUT.toString())
            .doesNotContain("jdoe-0001", "user", "password");
        TestSecrets.assertNoLeak(request.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void theHelperAcceptsTheEncodedOwnNodeRequestAndReceivesEveryValueUnchanged(String variant,
        URI url) throws Exception {
        String password = TestSecrets.CONFIGURED_PASSWORD + " %+&=\n€";
        String trustStorePassword = TestSecrets.TRUST_STORE_PASSWORD + "/+=";
        FakeInubitScript script = new FakeInubitScript().node("inubit-node-01");
        HelperRequest request = request(r -> {
            r.variant = variant;
            r.url = url;
            r.trustStore = Optional.of(new HelperRequest.TrustStore(dir.resolve("trust ü.p12"),
                Secret.of(trustStorePassword)));
            r.user = "jdoe ü";
            r.password = Secret.of(password);
        }, new HelperRequest.OwnNode());

        Run run = run(script, request.encode());

        assertThat(run.exit()).as(run.out()).isZero();
        HelperFrames frames = HelperFrames.parse(NONCE, run.out());
        assertThat(frames.problem()).isEmpty();
        assertThat(frames.frames()).containsExactly(new HelperFrames.Start(variant),
            new HelperFrames.LoginOk(), new HelperFrames.NodeOk("inubit-node-01"),
            new HelperFrames.End());
        String client = variant.equals("SOAP_81") ? "IBISSoapClient" : "RestClient";
        assertThat(script.calls()).contains(
            call(client + ".loginUserCli", "jdoe ü", password),
            call("ClusterClient.<init>", client, "jdoe ü", "fake-client-uid-0001", "null"),
            call("ClusterClient.getNode"));
        TestSecrets.assertNoLeak(run.out());
    }

    /**
     * Every node-tool request the server encodes passes the helper's request check: without any
     * vendor class the helper gets past it to the API check (exit 4), never to exit 2.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedNodeToolOperations")
    void theHelperFindsTheEncodedNodeToolRequestWellFormed(String label,
        HelperRequest.Operation operation) throws Exception {
        Run run = runWithout(request(r -> r.password = Secret.of(
            TestSecrets.CONFIGURED_PASSWORD + " %+&=\n€"), operation).encode());

        assertThat(run.exit()).as(run.out()).isEqualTo(4);
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).hasSize(1);
    }

    static Stream<Arguments> acceptedNodeToolOperations() {
        return Stream.of(
            Arguments.of("own node", new HelperRequest.OwnNode()),
            Arguments.of("cluster nodes without details",
                new HelperRequest.ClusterNodes(Optional.empty())),
            Arguments.of("cluster nodes with 128 characters of details",
                new HelperRequest.ClusterNodes(Optional.of("inubit-node_01.zone:"
                    + "n".repeat(108)))),
            Arguments.of("node config with one name",
                new HelperRequest.NodeConfig(List.of(NodeConfigName.CLUSTER_BIND_ADDRESS))),
            Arguments.of("node config with all twenty names",
                new HelperRequest.NodeConfig(List.of(NodeConfigName.values()))),
            Arguments.of("io log listing", ioLog(Optional.empty(), Optional.empty())),
            Arguments.of("io log message", ioLog(Optional.of(REF), Optional.of(MESSAGE_OUT))),
            Arguments.of("io log at its limits", new HelperRequest.IoLog("P".repeat(64),
                "Workflow ü " + "w".repeat(189), Optional.of(REF), Path.of("/var/io ü.list"),
                Optional.of(Path.of("/var/message ü.bin")))));
    }

    private static HelperRequest.IoLog ioLog(Optional<IoMessageRef> ref,
        Optional<Path> messageOut) {
        return new HelperRequest.IoLog(PROCESS_ID, WORKFLOW, ref, LIST_OUT, messageOut);
    }

    private static Arguments invalidOperation(String label, ThrowingCallable create,
        String field) {
        return Arguments.of(label, create, field);
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * The fields of a request; {@link #request} builds a valid 8.1 queue-entry read and
     * {@link #serverLogRequest} a valid 8.1 server-log read from them, changed by an edit.
     */
    public static final class Builder {
        public String nonce = NONCE;
        public String variant = "SOAP_81";
        public URI url = URL_81;
        public Optional<HelperRequest.TrustStore> trustStore = Optional.of(
            new HelperRequest.TrustStore(TRUST_STORE, Secret.of(TestSecrets.TRUST_STORE_PASSWORD)));
        public boolean hostnameVerification = true;
        public String objectType = OBJECT_TYPE;
        public String fileName = FILE;
        public Optional<Path> dataOut = Optional.of(DATA_OUT);
        public Path variablesOut = VARIABLES_OUT;
        public String user = USER;
        public Secret password = Secret.of(TestSecrets.CONFIGURED_PASSWORD);
        public ServerLogKind kind = ServerLogKind.WEBSERVICE;
        public Path logOut = LOG_OUT;
    }

    public static HelperRequest request(Consumer<Builder> edit) {
        Builder r = new Builder();
        edit.accept(r);
        return new HelperRequest(r.nonce, r.variant, r.url, r.trustStore, r.hostnameVerification,
            r.user, r.password, new HelperRequest.QueueEntryRead(r.objectType, r.fileName,
                r.dataOut, r.variablesOut));
    }

    public static HelperRequest serverLogRequest(Consumer<Builder> edit) {
        Builder r = new Builder();
        edit.accept(r);
        return new HelperRequest(r.nonce, r.variant, r.url, r.trustStore, r.hostnameVerification,
            r.user, r.password, new HelperRequest.ServerLogRead(r.kind, r.logOut));
    }

    /** A valid 8.1 request of {@code operation} with the connection fields changed by an edit. */
    static HelperRequest request(Consumer<Builder> edit, HelperRequest.Operation operation) {
        Builder r = new Builder();
        edit.accept(r);
        return new HelperRequest(r.nonce, r.variant, r.url, r.trustStore, r.hostnameVerification,
            r.user, r.password, operation);
    }

    static Call call(String name, String... arguments) {
        return new Call(name, List.of(arguments));
    }

    /** Calls {@code ClientHelper.run} in-process with the fake vendor classes. */
    static Run run(FakeInubitScript script, byte[] stdin) throws Exception {
        Method run = helper.getDeclaredMethod("run", InputStream.class, PrintStream.class,
            ClassLoader.class);
        run.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FakeInubitScript.Activation ignored = script.activate()) {
            int exit = (int) run.invoke(null, new ByteArrayInputStream(stdin),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                HelperRequestTest.class.getClassLoader());
            return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    /** Calls {@code ClientHelper.run} in-process without any vendor class. */
    static Run runWithout(byte[] stdin) throws Exception {
        Method run = helper.getDeclaredMethod("run", InputStream.class, PrintStream.class,
            ClassLoader.class);
        run.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = (int) run.invoke(null, new ByteArrayInputStream(stdin),
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            ClassLoader.getPlatformClassLoader());
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    record Run(int exit, String out) {
    }
}
