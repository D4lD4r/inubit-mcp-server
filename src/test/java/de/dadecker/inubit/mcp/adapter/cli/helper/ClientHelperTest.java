package de.dadecker.inubit.mcp.adapter.cli.helper;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Member;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.NullFrom;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Property;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The client helper in-process: the queue-entry read of feature 020 (research D-2, D-4) and the
 * server-log read of feature 021 (D-2, D-4, D-5), and the request check of the node-tool
 * operations with the own-node lookup, the cluster node list, the node configuration and the
 * I/O logging of feature 024 (D-2, D-3, D-7, D-8, D-9, D-10).
 * It is loaded from its resource directory {@code META-INF/inubit-mcp/client-helper/} of the
 * compiled classes by a {@link URLClassLoader} whose parent is the test class loader (so the
 * fake vendor classes under {@code com.inubit.ibis} are visible), and
 * {@code run(InputStream, PrintStream, ClassLoader)} is called reflectively; {@code main} and
 * its {@code System.exit} are never called here.
 */
@Timeout(60)
class ClientHelperTest {

    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String URL_81 = "https://inubit.example.test:8443/ibis/servlet/IBIS";
    private static final String URL_90 = "https://inubit.example.test:8443";
    private static final String TRUST_STORE = "/var/qe-0001/trust.p12";
    private static final String USER = "jdoe";
    private static final String FILE = "Order-0001.xml";
    private static final String OBJECT_TYPE = "queue_manager@@@Workflow-0001@@@error";
    private static final byte[] DATA = {'P', 'K', 3, 4, 0, (byte) 0xFF, '\n'};
    private static final byte[] VARIABLES = "<Properties version=\"4.1\"><Property name=\"apiToken\">"
        .concat(TestSecrets.CREDENTIAL_PAYLOAD_VALUE).concat("</Property></Properties>")
        .getBytes(StandardCharsets.UTF_8);
    private static final String NODE = "inubit-node-01";
    private static final String NODE_2 = "inubit-node-02";
    static final String CLIENT_UID = "fake-client-uid-0001";
    private static final String WEBSERVICE_LOG = "webservice.log";
    private static final byte[] LOG = ("2026-11-12 10:00:00,000 1 [WebService] request\n"
        + "<soapenv:Envelope/>\n").getBytes(StandardCharsets.UTF_8);
    /** The 20 names {@code nodeConfig} may read (contracts/helper-protocol-delta.md). */
    private static final List<String> NODE_CONFIG_NAMES = List.of("OperationMode", "ClusterName",
        "ZoneName", "NodeName", "LocalMaintenanceMode", "ActivateClusterManager", "ClusterDebug",
        "ContainerShutdownOnNodeFailure", "RegisterMBeans", "RegisterInternalMBeans",
        "RuntimeDataCacheXMXPercentage", "socketTimeoutInMillisecs", "indexEnabled",
        "iterationCount", "DataSourceLocation", "LogsDBDataSourceLocation",
        "RepositoryDataSourceLocation", "ClusterBindAddress", "noOfRetries", "retryInterval");
    /** A message reference (22 characters of the Base64url alphabet). */
    private static final String REF = "AbCdEfGhIjKlMnOpQr_-01";
    /** The object type of the I/O logging listing (research "Static facts"). */
    private static final String IO_LISTING = "inputOutputMessageDetails";
    /** A listed I/O logging entry of the run {@code P-0001_a} and its message reference. */
    private static final String IO_ENTRY = "P-0001_a@@@Orders(Module-0001)@@@Input.1";
    private static final String IO_REF = IoMessageRef.of(IO_ENTRY).value();
    /** The reference vectors shared with {@code IoMessageRefTest} (T007). */
    private static final Path IO_VECTORS =
        Path.of("src/test/resources/fixtures/node-tools/io-message-ref.vectors");

    private static URLClassLoader loader;
    private static Class<?> helper;

    @TempDir
    Path dir;

    @BeforeAll
    static void loadHelper() throws Exception {
        URL dir = ClientHelperPackagingTest.resourceDirectory().toUri().toURL();
        loader = new URLClassLoader(new URL[] {dir}, ClientHelperTest.class.getClassLoader());
        helper = Class.forName(ClientHelperPackagingTest.HELPER_CLASS, true, loader);
        assertThat(helper.getClassLoader()).as("loaded from the resource directory")
            .isSameAs(loader);
    }

    @AfterAll
    static void closeLoader() throws Exception {
        loader.close();
    }

    // --- request validation (D-4: exit 2 on a malformed request) --------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRequests")
    void aMalformedRequestEndsWithExitTwoAndPrintsNothing(String label, String request)
        throws Exception {
        Run run = run(request, ClassLoader.getPlatformClassLoader());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).as("no value is echoed").isEmpty();
        TestSecrets.assertNoLeak(run.out());
    }

    static Stream<Arguments> malformedRequests() {
        return Stream.of(
            Arguments.of("empty input", ""),
            Arguments.of("no end line", request(lines -> { }).replace("end\n", "")),
            Arguments.of("protocol line missing", request(lines -> lines.remove("qe"))),
            Arguments.of("other protocol version", request(lines -> lines.put("qe", "2"))),
            Arguments.of("protocol line not first", "nonce=" + NONCE + "\n"
                + request(lines -> lines.remove("nonce"))),
            Arguments.of("unknown key", request(lines -> lines.put("command", "deleteAll"))),
            Arguments.of("duplicate key", request(lines -> { })
                .replace("end\n", "user=other\nend\n")),
            Arguments.of("line without '='", request(lines -> { })
                .replace("end\n", TestSecrets.CONFIGURED_PASSWORD + "\nend\n")),
            Arguments.of("password missing", request(lines -> lines.remove("password"))),
            Arguments.of("dataOut missing", request(lines -> lines.remove("dataOut"))),
            Arguments.of("nonce too short", request(lines -> lines.put("nonce", "0123abcd"))),
            Arguments.of("nonce not hex", request(lines -> lines.put("nonce",
                "0123456789abcdef0123456789abcdeg"))),
            Arguments.of("unknown variant", request(lines -> lines.put("variant", "SOAP_61"))),
            Arguments.of("hostnameVerification not a boolean",
                request(lines -> lines.put("hostnameVerification", "yes"))),
            Arguments.of("unknown parts", request(lines -> lines.put("parts", "data"))),
            Arguments.of("data requested without dataOut",
                request(lines -> lines.put("dataOut", ""))),
            Arguments.of("empty url", request(lines -> lines.put("url", ""))),
            Arguments.of("empty user", request(lines -> lines.put("user", ""))),
            Arguments.of("empty password", request(lines -> lines.put("password", ""))),
            Arguments.of("empty fileName", request(lines -> lines.put("fileName", ""))),
            Arguments.of("empty objectType", request(lines -> lines.put("objectType", ""))),
            Arguments.of("empty variablesOut", request(lines -> lines.put("variablesOut", ""))),
            Arguments.of("value not percent-encoded",
                request(lines -> lines.put("fileName", "Order 0001.xml"))),
            Arguments.of("reserved character not percent-encoded", request(lines -> lines.put(
                "objectType", "queue_manager@@@Workflow-0001@@@error"))),
            Arguments.of("truncated escape", request(lines -> lines.put("password",
                TestSecrets.CONFIGURED_PASSWORD + "%4"))),
            Arguments.of("escape not hex", request(lines -> lines.put("password",
                TestSecrets.CONFIGURED_PASSWORD + "%G1"))),
            Arguments.of("escape with non-ASCII digits", request(lines -> lines.put("password",
                TestSecrets.CONFIGURED_PASSWORD + "%\u0663\u0663"))),
            Arguments.of("escape decodes to invalid UTF-8", request(lines -> lines.put(
                "trustStorePassword", TestSecrets.TRUST_STORE_PASSWORD + "%FF"))),
            // feature 021 (D-4): op is optional, queueEntry or serverLog, each with its own keys
            Arguments.of("operation serverLog with the queue-entry keys",
                withOp("op=serverLog")),
            Arguments.of("unknown operation", withOp("op=other")),
            Arguments.of("empty operation", withOp("op=")),
            Arguments.of("operation in another case", withOp("op=QueueEntry")),
            Arguments.of("operation repeated", withOp("op=queueEntry\nop=queueEntry")),
            Arguments.of("queue entry with a server-log key",
                request(lines -> lines.put("log", "webservice"))),
            Arguments.of("server log with a fileName key",
                serverLogRequest(lines -> lines.put("fileName", "webservice.log"))),
            Arguments.of("server log with an objectType key",
                serverLogRequest(lines -> lines.put("objectType", "queue_manager"))),
            Arguments.of("server log with a parts key",
                serverLogRequest(lines -> lines.put("parts", "variables"))),
            Arguments.of("server log without op", serverLogRequest(lines -> lines.remove("op"))),
            Arguments.of("server log keys with op queueEntry",
                serverLogRequest(lines -> lines.put("op", "queueEntry"))),
            Arguments.of("server log trace.log",
                serverLogRequest(lines -> lines.put("log", "trace"))),
            Arguments.of("server log in another case",
                serverLogRequest(lines -> lines.put("log", "WEBSERVICE"))),
            Arguments.of("server log file name instead of kind",
                serverLogRequest(lines -> lines.put("log", "webservice.log"))),
            Arguments.of("server log kind empty", serverLogRequest(lines -> lines.put("log", ""))),
            Arguments.of("server log kind missing",
                serverLogRequest(lines -> lines.remove("log"))),
            Arguments.of("server log logOut missing",
                serverLogRequest(lines -> lines.remove("logOut"))),
            Arguments.of("server log logOut empty",
                serverLogRequest(lines -> lines.put("logOut", ""))),
            Arguments.of("server log empty user",
                serverLogRequest(lines -> lines.put("user", ""))),
            Arguments.of("server log with a fileName instead of the password",
                serverLogRequest(lines -> {
                    lines.remove("password");
                    lines.put("fileName", "webservice.log");
                })),
            Arguments.of("server log unknown variant",
                serverLogRequest(lines -> lines.put("variant", "SOAP_61"))),
            Arguments.of("server log nonce not hex", serverLogRequest(lines -> lines.put("nonce",
                "0123456789abcdef0123456789abcdeg"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wellFormedRequests")
    void aWellFormedRequestIsNotRejectedAsMalformed(String label, String request)
        throws Exception {
        Run run = run(request, ClassLoader.getPlatformClassLoader());

        assertThat(run.exit()).isNotEqualTo(2);
        TestSecrets.assertNoLeak(run.out());
    }

    static Stream<Arguments> wellFormedRequests() {
        return Stream.of(
            Arguments.of("8.1 over https", request(lines -> { })),
            Arguments.of("9.0 over http without trust store", request(lines -> {
                lines.put("variant", "REST_90");
                lines.put("url", "http%3A%2F%2Finubit.example.test%3A8000");
                lines.put("trustStore", "");
                lines.put("trustStorePassword", "");
                lines.put("hostnameVerification", "false");
            })),
            Arguments.of("variables only, no data path", request(lines -> {
                lines.put("parts", "variables");
                lines.put("dataOut", "");
            })),
            Arguments.of("upper-case nonce, lower-case escape, non-ASCII file name", request(lines -> {
                lines.put("nonce", NONCE.toUpperCase());
                lines.put("fileName", "Order%20%C3%BC%2b0001.xml");
            })),
            Arguments.of("CRLF line ends", request(lines -> { }).replace("\n", "\r\n")),
            Arguments.of("operation queueEntry", withOp("op=queueEntry")),
            Arguments.of("operation queueEntry as the last key",
                request(lines -> lines.put("op", "queueEntry"))),
            Arguments.of("server log on 8.1", serverLogRequest(lines -> { })),
            Arguments.of("server log on 9.0 over http without trust store",
                serverLogRequest(lines -> {
                    lines.put("variant", "REST_90");
                    lines.put("url", "http%3A%2F%2Finubit.example.test%3A8000");
                    lines.put("trustStore", "");
                    lines.put("trustStorePassword", "");
                    lines.put("hostnameVerification", "false");
                })),
            Arguments.of("server log with op as the last key", serverLogRequest(lines -> {
                lines.remove("op");
                lines.put("op", "serverLog");
            })),
            // feature 024 (D-2)
            Arguments.of("own node on 8.1", nodeToolRequest("ownNode", lines -> { })),
            Arguments.of("own node on 9.0 over http without trust store",
                nodeToolRequest("ownNode", lines -> {
                    lines.put("variant", "REST_90");
                    lines.put("url", "http%3A%2F%2Finubit.example.test%3A8000");
                    lines.put("trustStore", "");
                    lines.put("trustStorePassword", "");
                    lines.put("hostnameVerification", "false");
                })),
            Arguments.of("cluster nodes without details",
                nodeToolRequest("clusterNodes", lines -> { })),
            Arguments.of("cluster nodes with details",
                nodeToolRequest("clusterNodes", lines -> lines.put("details", NODE))),
            Arguments.of("cluster nodes with 128 characters of details", nodeToolRequest(
                "clusterNodes", lines -> lines.put("details",
                    "inubit-node_01.zone%3A" + "n".repeat(108)))),
            Arguments.of("node config with one name",
                nodeToolRequest("nodeConfig", lines -> lines.put("names", "ClusterBindAddress"))),
            Arguments.of("node config with all twenty names", nodeToolRequest("nodeConfig",
                lines -> lines.put("names", String.join("%2C", NODE_CONFIG_NAMES)))),
            Arguments.of("io log listing", nodeToolRequest("ioLog", lines -> { })),
            Arguments.of("io log message", nodeToolRequest("ioLog", lines -> {
                lines.put("ref", REF);
                lines.put("messageOut", "%2Fvar%2Fqe-0001%2Fout%2Fmessage.bin");
            })),
            Arguments.of("io log at its limits", nodeToolRequest("ioLog", lines -> {
                lines.put("processId", "P".repeat(64));
                lines.put("workflow", "Workflow%20%C3%BC%20" + "w".repeat(189));
            })),
            Arguments.of("io log workflow with single separator characters",
                nodeToolRequest("ioLog", lines -> lines.put("workflow",
                    "Order%40Shop%23%23Inbound"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedNodeToolRequests")
    void aMalformedNodeToolRequestEndsWithExitTwoBeforeAnyVendorCall(String label,
        String request) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, request, vendor());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).as("no value is echoed").isEmpty();
        assertThat(script.calls()).as("nothing is sent").isEmpty();
    }

    static Stream<Arguments> malformedNodeToolRequests() {
        return Stream.of(
            // a key of another operation, a repeated or missing key, an unknown or repeated op
            Arguments.of("own node with a details key",
                nodeToolRequest("ownNode", lines -> lines.put("details", ""))),
            Arguments.of("own node with a fileName key",
                nodeToolRequest("ownNode", lines -> lines.put("fileName", "Order-0001.xml"))),
            Arguments.of("own node with a log key",
                nodeToolRequest("ownNode", lines -> lines.put("log", "webservice"))),
            Arguments.of("own node in another case",
                nodeToolRequest("ownNode", lines -> lines.put("op", "OwnNode"))),
            Arguments.of("own node op repeated", nodeToolRequest("ownNode", lines -> { })
                .replace("op=ownNode\n", "op=ownNode\nop=ownNode\n")),
            Arguments.of("cluster nodes keys without op",
                nodeToolRequest("clusterNodes", lines -> lines.remove("op"))),
            Arguments.of("cluster nodes without the details key",
                nodeToolRequest("clusterNodes", lines -> lines.remove("details"))),
            Arguments.of("cluster nodes with details repeated",
                nodeToolRequest("clusterNodes", lines -> { })
                    .replace("end\n", "details=" + NODE + "\nend\n")),
            Arguments.of("cluster nodes with a names key",
                nodeToolRequest("clusterNodes", lines -> lines.put("names", "NodeName"))),
            Arguments.of("cluster nodes details with a space",
                nodeToolRequest("clusterNodes", lines -> lines.put("details", "inubit%20node"))),
            Arguments.of("cluster nodes details with a slash",
                nodeToolRequest("clusterNodes", lines -> lines.put("details", "inubit%2Fnode"))),
            Arguments.of("cluster nodes details with a non-ASCII letter",
                nodeToolRequest("clusterNodes", lines -> lines.put("details", "n%C3%BCde"))),
            Arguments.of("cluster nodes details of 129 characters", nodeToolRequest(
                "clusterNodes", lines -> lines.put("details", "n".repeat(129)))),
            Arguments.of("node config without the names key",
                nodeToolRequest("nodeConfig", lines -> lines.remove("names"))),
            Arguments.of("node config with empty names",
                nodeToolRequest("nodeConfig", lines -> lines.put("names", ""))),
            Arguments.of("node config with a name outside the list",
                nodeToolRequest("nodeConfig", lines -> lines.put("names", "Database"))),
            Arguments.of("node config with a password name",
                nodeToolRequest("nodeConfig", lines -> lines.put("names",
                    "NodeName%2CClusterPassword"))),
            Arguments.of("node config with a name in another case",
                nodeToolRequest("nodeConfig", lines -> lines.put("names", "operationMode"))),
            Arguments.of("node config with a repeated name",
                nodeToolRequest("nodeConfig", lines -> lines.put("names",
                    "NodeName%2CZoneName%2CNodeName"))),
            Arguments.of("node config with an empty name",
                nodeToolRequest("nodeConfig", lines -> lines.put("names",
                    "OperationMode%2C%2CNodeName"))),
            Arguments.of("node config with a trailing comma",
                nodeToolRequest("nodeConfig", lines -> lines.put("names", "OperationMode%2C"))),
            Arguments.of("node config with a space after the comma",
                nodeToolRequest("nodeConfig", lines -> lines.put("names",
                    "OperationMode%2C%20NodeName"))),
            Arguments.of("node config with twenty-one names", nodeToolRequest("nodeConfig",
                lines -> lines.put("names",
                    String.join("%2C", NODE_CONFIG_NAMES) + "%2COperationMode"))),
            Arguments.of("node config with a details key",
                nodeToolRequest("nodeConfig", lines -> lines.put("details", ""))),
            Arguments.of("io log without the processId key",
                nodeToolRequest("ioLog", lines -> lines.remove("processId"))),
            Arguments.of("io log with an empty processId",
                nodeToolRequest("ioLog", lines -> lines.put("processId", ""))),
            Arguments.of("io log processId of 65 characters",
                nodeToolRequest("ioLog", lines -> lines.put("processId", "P".repeat(65)))),
            Arguments.of("io log processId with a dot",
                nodeToolRequest("ioLog", lines -> lines.put("processId", "P.0001"))),
            Arguments.of("io log without the workflow key",
                nodeToolRequest("ioLog", lines -> lines.remove("workflow"))),
            Arguments.of("io log with an empty workflow",
                nodeToolRequest("ioLog", lines -> lines.put("workflow", ""))),
            Arguments.of("io log workflow of 201 characters",
                nodeToolRequest("ioLog", lines -> lines.put("workflow", "w".repeat(201)))),
            Arguments.of("io log workflow with a line break",
                nodeToolRequest("ioLog", lines -> lines.put("workflow", "Workflow%0A0001"))),
            Arguments.of("io log workflow with a C1 control character",
                nodeToolRequest("ioLog", lines -> lines.put("workflow", "Workflow%C2%850001"))),
            Arguments.of("io log workflow with the entry separator",
                nodeToolRequest("ioLog", lines -> lines.put("workflow",
                    "Workflow%40%40%400001"))),
            Arguments.of("io log workflow with the download separator",
                nodeToolRequest("ioLog", lines -> lines.put("workflow",
                    "Workflow%23%23%230001"))),
            Arguments.of("io log without the ref key",
                nodeToolRequest("ioLog", lines -> lines.remove("ref"))),
            Arguments.of("io log ref of 21 characters", nodeToolRequest("ioLog", lines -> {
                lines.put("ref", REF.substring(1));
                lines.put("messageOut", "%2Fvar%2Fqe-0001%2Fout%2Fmessage.bin");
            })),
            Arguments.of("io log ref of 23 characters", nodeToolRequest("ioLog", lines -> {
                lines.put("ref", REF + "A");
                lines.put("messageOut", "%2Fvar%2Fqe-0001%2Fout%2Fmessage.bin");
            })),
            Arguments.of("io log ref with a Base64 plus", nodeToolRequest("ioLog", lines -> {
                lines.put("ref", "%2B" + REF.substring(1));
                lines.put("messageOut", "%2Fvar%2Fqe-0001%2Fout%2Fmessage.bin");
            })),
            Arguments.of("io log ref without messageOut",
                nodeToolRequest("ioLog", lines -> lines.put("ref", REF))),
            Arguments.of("io log messageOut without ref", nodeToolRequest("ioLog",
                lines -> lines.put("messageOut", "%2Fvar%2Fqe-0001%2Fout%2Fmessage.bin"))),
            Arguments.of("io log without the messageOut key",
                nodeToolRequest("ioLog", lines -> lines.remove("messageOut"))),
            Arguments.of("io log without the listOut key",
                nodeToolRequest("ioLog", lines -> lines.remove("listOut"))),
            Arguments.of("io log with an empty listOut",
                nodeToolRequest("ioLog", lines -> lines.put("listOut", ""))),
            Arguments.of("io log with a logOut key", nodeToolRequest("ioLog",
                lines -> lines.put("logOut", "%2Fvar%2Fqe-0001%2Fout%2Fwebservice.log"))),
            Arguments.of("io log with a details key",
                nodeToolRequest("ioLog", lines -> lines.put("details", ""))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aRequestWithOpQueueEntryBehavesExactlyAsTheSameRequestWithout(String variant)
        throws Exception {
        Path data = dir.resolve("data.bin");
        Path variables = dir.resolve("variables.wfsp");
        String request = request(lines -> {
            lines.put("variant", variant);
            lines.put("url", encode(variant.equals("SOAP_81") ? URL_81 : URL_90));
            outputs(lines, data, variables);
        });
        FakeInubitScript without = new FakeInubitScript()
            .file(FILE, DATA)
            .file(FILE + ".wfsp", VARIABLES);
        FakeInubitScript with = new FakeInubitScript()
            .file(FILE, DATA)
            .file(FILE + ".wfsp", VARIABLES);

        Run plain = run(without, request, vendor());
        Run withOp = run(with, request.replace("qe=1\n", "qe=1\nop=queueEntry\n"), vendor());

        assertThat(plain.exit()).isZero();
        assertThat(withOp.exit()).isEqualTo(plain.exit());
        assertThat(withOp.out()).isEqualTo(plain.out());
        assertThat(with.calls()).isEqualTo(without.calls());
        assertThat(data).hasBinaryContent(DATA);
        assertThat(variables).hasBinaryContent(VARIABLES);
    }

    // --- the vendor flows (FR-006, FR-007, FR-014, FR-015, D-4) ----------------------------

    @Test
    void soap81ReadsBothPartsThroughLoginDownloadAndLogoutOnly() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .file(FILE, DATA)
            .file(FILE + ".wfsp", VARIABLES);
        Path data = dir.resolve("data.bin");
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> outputs(lines, data, variables)), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("part", "data", "ok", Integer.toString(DATA.length)),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(data).hasBinaryContent(DATA);
        assertThat(variables).hasBinaryContent(VARIABLES);
        assertThat(script.calls()).as("only login, download and logout (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
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
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void soap81WithoutHostnameVerificationOverHttpPassesNoTrustStore() throws Exception {
        FakeInubitScript script = new FakeInubitScript().file(FILE + ".wfsp", VARIABLES);
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> {
            variablesOnly(lines, variables);
            lines.put("url", encode("http://inubit.example.test:8000/ibis/servlet/IBIS"));
            lines.put("trustStore", "");
            lines.put("trustStorePassword", "");
            lines.put("hostnameVerification", "false");
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(script.calls().get(0)).isEqualTo(call("IBISSoapClient.<init>",
            "http://inubit.example.test:8000/ibis/servlet/IBIS?isCli", "null", "null", "null",
            "null", "false"));
    }

    @Test
    void rest90ReadsBothPartsThroughTheFactoryClientWithoutLogout() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .file(FILE, DATA)
            .file(FILE + ".wfsp", VARIABLES);
        Path data = dir.resolve("data.bin");
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> {
            outputs(lines, data, variables);
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "REST_90"),
            List.of("login", "ok"),
            List.of("part", "data", "ok", Integer.toString(DATA.length)),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(data).hasBinaryContent(DATA);
        assertThat(variables).hasBinaryContent(VARIABLES);
        assertThat(script.calls()).as("only login and download (FR-007)").containsExactly(
            call("ConnectionData.setServerURL", URL_90),
            call("ConnectionData.setUserName", USER),
            call("ConnectionData.setPassword", TestSecrets.CONFIGURED_PASSWORD),
            call("ConnectionData.setServerAuth", "true"),
            call("ConnectionData.setServerTrustStoreFile", new File(TRUST_STORE).toString()),
            call("ConnectionData.setServerTrustStorePassword", TestSecrets.TRUST_STORE_PASSWORD),
            call("ConnectionData.setSkipHostnameVerification", "false"),
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("MultipartDownloadThread.<init>", "RestClient", USER, FILE, OBJECT_TYPE,
                "null", "null"),
            call("MultipartDownloadThread.setOutputFile", data.toString()),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("MultipartDownloadThread.<init>", "RestClient", USER, FILE + ".wfsp",
                OBJECT_TYPE, "null", "null"),
            call("MultipartDownloadThread.setOutputFile", variables.toString()),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90OverHttpSetsNoTrustStoreAndSkipsHostnameVerificationWhenDisabled()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().file(FILE + ".wfsp", VARIABLES);
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> {
            variablesOnly(lines, variables);
            lines.put("variant", "REST_90");
            lines.put("url", encode("http://inubit.example.test:8000"));
            lines.put("trustStore", "");
            lines.put("trustStorePassword", "");
            lines.put("hostnameVerification", "false");
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(script.calls()).startsWith(
            call("ConnectionData.setServerURL", "http://inubit.example.test:8000"),
            call("ConnectionData.setUserName", USER),
            call("ConnectionData.setPassword", TestSecrets.CONFIGURED_PASSWORD),
            call("ConnectionData.setServerAuth", "true"),
            call("ConnectionData.setSkipHostnameVerification", "true"),
            call("InubitClientFactory.getRestInstance"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aVariablesOnlyRequestDownloadsOnlyTheVariables(String variant) throws Exception {
        FakeInubitScript script = new FakeInubitScript().file(FILE + ".wfsp", VARIABLES);
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> {
            variablesOnly(lines, variables);
            lines.put("variant", variant);
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "ok"),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(script.calls()).filteredOn(c -> c.name().equals("MultipartDownloadThread.<init>"))
            .extracting(c -> c.arguments().get(2)).containsExactly(FILE + ".wfsp");
        assertThat(variables).hasBinaryContent(VARIABLES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aFailedPartIsReportedWithItsExceptionChainAndTheOtherPartIsStillRead(String variant)
        throws Exception {
        String missing = "@Start@Unable to download@@@" + FILE
            + "@End@acme/workflow/Workflow-0001/error/" + FILE;
        FakeInubitScript script = new FakeInubitScript()
            .fileFails(FILE, new Failure("java.io.IOException", missing),
                new Failure("java.lang.IllegalStateException", "HTTP 404"))
            .file(FILE + ".wfsp", VARIABLES);
        Path variables = dir.resolve("variables.wfsp");

        Run run = run(script, request(lines -> {
            outputs(lines, dir.resolve("data.bin"), variables);
            lines.put("variant", variant);
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "ok"),
            List.of("part", "data", "error",
                "java.io.IOException>java.lang.IllegalStateException", missing + "\nHTTP 404"),
            List.of("part", "variables", "ok", Integer.toString(VARIABLES.length)),
            List.of("end"));
        assertThat(script.calls()).contains(call("MultipartDownloadThread.getException"));
    }

    @Test
    void exceptionMessagesAreCutAtOneThousandCharactersPerLevel() throws Exception {
        String longMessage = "x".repeat(1_500);
        FakeInubitScript script = new FakeInubitScript()
            .loginFails(new Failure("java.io.IOException", longMessage),
                new Failure("java.lang.IllegalStateException", "PKIX path building failed"));

        Run run = run(script, request(lines -> { }), vendor());

        assertThat(frames(run.out())).last().isEqualTo(List.of("login", "error",
            "java.io.IOException>java.lang.IllegalStateException",
            "x".repeat(1_000) + "\nPKIX path building failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aFailedLoginEndsWithExitThreeAndDownloadsNothing(String variant) throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .loginFails(new Failure("java.lang.SecurityException",
                "LoginFailure: password does not match"));

        Run run = run(script, request(lines -> lines.put("variant", variant)), vendor());

        assertThat(run.exit()).isEqualTo(3);
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "error", "java.lang.SecurityException",
                "LoginFailure: password does not match"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"));
        if (variant.equals("SOAP_81")) {
            assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
        }
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void passwordsInVendorMessagesAreNeverPrinted() throws Exception {
        String echo = "login of jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " failed";
        FakeInubitScript loginScript = new FakeInubitScript()
            .loginFails(new Failure("java.io.IOException", echo),
                new Failure("java.io.IOException", TestSecrets.CONFIGURED_PASSWORD));
        FakeInubitScript partScript = new FakeInubitScript()
            .fileFails(FILE + ".wfsp", new Failure("java.io.IOException", echo));
        Path variables = dir.resolve("variables.wfsp");

        Run login = run(loginScript, request(lines -> { }), vendor());
        Run part = run(partScript, request(lines -> variablesOnly(lines, variables)), vendor());

        assertThat(login.exit()).isEqualTo(3);
        assertThat(frames(login.out())).last().isEqualTo(List.of("login", "error",
            "java.io.IOException>java.io.IOException",
            "login of jdoe with ****** and store ****** failed\n******"));
        assertThat(part.exit()).isZero();
        assertThat(frames(part.out())).contains(List.of("part", "variables", "error",
            "java.io.IOException", "login of jdoe with ****** and store ****** failed"));
        for (Run run : List.of(login, part)) {
            TestSecrets.assertNoLeak(run.out());
            TestSecrets.assertNoLeak(decoded(run.out()));
        }
    }

    @Test
    void aPasswordInsideTheTrustStorePasswordLeavesNoPieceOfItUnmasked() throws Exception {
        String password = TestSecrets.TRUST_STORE_PASSWORD.substring(
            TestSecrets.TRUST_STORE_PASSWORD.indexOf("password"));
        FakeInubitScript script = new FakeInubitScript()
            .loginFails(new Failure("java.io.IOException", "store "
                + TestSecrets.TRUST_STORE_PASSWORD + " user " + password));

        Run run = run(script, request(lines -> lines.put("password", encode(password))),
            vendor());

        assertThat(run.exit()).isEqualTo(3);
        assertThat(frames(run.out())).last().isEqualTo(List.of("login", "error",
            "java.io.IOException", "store ****** user ******"));
    }

    @Test
    void aFailureWithoutMessageOrWithoutExceptionIsWrittenWithDashTokens() throws Exception {
        FakeInubitScript loginScript = new FakeInubitScript()
            .loginFails(new Failure("java.io.IOException", null));
        FakeInubitScript partScript = new FakeInubitScript()
            .fileFailsWithoutException(FILE + ".wfsp");
        Path variables = dir.resolve("variables.wfsp");

        Run login = run(loginScript, request(lines -> { }), vendor());
        Run part = run(partScript, request(lines -> variablesOnly(lines, variables)), vendor());

        assertThat(login.exit()).isEqualTo(3);
        assertThat(frames(login.out())).last()
            .isEqualTo(List.of("login", "error", "java.io.IOException", "-"));
        assertThat(part.exit()).isZero();
        assertThat(frames(part.out())).contains(List.of("part", "variables", "error", "-", "-"));
        assertThat(part.out()).as("no empty token, no trailing space").doesNotContain("  ")
            .doesNotContain(" \n");
    }

    @ParameterizedTest
    @MethodSource("failingClientInitializers")
    void aClientClassWhoseStaticInitializerFailsEndsWithLoginErrorAndExitThree(String variant,
        String vendorClass) throws Exception {
        String simpleName = vendorClass.substring(vendorClass.lastIndexOf('.') + 1);
        FakeInubitScript script = new FakeInubitScript().initializerFails(simpleName);

        Run run = run(script, request(lines -> lines.put("variant", variant)),
            reloading(vendorClass));

        assertThat(run.exit()).isEqualTo(3);
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "error",
                "java.lang.ExceptionInInitializerError>java.lang.IllegalStateException",
                "\nstatic initializer of " + simpleName + " failed"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.contains("loginUserCli") || name.contains("logoutUser"));
    }

    static Stream<Arguments> failingClientInitializers() {
        return Stream.of(
            Arguments.of("SOAP_81", "com.inubit.ibis.soap.IBISSoapClient"),
            Arguments.of("REST_90", "com.inubit.ibis.client.rest.InubitClientFactory"));
    }

    @Test
    void aDownloadThreadWhoseStaticInitializerFailsGivesPartErrorsAndStillLogsOut()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .initializerFails("MultipartDownloadThread");

        Run run = run(script, request(lines -> outputs(lines, dir.resolve("data.bin"),
            dir.resolve("variables.wfsp"))),
            reloading("com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"));

        assertThat(run.exit()).isZero();
        List<List<String>> frames = frames(run.out());
        assertThat(frames).hasSize(5);
        assertThat(frames.get(2)).startsWith("part", "data", "error",
            "java.lang.ExceptionInInitializerError>java.lang.IllegalStateException");
        assertThat(frames.get(3)).startsWith("part", "variables", "error");
        assertThat(frames.get(3).get(3)).startsWith("java.lang.NoClassDefFoundError");
        assertThat(frames.get(4)).isEqualTo(List.of("end"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapingErrors")
    void anyOtherThrowableEndsWithExitOneAndPrintsNothing(String label, InputStream in,
        ClassLoader vendor) throws Exception {
        Run run = run(in, vendor);

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.out()).isEmpty();
    }

    static Stream<Arguments> escapingErrors() {
        InputStream failingStdin = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError(TestSecrets.CONFIGURED_PASSWORD);
            }
        };
        ClassLoader failingVendor = new ClassLoader(vendor()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new AssertionError(TestSecrets.TRUST_STORE_PASSWORD);
            }
        };
        return Stream.of(
            Arguments.of("error reading the request", failingStdin, vendor()),
            Arguments.of("error from the vendor class loader", new ByteArrayInputStream(
                request(lines -> { }).getBytes(StandardCharsets.UTF_8)), failingVendor));
    }

    @ParameterizedTest(name = "{0} without {1}")
    @MethodSource("missingVendorApi")
    void aMissingVendorClassEndsWithApiMissingAndExitFourBeforeAnyCall(String variant,
        String hiddenClass) throws Exception {
        FakeInubitScript script = new FakeInubitScript();

        Run run = run(script, request(lines -> lines.put("variant", variant)),
            hiding(hiddenClass));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", hiddenClass));
        assertThat(script.calls()).isEmpty();
        TestSecrets.assertNoLeak(run.out());
    }

    static Stream<Arguments> missingVendorApi() {
        return Stream.of(
            Arguments.of("SOAP_81", "com.inubit.ibis.utils.I_IBISClientInterface"),
            Arguments.of("SOAP_81", "com.inubit.ibis.soap.IBISSoapClient"),
            Arguments.of("SOAP_81", "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"),
            Arguments.of("REST_90", "com.inubit.ibis.configuration.utils.ConnectionData"),
            Arguments.of("REST_90", "com.inubit.ibis.client.rest.InubitClientFactory"),
            Arguments.of("REST_90", "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"));
    }

    @Test
    void withoutAnyVendorClassTheHelperReportsTheClientInterfaceMissing() throws Exception {
        Run run = run(request(lines -> { }), ClassLoader.getPlatformClassLoader());

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(
            List.of("api", "missing", "com.inubit.ibis.utils.I_IBISClientInterface"));
    }

    // --- operation serverLog (feature 021: FR-006, D-2, D-4, D-5) --------------------------

    @Test
    void soap81ServerLogLooksUpItsNodeAndDownloadsTheWebServiceLogFromThatNodeOnly()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG);
        Path logOut = dir.resolve("webservice.log");

        Run run = run(script, serverLogRequest(lines -> lines.put("logOut",
            encode(logOut.toString()))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("part", "log", "ok", Integer.toString(LOG.length)),
            List.of("end"));
        assertThat(logOut).hasBinaryContent(LOG);
        assertThat(script.calls()).as("only login, own node, download and logout (FR-006)")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("ClusterClient.clusterDownload", logOut.toString(), NODE, WEBSERVICE_LOG),
                call("MultipartDownloadThread.start"),
                call("MultipartDownloadThread.exceptionOccured"),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90ServerLogUsesTheFactoryClientAndDoesNotLogOut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG);
        Path logOut = dir.resolve("webservice.log");

        Run run = run(script, serverLogRequest(lines -> {
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
            lines.put("logOut", encode(logOut.toString()));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "REST_90"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("part", "log", "ok", Integer.toString(LOG.length)),
            List.of("end"));
        assertThat(logOut).hasBinaryContent(LOG);
        assertThat(script.calls()).endsWith(
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "RestClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.clusterDownload", logOut.toString(), NODE, WEBSERVICE_LOG),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aFailedNodeLookupIsReportedAndEndsWithoutDownload(String variant) throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .nodeFails(new Failure("java.lang.Exception", "Server not in cluster mode."),
                new Failure("java.lang.IllegalStateException", "HTTP 403"))
            .file(WEBSERVICE_LOG, LOG);
        Path logOut = dir.resolve("webservice.log");

        Run run = run(script, serverLogRequest(lines -> {
            lines.put("variant", variant);
            lines.put("logOut", encode(logOut.toString()));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "ok"),
            List.of("node", "error", "java.lang.Exception>java.lang.IllegalStateException",
                "Server not in cluster mode.\nHTTP 403"),
            List.of("end"));
        assertThat(logOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.contains("clusterDownload")
                || name.startsWith("MultipartDownloadThread"));
        if (variant.equals("SOAP_81")) {
            assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
        }
    }

    @ParameterizedTest
    @EnumSource(NullFrom.class)
    void aNodeLookupReturningNullIsANodeErrorWithoutDownloadAndStillLogsOut(NullFrom call)
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).nodeLookupReturnsNull(call)
            .file(WEBSERVICE_LOG, LOG);
        Path logOut = dir.resolve("webservice.log");

        Run run = run(script, serverLogRequest(lines -> lines.put("logOut",
            encode(logOut.toString()))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error", "-", "the cluster client returned no node or node name"),
            List.of("end"));
        // nothing was thrown, so the frame carries no exception chain
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).contains(
            new HelperFrames.NodeError(List.of(),
                "the cluster client returned no node or node name"));
        assertThat(logOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.contains("clusterDownload")
                || name.startsWith("MultipartDownloadThread"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void aClusterClientWhoseStaticInitializerFailsIsANodeErrorAndStillLogsOut()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().initializerFails("ClusterClient");

        Run run = run(script, serverLogRequest(lines -> { }),
            reloading("com.inubit.ibis.server.cluster.ClusterClient"));

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error",
                "java.lang.ExceptionInInitializerError>java.lang.IllegalStateException",
                "\nstatic initializer of ClusterClient failed"),
            List.of("end"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void aFailedLogDownloadIsReportedWithItsExceptionChain() throws Exception {
        String missing = "File webservice.log not found on " + NODE;
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .fileFails(WEBSERVICE_LOG, new Failure("java.io.IOException", "Download failed"),
                new Failure("java.lang.Exception", missing));

        Run run = run(script, serverLogRequest(lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("part", "log", "error", "java.io.IOException>java.lang.Exception",
                "Download failed\n" + missing),
            List.of("end"));
        assertThat(script.calls()).contains(call("MultipartDownloadThread.getException"))
            .last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void aClusterDownloadThatThrowsItselfIsALogPartError() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG)
            .clusterDownloadFails(new Failure("java.io.IOException", "Connection reset"));

        Run run = run(script, serverLogRequest(lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("part", "log", "error", "java.io.IOException", "Connection reset"),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void aLogDownloadFailingWithoutExceptionIsWrittenWithDashTokens() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .fileFailsWithoutException(WEBSERVICE_LOG);

        Run run = run(script, serverLogRequest(lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).contains(List.of("part", "log", "error", "-", "-"));
    }

    @Test
    void aFailedLoginEndsTheServerLogReadBeforeTheNodeLookup() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG)
            .loginFails(new Failure("java.lang.SecurityException", "LoginFailure"));

        Run run = run(script, serverLogRequest(lines -> { }), vendor());

        assertThat(run.exit()).isEqualTo(3);
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "error", "java.lang.SecurityException", "LoginFailure"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("ClusterClient"));
    }

    @Test
    void passwordsInNodeAndLogDownloadMessagesAreNeverPrinted() throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " refused";
        FakeInubitScript nodeScript = new FakeInubitScript()
            .nodeFails(new Failure("java.io.IOException", echo));
        FakeInubitScript logScript = new FakeInubitScript().node(NODE)
            .fileFails(WEBSERVICE_LOG, new Failure("java.io.IOException", echo));

        Run node = run(nodeScript, serverLogRequest(lines -> { }), vendor());
        Run log = run(logScript, serverLogRequest(lines -> { }), vendor());

        String masked = "user jdoe with ****** and store ****** refused";
        assertThat(frames(node.out())).contains(
            List.of("node", "error", "java.io.IOException", masked));
        assertThat(frames(log.out())).contains(
            List.of("part", "log", "error", "java.io.IOException", masked));
        for (Run run : List.of(node, log)) {
            TestSecrets.assertNoLeak(run.out());
            TestSecrets.assertNoLeak(decoded(run.out()));
        }
    }

    @ParameterizedTest(name = "{0} without {1}")
    @MethodSource("missingServerLogClasses")
    void aServerLogWithoutAVendorClassEndsWithApiMissingAndExitFourBeforeAnyCall(String variant,
        String hiddenClass) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG);

        Run run = run(script, serverLogRequest(lines -> lines.put("variant", variant)),
            hiding(hiddenClass));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", hiddenClass));
        assertThat(script.calls()).isEmpty();
    }

    static Stream<Arguments> missingServerLogClasses() {
        return Stream.of(
            Arguments.of("SOAP_81", "com.inubit.ibis.utils.I_IBISClientInterface"),
            Arguments.of("SOAP_81", "com.inubit.ibis.soap.IBISSoapClient"),
            Arguments.of("REST_90", "com.inubit.ibis.configuration.utils.ConnectionData"),
            Arguments.of("REST_90", "com.inubit.ibis.client.rest.InubitClientFactory"),
            Arguments.of("SOAP_81",
                "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"),
            Arguments.of("SOAP_81", "com.inubit.ibis.server.cluster.ClusterClient"),
            Arguments.of("REST_90", "com.inubit.ibis.server.cluster.ClusterClient"),
            Arguments.of("SOAP_81", "com.inubit.ibis.server.cluster.data.Node"),
            Arguments.of("SOAP_81", "com.inubit.ibis.server.cache.NodeName"),
            Arguments.of("SOAP_81", "com.inubit.ibis.common.CurrentUser"));
    }

    @Test
    void aServerLogChecksTheDownloadThreadBeforeTheClusterClassesAsInFeature021()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG);

        Run run = run(script, serverLogRequest(lines -> { }),
            hiding("com.inubit.ibis.configuration.utils.io.MultipartDownloadThread",
                "com.inubit.ibis.server.cache.NodeName",
                "com.inubit.ibis.server.cluster.ClusterClient"));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing",
            "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"));
        assertThat(script.calls()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingServerLogMembers")
    void aServerLogWithoutAVendorMemberEndsWithApiMissingAndExitFourBeforeAnyCall(
        String missing, String className, String source) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).file(WEBSERVICE_LOG, LOG);

        Run run = run(script, serverLogRequest(lines -> { }), replacing(className, source));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", missing));
        assertThat(script.calls()).isEmpty();
    }

    static Stream<Arguments> missingServerLogMembers() {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        String constructor = "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c,"
            + " String u, String id, String g) { }";
        String getNode = "public com.inubit.ibis.server.cluster.data.Node getNode() {"
            + " return null; }";
        String clusterDownload = "public com.inubit.ibis.configuration.utils.io"
            + ".MultipartDownloadThread clusterDownload(java.io.File f,"
            + " com.inubit.ibis.server.cache.NodeName n, String s) { return null; }";
        String currentUser = "com.inubit.ibis.common.CurrentUser";
        String clientUid = "public static String getClientUID() { return null; }";
        String group = "public static String getUsedAdditionalUserGroup() { return null; }";
        return Stream.of(
            Arguments.of(cluster + ".<init>(com.inubit.ibis.utils.I_IBISClientInterface,"
                + "java.lang.String,java.lang.String,java.lang.String)", cluster,
                stub(cluster, "public ClusterClient(Object c, String u, String id, String g) { }",
                    getNode, clusterDownload)),
            Arguments.of(cluster + ".getNode()", cluster,
                stub(cluster, constructor, clusterDownload)),
            Arguments.of(cluster + ".clusterDownload(java.io.File,"
                + "com.inubit.ibis.server.cache.NodeName,java.lang.String)", cluster,
                stub(cluster, constructor, getNode, clusterDownload.replace(
                    "com.inubit.ibis.server.cache.NodeName n", "String n"))),
            Arguments.of("com.inubit.ibis.server.cluster.data.Node.getNodeName()",
                "com.inubit.ibis.server.cluster.data.Node",
                stub("com.inubit.ibis.server.cluster.data.Node")),
            Arguments.of("com.inubit.ibis.server.cache.NodeName.getName()",
                "com.inubit.ibis.server.cache.NodeName",
                stub("com.inubit.ibis.server.cache.NodeName")),
            Arguments.of(currentUser + ".getClientUID()", currentUser, stub(currentUser, group)),
            Arguments.of(currentUser + ".getUsedAdditionalUserGroup()", currentUser,
                stub(currentUser, clientUid)));
    }

    // --- operation ownNode (feature 024: FR-004, FR-042, D-2, D-3) --------------------------

    @Test
    void soap81OwnNodeLogsInLooksUpTheAnsweringNodeAndLogsOutOnly() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("ownNode", lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE), new HelperFrames.End());
        assertThat(script.calls()).as("only login, own node and logout (FR-007)")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90OwnNodeUsesTheFactoryClientAndDoesNotLogOut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("ownNode", lines -> {
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "REST_90"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("end"));
        assertThat(script.calls()).endsWith(
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "RestClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP_81", "REST_90"})
    void aFailedOwnNodeLookupIsANodeErrorFollowedByEndWithMaskedPasswords(String variant)
        throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " refused";
        FakeInubitScript script = new FakeInubitScript()
            .nodeFails(new Failure("java.lang.Exception", "Server not in cluster mode."),
                new Failure("java.io.IOException", echo));

        Run run = run(script, nodeToolRequest("ownNode", lines -> lines.put("variant", variant)),
            vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", variant),
            List.of("login", "ok"),
            List.of("node", "error", "java.lang.Exception>java.io.IOException",
                "Server not in cluster mode.\nuser jdoe with ****** and store ****** refused"),
            List.of("end"));
        if (variant.equals("SOAP_81")) {
            assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
        }
        TestSecrets.assertNoLeak(run.out());
        TestSecrets.assertNoLeak(decoded(run.out()));
    }

    @ParameterizedTest
    @EnumSource(NullFrom.class)
    void anOwnNodeLookupReturningNullIsANodeErrorAndStillLogsOut(NullFrom call)
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).nodeLookupReturnsNull(call);

        Run run = run(script, nodeToolRequest("ownNode", lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error", "-", "the cluster client returned no node or node name"),
            List.of("end"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void aFailedLoginEndsTheOwnNodeLookupBeforeTheClusterClient() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .loginFails(new Failure("java.lang.SecurityException", "LoginFailure"));

        Run run = run(script, nodeToolRequest("ownNode", lines -> { }), vendor());

        assertThat(run.exit()).isEqualTo(3);
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "error", "java.lang.SecurityException", "LoginFailure"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("ClusterClient"));
    }

    @Test
    void ownNodeChecksOnlyTheMembersItCallsSoItRunsWithoutTheDownloadThread() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("ownNode", lines -> { }),
            hiding("com.inubit.ibis.configuration.utils.io.MultipartDownloadThread"));

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).contains(List.of("node", "ok", NODE), List.of("end"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.inubit.ibis.server.cluster.ClusterClient",
        "com.inubit.ibis.server.cluster.data.Node", "com.inubit.ibis.server.cache.NodeName",
        "com.inubit.ibis.common.CurrentUser"})
    void ownNodeWithoutAClusterClassEndsWithApiMissingAndExitFourBeforeAnyCall(
        String hiddenClass) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("ownNode", lines -> { }), hiding(hiddenClass));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", hiddenClass));
        assertThat(script.calls()).isEmpty();
    }

    // --- operation clusterNodes (feature 024: FR-010, FR-011, D-2, D-7) ----------------------

    @Test
    void soap81ClusterNodesListsTheMembersInOrderAndAMemberWithoutAnswerWithDashes()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).members(
            new Member(NODE, "ACTIVE", "9.0.10", "zone-a", "node-id-0001", 3_600_000L),
            Member.unanswered(NODE_2));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("member", NODE, "ACTIVE", "9.0.10", "zone-a", "node-id-0001", "3600000"),
            List.of("member", NODE_2, "-", "-", "-", "-", "-"),
            List.of("members", "ok", "2"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE),
            new HelperFrames.Member(NODE, "ACTIVE", "9.0.10", "zone-a", "node-id-0001",
                Optional.of(3_600_000L)),
            new HelperFrames.Member(NODE_2, "", "", "", "", Optional.empty()),
            new HelperFrames.MembersOk(2), new HelperFrames.End());
        assertThat(script.calls()).as("login, own node, node list and logout (FR-007)")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("ClusterClient.getNodes"),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90ClusterNodesUsesTheFactoryClientAndDoesNotLogOut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(new Member(NODE, "ACTIVE", "8.1.17", null, "node-id-0001", 0L));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> {
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).contains(
            List.of("member", NODE, "ACTIVE", "8.1.17", "-", "node-id-0001", "0"),
            List.of("members", "ok", "1"), List.of("end"));
        assertThat(script.calls()).endsWith(
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "RestClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodes"));
    }

    /**
     * The helper writes the status constant's {@code name()} unchanged; classifying a text the
     * server does not know is the server's (D-7, the T019 fixture {@code unknown_status}).
     */
    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "MAINTENANCE", "OFFLINE"})
    void theStatusIsTheNameOfTheVendorConstantUnchanged(String status) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(new Member(NODE, status, "9.0.10", "zone-a", "node-id-0001", 5L));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        assertThat(frames(run.out())).contains(
            List.of("member", NODE, status, "9.0.10", "zone-a", "node-id-0001", "5"));
    }

    /** A member without status did not answer: its uptime (a primitive, 0) is no uptime. */
    @Test
    void aMemberWithoutStatusHasNoUptimeWhatEverTheNodeCarries() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE).members(
            new Member(NODE_2, null, "9.0.10", null, null, 1_234L),
            Member.unanswered("inubit-node-03"),
            new Member(NODE, "OFFLINE", null, null, null, 0L));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        assertThat(frames(run.out())).filteredOn(frame -> frame.get(0).equals("member"))
            .containsExactly(
                List.of("member", NODE_2, "-", "9.0.10", "-", "-", "-"),
                List.of("member", "inubit-node-03", "-", "-", "-", "-", "-"),
                List.of("member", NODE, "OFFLINE", "-", "-", "-", "0"));
    }

    @Test
    void moreThanTwoHundredMembersGiveTwoHundredMemberFramesAndTheirTotal() throws Exception {
        Member[] members = new Member[201];
        for (int i = 0; i < members.length; i++) {
            members[i] = Member.unanswered(String.format("inubit-node-%03d", i));
        }
        FakeInubitScript script = new FakeInubitScript().node(NODE).members(members);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        List<List<String>> memberFrames = framesStartingWith(run.out(), "member");
        assertThat(memberFrames).hasSize(200);
        assertThat(memberFrames.get(199).get(1)).isEqualTo("inubit-node-199");
        assertThat(frames(run.out())).endsWith(List.of("members", "ok", "201"), List.of("end"));
    }

    /**
     * Every member token is cut at 200 characters and, whole characters only, at 600 bytes of its
     * percent-encoded form: 200 ASCII characters are never cut by the byte bound (3 bytes each at
     * most), non-ASCII ones are (D-2).
     */
    @Test
    void memberTokensAreCutAtTwoHundredCharactersAndSixHundredEncodedBytes() throws Exception {
        String emoji = "\uD83D\uDE00";
        FakeInubitScript script = new FakeInubitScript().node(NODE).members(
            new Member("n".repeat(201), "ACTIVE", " ".repeat(200), "\u00fc".repeat(250),
                "\u20ac".repeat(250), 1L),
            new Member(NODE, "ACTIVE", emoji.repeat(150), "z".repeat(200), "%".repeat(201), 1L));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        assertThat(framesStartingWith(run.out(), "member")).containsExactly(
            List.of("member", "n".repeat(200), "ACTIVE", " ".repeat(200), "\u00fc".repeat(100),
                "\u20ac".repeat(66), "1"),
            List.of("member", NODE, "ACTIVE", emoji.repeat(50), "z".repeat(200),
                "%".repeat(200), "1"));
        for (String line : run.out().split("\n")) {
            for (String token : line.split(" ")) {
                assertThat(token.length()).as("an encoded token").isLessThanOrEqualTo(600);
            }
        }
    }

    @Test
    void detailsOfAListedMemberAreItsNodeInformationFlattenedInTheReceivedOrder()
        throws Exception {
        Map<String, Object> jdk = new LinkedHashMap<>();
        jdk.put("Vendor", "Eclipse Adoptium");
        jdk.put("Version", "17.0.12");
        Map<String, Object> information = new LinkedHashMap<>();
        information.put("ServerVersion", "9.0.10");
        information.put("JDK", jdk);
        information.put("InternalNodeAddress", "node-02.example.test:5701");
        information.put("LogDirectory", null);
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE), Member.unanswered(NODE_2))
            .nodeInformation(NODE_2, information);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE_2)), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("members", "ok", "2"),
            List.of("detail", "ServerVersion", "9.0.10"),
            List.of("detail", "JDK.Vendor", "Eclipse Adoptium"),
            List.of("detail", "JDK.Version", "17.0.12"),
            List.of("detail", "InternalNodeAddress", "node-02.example.test:5701"),
            List.of("detail", "LogDirectory", "-"),
            List.of("details", "ok", "5", "false"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames())
            .contains(new HelperFrames.Detail("JDK.Vendor", "Eclipse Adoptium", false),
                new HelperFrames.DetailsOk(5, false));
        assertThat(script.calls()).endsWith(
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodes"),
            call("ClusterClient.getNode", NODE_2),
            call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void detailsOfANameThatIsNoListedMemberAreUnknownAndNotAskedFor() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE), Member.unanswered(NODE_2))
            .nodeInformation("inubit-node-09", Map.of("ServerVersion", "9.0.10"));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            "inubit-node-09")), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("members", "ok", "2"), List.of("details", "unknown"), List.of("end"));
        assertThat(script.calls()).as("no getNode(NodeName)").endsWith(
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodes"),
            call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void aKeyOverTwoHundredOrAValueOverFourThousandCharactersIsCutAndMarked() throws Exception {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("i".repeat(60), "inner");
        Map<String, Object> information = new LinkedHashMap<>();
        information.put("k".repeat(201), "v");
        information.put("long", "v".repeat(4_001));
        information.put("k".repeat(200), "v".repeat(4_000));
        information.put("o".repeat(150), outer);
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE)).nodeInformation(NODE, information);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor());

        assertThat(framesStartingWith(run.out(), "detail")).containsExactly(
            List.of("detail", "k".repeat(200), "v", "cut"),
            List.of("detail", "long", "v".repeat(4_000), "cut"),
            List.of("detail", "k".repeat(200), "v".repeat(4_000)),
            List.of("detail", ("o".repeat(150) + "." + "i".repeat(60)).substring(0, 200),
                "inner", "cut"));
        assertThat(frames(run.out())).contains(List.of("details", "ok", "4", "false"));
    }

    /**
     * The encoded {@code detail} frames (whole lines) together stay within 256 KiB: the helper
     * stops before the frame that would exceed it and reports the details as truncated (D-2).
     */
    @Test
    void detailFramesStopBeforeTheOneThatWouldExceedTheBudget() throws Exception {
        Map<String, Object> information = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            information.put(String.format("key-%03d", i), "\u20ac".repeat(4_000));
        }
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE)).nodeInformation(NODE, information);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor());

        List<String> detailLines = Arrays.stream(run.out().split("\n"))
            .filter(line -> line.startsWith("#QE1:" + NONCE + " detail ")).toList();
        int bytes = detailLines.stream().mapToInt(line -> line.length() + 1).sum();
        int oneFrame = detailLines.get(0).length() + 1;
        assertThat(bytes).isLessThanOrEqualTo(256 * 1024).isGreaterThan(256 * 1024 - oneFrame);
        assertThat(frames(run.out())).contains(
            List.of("details", "ok", Integer.toString(detailLines.size()), "true"));
        assertThat(detailLines.size()).isLessThan(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501})
    void atMostFiveHundredDetailsAreWrittenTruncatedOnlyWhenThereAreMore(int entries)
        throws Exception {
        Map<String, Object> information = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            information.put(String.format("key-%03d", i), "v");
        }
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE)).nodeInformation(NODE, information);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor());

        assertThat(framesStartingWith(run.out(), "detail")).hasSize(500);
        assertThat(frames(run.out())).contains(
            List.of("details", "ok", "500", Boolean.toString(entries > 500)));
    }

    /**
     * The largest answer: 200 members whose tokens are non-ASCII (9 encoded bytes per character)
     * and node information up to the detail budget; the whole stdout stays below the 1 MiB cap
     * of 020 and parses.
     */
    @Test
    void theLargestClusterNodesAnswerWithNonAsciiTokensStaysBelowTheOneMibCap() throws Exception {
        String euros = "\u20ac".repeat(250);
        Member[] members = new Member[201];
        // the largest uptime the frame parser takes (18 digits)
        long uptime = 999_999_999_999_999_999L;
        members[0] = new Member(NODE, "MAINTENANCE", euros, euros, euros, uptime);
        for (int i = 1; i < members.length; i++) {
            members[i] = new Member(euros + i, "OFFLINE", euros, euros, euros, uptime);
        }
        Map<String, Object> information = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) {
            information.put("\u20ac".repeat(200) + i, "\u20ac".repeat(4_000));
        }
        FakeInubitScript script = new FakeInubitScript().node(NODE).members(members)
            .nodeInformation(NODE, information);

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor());

        assertThat(run.out().getBytes(StandardCharsets.UTF_8).length)
            .isLessThan(ProcessRun.MAX_OUTPUT_BYTES);
        HelperFrames parsed = HelperFrames.parse(NONCE, run.out());
        assertThat(parsed.problem()).isEmpty();
        assertThat(parsed.ended()).isTrue();
        assertThat(parsed.frames()).filteredOn(HelperFrames.Member.class::isInstance)
            .hasSize(200);
        assertThat(parsed.frames()).filteredOn(HelperFrames.DetailsOk.class::isInstance)
            .map(frame -> ((HelperFrames.DetailsOk) frame).truncated()).containsExactly(true);
    }

    @Test
    void aFailedNodeListIsAMembersErrorWithMaskedPasswordsAndNoDetails() throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " refused";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .membersFail(new Failure("java.lang.IllegalStateException", "no cluster"),
                new Failure("java.io.IOException", echo))
            .nodeInformation(NODE, Map.of("ServerVersion", "9.0.10"));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("members", "error", "java.lang.IllegalStateException>java.io.IOException",
                "no cluster\nuser jdoe with ****** and store ****** refused"),
            List.of("end"));
        assertThat(script.calls()).endsWith(
            call("ClusterClient.getNodes"), call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
        TestSecrets.assertNoLeak(decoded(run.out()));
    }

    @Test
    void aFailedNodeInformationRequestIsADetailsErrorAfterTheMembers() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE), Member.unanswered(NODE_2))
            .nodeInformationFails(NODE_2, new Failure("java.io.IOException",
                "node inubit-node-02 did not answer"));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE_2)), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("members", "ok", "2"),
            List.of("details", "error", "java.io.IOException",
                "node inubit-node-02 did not answer"),
            List.of("end"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    /** A node list that is {@code null} is a failed one, as a node lookup without a node. */
    @Test
    void aNodeListReturningNullIsAMembersErrorWithoutExceptionChainAndStillLogsOut()
        throws Exception {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        ClassLoader vendor = replacing(cluster, "package com.inubit.ibis.server.cluster;\n"
            + "import com.inubit.ibis.server.cache.NodeName;\n"
            + "import com.inubit.ibis.server.cluster.data.Node;\n"
            + "import com.inubit.ibis.server.cluster.data.NodeList;\n"
            + "public class ClusterClient {\n"
            + "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c, String u,"
            + " String id, String g) { }\n"
            + "public Node getNode() { return new Node(NodeName.nodeName(\"" + NODE + "\")); }\n"
            + "public NodeList getNodes() { return null; }\n"
            + "public Node getNode(NodeName n) { return null; }\n"
            + "}\n");
        FakeInubitScript script = new FakeInubitScript();

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> lines.put("details",
            NODE)), vendor);

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("members", "error", "-", "the cluster client returned no node list"),
            List.of("end"));
        assertThat(script.calls()).last().isEqualTo(call("IBISSoapClient.logoutUser", USER));
    }

    /** A named node without node information (a member that did not answer) is no exception. */
    @ParameterizedTest
    @ValueSource(strings = {"null", "new Node(n)"})
    void aNamedNodeWithoutNodeInformationIsADetailsErrorWithoutExceptionChain(String answer)
        throws Exception {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        ClassLoader vendor = replacing(cluster, "package com.inubit.ibis.server.cluster;\n"
            + "import com.inubit.ibis.server.cache.NodeName;\n"
            + "import com.inubit.ibis.server.cluster.data.Node;\n"
            + "import com.inubit.ibis.server.cluster.data.NodeList;\n"
            + "public class ClusterClient {\n"
            + "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c, String u,"
            + " String id, String g) { }\n"
            + "public Node getNode() { return new Node(NodeName.nodeName(\"" + NODE + "\")); }\n"
            + "public NodeList getNodes() { NodeList l = new NodeList();"
            + " l.add(new Node(NodeName.nodeName(\"" + NODE + "\"))); return l; }\n"
            + "public Node getNode(NodeName n) { return " + answer + "; }\n"
            + "}\n");

        Run run = run(new FakeInubitScript(), nodeToolRequest("clusterNodes",
            lines -> lines.put("details", NODE)), vendor);

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("member", NODE, "-", "-", "-", "-", "-"),
            List.of("members", "ok", "1"),
            List.of("details", "error", "-", "the cluster client returned no node information"),
            List.of("end"));
    }

    @Test
    void aFailedOwnNodeLookupEndsTheClusterNodesReadBeforeTheNodeList() throws Exception {
        FakeInubitScript script = new FakeInubitScript().members(Member.unanswered(NODE))
            .nodeFails(new Failure("java.lang.Exception", "Server not in cluster mode."));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error", "java.lang.Exception", "Server not in cluster mode."),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name)
            .doesNotContain("ClusterClient.getNodes");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingClusterNodesMembers")
    void clusterNodesWithoutAVendorMemberEndsWithApiMissingAndExitFourBeforeAnyCall(
        String missing, String className, String source) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .members(Member.unanswered(NODE));

        Run run = run(script, nodeToolRequest("clusterNodes", lines -> { }),
            replacing(className, source));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", missing));
        assertThat(script.calls()).isEmpty();
    }

    static Stream<Arguments> missingClusterNodesMembers() {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        String constructor = "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c,"
            + " String u, String id, String g) { }";
        String getNode = "public com.inubit.ibis.server.cluster.data.Node getNode() {"
            + " return null; }";
        String getNodes = "public com.inubit.ibis.server.cluster.data.NodeList getNodes() {"
            + " return null; }";
        String getNamedNode = "public com.inubit.ibis.server.cluster.data.Node getNode("
            + "com.inubit.ibis.server.cache.NodeName n) { return null; }";
        String nodeName = "com.inubit.ibis.server.cache.NodeName";
        String information = "com.inubit.ibis.server.datafacade.NodeInformation";
        return Stream.of(
            Arguments.of(cluster + ".getNodes()", cluster,
                stub(cluster, constructor, getNode, getNamedNode)),
            Arguments.of(cluster + ".getNode(" + nodeName + ")", cluster,
                stub(cluster, constructor, getNode, getNodes)),
            Arguments.of(nodeName + ".nodeName(java.lang.String)", nodeName,
                stub(nodeName, "public String getName() { return null; }")),
            Arguments.of(information + ".getNodeInformationMap()", information,
                stub(information)));
    }

    // --- operation nodeConfig (feature 024: FR-030 … FR-032, SC-003, D-10) ------------------

    @Test
    void soap81NodeConfigReadsEachRequestedNameOfTheOwnNodeInTheRequestedOrder()
        throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property("OperationMode", new Property(String.class, "CLUSTER", "Operation mode",
                "How the node runs", "Cluster", false, false))
            .property("socketTimeoutInMillisecs", new Property(Integer.class, "60000",
                "Socket timeout", "Socket timeout in milliseconds", "Network", true, true));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            "socketTimeoutInMillisecs%2COperationMode")), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("config", "socketTimeoutInMillisecs", "ok", "Integer", "true", "true",
                "Socket timeout", "Network", "Socket timeout in milliseconds", "60000"),
            List.of("config", "OperationMode", "ok", "String", "false", "false",
                "Operation mode", "Cluster", "How the node runs", "CLUSTER"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE),
            new HelperFrames.ConfigOk("socketTimeoutInMillisecs", "Integer", true, true,
                "Socket timeout", "Network", "Socket timeout in milliseconds", "60000"),
            new HelperFrames.ConfigOk("OperationMode", "String", false, false,
                "Operation mode", "Cluster", "How the node runs", "CLUSTER"),
            new HelperFrames.End());
        assertThat(script.calls()).as("login, own node, one request per name and logout (FR-007)")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("ClusterClient.getNodeConfigurationProperty", NODE,
                    "socketTimeoutInMillisecs"),
                call("ClusterClient.getNodeConfigurationProperty", NODE, "OperationMode"),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90NodeConfigUsesTheFactoryClientAndDoesNotLogOut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property("NodeName", new Property(String.class, NODE, "Node name", "", "Cluster",
                false, false));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> {
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
            lines.put("names", "NodeName");
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("config", "NodeName", "ok", "String", "false", "false", "Node name",
                "Cluster", "-", NODE),
            List.of("end"));
        assertThat(script.calls()).endsWith(
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "RestClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodeConfigurationProperty", NODE, "NodeName"));
    }

    /** Each of the 20 names of the helper's own list is sent as it is (D-10). */
    @ParameterizedTest
    @MethodSource("nodeConfigNames")
    void eachAllowlistedNameIsRequestedFromTheOwnNode(String name) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property(name, new Property(String.class, "value-0001", "Display", "Description",
                "Category", true, false));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names", name)),
            vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).contains(List.of("config", name, "ok", "String", "true",
            "false", "Display", "Category", "Description", "value-0001"));
        assertThat(script.calls()).filteredOn(c -> c.name().startsWith("ClusterClient.get"))
            .containsExactly(call("ClusterClient.getNode"),
                call("ClusterClient.getNodeConfigurationProperty", NODE, name));
    }

    static Stream<String> nodeConfigNames() {
        return NODE_CONFIG_NAMES.stream();
    }

    @Test
    void allTwentyNamesInOneRequestAreEachRequestedOnce() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            String.join("%2C", NODE_CONFIG_NAMES))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(framesStartingWith(run.out(), "config")).hasSize(20);
        assertThat(script.calls()).filteredOn(c ->
                c.name().equals("ClusterClient.getNodeConfigurationProperty"))
            .extracting(c -> c.arguments().get(1)).containsExactlyElementsOf(NODE_CONFIG_NAMES);
    }

    /**
     * A secret-holding name or any other text outside the helper's list is a malformed request:
     * exit 2 and no vendor call at all, not even the client creation (SC-003, D-10).
     */
    @ParameterizedTest
    @ValueSource(strings = {"Database", "ClusterPassword", "initialRootUserPassword",
        "IdentityProvider", "IdentityProviderConfiguration", "RuntimeDataBackupStore",
        "ClusterCacheTaskDataModesConfig", "MaxEntriesInMemoryLimit", "NodeName%2CDatabase",
        "ClusterPassword%2CNodeName", "nodename", "NODENAME", "Node%20Name", "%20NodeName",
        "NodeName%00", "Node%C2%ADName", "%2A", "..%2FNodeName", "NodeName%3BDatabase"})
    void aNameOutsideTheListEndsWithExitTwoAndNoVendorCallAtAll(String names) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property("Database", new Property(String.class, "jdbc:fake://db.example.test/ibis",
                "Database", "", "Database", true, false))
            .property("ClusterPassword", new Property(String.class, "fake-cluster-secret",
                "Cluster password", "", "Cluster", true, false))
            .property("NodeName", new Property(String.class, NODE, "Node name", "", "Cluster",
                false, false));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names", names)),
            vendor());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).as("no value is echoed").isEmpty();
        assertThat(script.calls()).as("nothing is sent").isEmpty();
    }

    /**
     * {@code null} is a property the node does not have (US3-4); a failed request is that
     * name's error, with the passwords masked, and the remaining names are still read.
     */
    @Test
    void anAbsentAndAFailingNameDoNotStopTheOthers() throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " refused";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .propertyFails("ClusterName", new Failure("java.lang.IllegalStateException",
                "no property"), new Failure("java.io.IOException", echo))
            .property("ZoneName", new Property(String.class, "zone-a", "Zone", "Zone name",
                "Cluster", true, false));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            "noOfRetries%2CClusterName%2CZoneName")), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("config", "noOfRetries", "absent"),
            List.of("config", "ClusterName", "error",
                "java.lang.IllegalStateException>java.io.IOException",
                "no property\nuser jdoe with ****** and store ****** refused"),
            List.of("config", "ZoneName", "ok", "String", "true", "false", "Zone", "Cluster",
                "Zone name", "zone-a"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).contains(
            new HelperFrames.ConfigAbsent("noOfRetries"),
            new HelperFrames.ConfigError("ClusterName",
                List.of("java.lang.IllegalStateException", "java.io.IOException"),
                "no property\nuser jdoe with ****** and store ****** refused"));
        assertThat(script.calls()).extracting(Call::name).containsSubsequence(
            "ClusterClient.getNodeConfigurationProperty",
            "ClusterClient.getNodeConfigurationProperty",
            "ClusterClient.getNodeConfigurationProperty", "IBISSoapClient.logoutUser");
        TestSecrets.assertNoLeak(run.out());
        TestSecrets.assertNoLeak(decoded(run.out()));
    }

    /** The texts are cut at 4,000 characters; no type, no texts are written as {@code -}. */
    @Test
    void textsAreCutAtFourThousandCharactersAndMissingOnesAreDashes() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property("ClusterDebug", new Property(Boolean.class, "v".repeat(4_001),
                "d".repeat(4_001), "s".repeat(4_000), "c".repeat(4_001), true, false))
            .property("ClusterName", new Property(null, null, null, null, null, false, true));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            "ClusterDebug%2CClusterName")), vendor());

        assertThat(framesStartingWith(run.out(), "config")).containsExactly(
            List.of("config", "ClusterDebug", "ok", "Boolean", "true", "false", "d".repeat(4_000),
                "c".repeat(4_000), "s".repeat(4_000), "v".repeat(4_000)),
            List.of("config", "ClusterName", "ok", "-", "false", "true", "-", "-", "-", "-"));
    }

    /** The byte bound never cuts 4,000 ASCII characters, even those encoded as 3 bytes each. */
    @Test
    void fourThousandAsciiCharactersOfThreeEncodedBytesEachAreNeverCut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .property("ClusterDebug", new Property(Boolean.class, " ".repeat(4_000),
                "%".repeat(4_000), " ".repeat(4_000), "%".repeat(4_000), true, false));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            "ClusterDebug")), vendor());

        assertThat(framesStartingWith(run.out(), "config")).containsExactly(
            List.of("config", "ClusterDebug", "ok", "Boolean", "true", "false", "%".repeat(4_000),
                "%".repeat(4_000), " ".repeat(4_000), " ".repeat(4_000)));
    }

    /** Twenty properties with the longest non-ASCII texts keep the stdout below its 1 MiB cap. */
    @Test
    void theLargestNodeConfigAnswerWithNonAsciiTextsStaysBelowTheOneMibCap() throws Exception {
        String euros = "€".repeat(4_000);
        FakeInubitScript script = new FakeInubitScript().node(NODE);
        for (String name : NODE_CONFIG_NAMES) {
            script.property(name, new Property(String.class, euros, euros, euros, euros, true,
                true));
        }

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> lines.put("names",
            String.join("%2C", NODE_CONFIG_NAMES))), vendor());

        assertThat(run.out().getBytes(StandardCharsets.UTF_8).length)
            .isLessThan(ProcessRun.MAX_OUTPUT_BYTES);
        HelperFrames parsed = HelperFrames.parse(NONCE, run.out());
        assertThat(parsed.problem()).isEmpty();
        assertThat(parsed.ended()).isTrue();
        assertThat(parsed.frames()).filteredOn(HelperFrames.ConfigOk.class::isInstance)
            .hasSize(20);
    }

    /**
     * The {@code NodeName} each request names is the very object {@code getNode()} returned;
     * the vendor class needs no {@code setNodeConfigurationProperty}, as the helper never
     * looks it up (FR-032).
     */
    @Test
    void theNodeNameSentIsTheOneTheOwnNodeLookupReturned() throws Exception {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        ClassLoader vendor = replacing(cluster, "package com.inubit.ibis.server.cluster;\n"
            + "import com.inubit.ibis.server.cache.NodeName;\n"
            + "import com.inubit.ibis.server.cluster.data.Node;\n"
            + "import com.inubit.ibis.utils.property.type.IBISProperty;\n"
            + "public class ClusterClient {\n"
            + "private final NodeName own = NodeName.nodeName(\"" + NODE + "\");\n"
            + "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c, String u,"
            + " String id, String g) { }\n"
            + "public Node getNode() { return new Node(own); }\n"
            + "public IBISProperty getNodeConfigurationProperty(NodeName n, String name) {\n"
            + "  if (n != own) { throw new IllegalArgumentException(\"another NodeName\"); }\n"
            + "  return new IBISProperty(name, String.class, \"same\", \"\", \"\", \"\", false,"
            + " false);\n"
            + "}\n"
            + "}\n");

        Run run = run(new FakeInubitScript(), nodeToolRequest("nodeConfig", lines -> { }),
            vendor);

        assertThat(run.exit()).isZero();
        assertThat(framesStartingWith(run.out(), "config")).containsExactly(
            List.of("config", "OperationMode", "ok", "String", "false", "false", "-", "-", "-",
                "same"),
            List.of("config", "NodeName", "ok", "String", "false", "false", "-", "-", "-",
                "same"));
    }

    @Test
    void aFailedOwnNodeLookupEndsTheNodeConfigReadBeforeAnyProperty() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .nodeFails(new Failure("java.lang.Exception", "Server not in cluster mode."));

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> { }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error", "java.lang.Exception", "Server not in cluster mode."),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name)
            .doesNotContain("ClusterClient.getNodeConfigurationProperty")
            .last().isEqualTo("IBISSoapClient.logoutUser");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingNodeConfigMembers")
    void nodeConfigWithoutAVendorMemberEndsWithApiMissingAndExitFourBeforeAnyCall(
        String missing, String className, String source) throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE);

        Run run = run(script, nodeToolRequest("nodeConfig", lines -> { }),
            replacing(className, source));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", missing));
        assertThat(script.calls()).isEmpty();
    }

    static Stream<Arguments> missingNodeConfigMembers() {
        String cluster = "com.inubit.ibis.server.cluster.ClusterClient";
        String constructor = "public ClusterClient(com.inubit.ibis.utils.I_IBISClientInterface c,"
            + " String u, String id, String g) { }";
        String getNode = "public com.inubit.ibis.server.cluster.data.Node getNode() {"
            + " return null; }";
        String property = "com.inubit.ibis.utils.property.type.IBISProperty";
        List<String> getters = List.of("public Class<?> getType() { return null; }",
            "public String getStringValue() { return null; }",
            "public String getDisplayName() { return null; }",
            "public String getShortDescription() { return null; }",
            "public String getCategory() { return null; }",
            "public boolean isEditable() { return false; }",
            "public boolean isDeprecated() { return false; }");
        Stream<Arguments> missingGetters = getters.stream().map(getter -> {
            String name = getter.replaceAll(".* (\\w+)\\(.*", "$1");
            return Arguments.of(property + "." + name + "()", property, stub(property,
                getters.stream().filter(other -> !other.equals(getter))
                    .toArray(String[]::new)));
        });
        return Stream.concat(Stream.of(
            Arguments.of(cluster + ".getNodeConfigurationProperty("
                + "com.inubit.ibis.server.cache.NodeName,java.lang.String)", cluster,
                stub(cluster, constructor, getNode))), missingGetters);
    }

    // --- operation ioLog (feature 024: FR-020 … FR-023, D-2, D-8, D-9) ---------------------

    @Test
    void soap81IoLogListsTheEntriesOfTheRunIntoTheListFileInTheAnswersOrder() throws Exception {
        List<String> entries = List.of("P-0001_a@@@Orders(Module-0001)@@@Input.1",
            "P-0001_a@@@Orders(Module-0001)@@@Output.1",
            "P-0001_a@@@Bestellprüfung Größe(Module-0002)@@@Input.1");
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, String.join("###", entries));
        Path listOut = dir.resolve("io.list");

        Run run = run(script, nodeToolRequest("ioLog", lines -> lines.put("listOut",
            encode(listOut.toString()))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("io", "list", "ok", "3"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE), new HelperFrames.IoListOk(3), new HelperFrames.End());
        assertThat(Files.readString(listOut, StandardCharsets.UTF_8))
            .isEqualTo(String.join("\n", entries) + "\n");
        assertThat(script.calls()).as("login, own node, the listing and logout (FR-007)")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("IBISSoapClient.getObject", "P-0001_a@@@Workflow-0001", USER, IO_LISTING),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    @Test
    void rest90IoLogUsesTheFactoryClientAndDoesNotLogOut() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY);
        Path listOut = dir.resolve("io.list");

        Run run = run(script, nodeToolRequest("ioLog", lines -> {
            lines.put("variant", "REST_90");
            lines.put("url", encode(URL_90));
            lines.put("listOut", encode(listOut.toString()));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(List.of("io", "list", "ok", "1"), List.of("end"));
        assertThat(script.calls()).endsWith(
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "RestClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("RestClient.getObject", "P-0001_a@@@Workflow-0001", USER, IO_LISTING));
    }

    /**
     * The object name is the process id and the workflow as requested, joined by {@code @@@};
     * single separator characters of the workflow name stay as they are.
     */
    @Test
    void theListingIsRequestedForTheProcessIdAndTheWorkflowOfTheRequest() throws Exception {
        String workflow = "Order@Shop##Inbound ü";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("A1b2_C3-d4@@@" + workflow, IO_LISTING, "");

        Run run = run(script, nodeToolRequest("ioLog", lines -> {
            lines.put("processId", "A1b2_C3-d4");
            lines.put("workflow", encode(workflow));
            lines.put("listOut", encode(dir.resolve("io.list").toString()));
        }), vendor());

        assertThat(run.exit()).isZero();
        assertThat(script.calls()).contains(
            call("IBISSoapClient.getObject", "A1b2_C3-d4@@@" + workflow, USER, IO_LISTING));
    }

    /** An empty answer (no logged message, or an unknown workflow) lists zero entries. */
    @Test
    void anEmptyAnswerListsZeroEntriesIntoAnEmptyListFile() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, "");
        Path listOut = dir.resolve("io.list");

        Run run = run(script, nodeToolRequest("ioLog", lines -> lines.put("listOut",
            encode(listOut.toString()))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(List.of("io", "list", "ok", "0"), List.of("end"));
        assertThat(listOut).isEmptyFile();
    }

    /** Empty pieces between separators are no entries; the order of the others is kept. */
    @Test
    void emptyPiecesOfTheAnswerAreNoEntries() throws Exception {
        String other = "P-0001_a@@@Orders(Module-0001)@@@Output.1";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING,
                "###" + IO_ENTRY + "######" + other + "###");
        Path listOut = dir.resolve("io.list");

        Run run = run(script, nodeToolRequest("ioLog", lines -> lines.put("listOut",
            encode(listOut.toString()))), vendor());

        assertThat(frames(run.out())).contains(List.of("io", "list", "ok", "2"));
        assertThat(Files.readAllLines(listOut, StandardCharsets.UTF_8))
            .containsExactly(IO_ENTRY, other);
    }

    @Test
    void aFailedListingIsAnIoListErrorWithMaskedMessagesAndNoDownload() throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " and store "
            + TestSecrets.TRUST_STORE_PASSWORD + " refused";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .objectFails("P-0001_a@@@Workflow-0001", IO_LISTING,
                new Failure("java.lang.IllegalStateException", "no listing"),
                new Failure("java.io.IOException", echo));
        Path listOut = dir.resolve("io.list");
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(listOut, messageOut, IO_REF), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("io", "list", "error", "java.lang.IllegalStateException>java.io.IOException",
                "no listing\nuser jdoe with ****** and store ****** refused"),
            List.of("end"));
        assertThat(listOut).doesNotExist();
        assertThat(messageOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"))
            .last().isEqualTo("IBISSoapClient.logoutUser");
        TestSecrets.assertNoLeak(run.out());
        TestSecrets.assertNoLeak(decoded(run.out()));
    }

    /**
     * A {@code null} listing is a failed one, as a {@code null} node or member list: an
     * {@code io list error} without exception chain, no list file, no match and no download.
     */
    @Test
    void aNullListingIsAnIoListErrorWithoutChainAndNoDownload() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, null);
        Path listOut = dir.resolve("io.list");
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(listOut, messageOut, IO_REF), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("io", "list", "error", "-", "the client returned no I/O listing"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).contains(
            new HelperFrames.IoListError(List.of(), "the client returned no I/O listing"));
        assertThat(listOut).doesNotExist();
        assertThat(messageOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"))
            .last().isEqualTo("IBISSoapClient.logoutUser");
    }

    /** A list file the helper cannot write is an {@code io list error}, nothing is matched. */
    @Test
    void aListFileThatCannotBeWrittenIsAnIoListError() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .file("Workflow-0001###" + IO_ENTRY, DATA);
        Path listOut = dir.resolve("missing-directory").resolve("io.list");
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(listOut, messageOut, IO_REF), vendor());

        assertThat(run.exit()).isZero();
        List<List<String>> frames = frames(run.out());
        assertThat(frames.get(3)).startsWith("io", "list", "error").hasSize(5);
        assertThat(frames.get(3).get(3)).contains("java.nio.file.NoSuchFileException");
        assertThat(frames).hasSize(5).last().isEqualTo(List.of("end"));
        assertThat(messageOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"));
    }

    @Test
    void aFailedOwnNodeLookupEndsTheIoLogReadBeforeTheListing() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .nodeFails(new Failure("java.lang.Exception", "Server not in cluster mode."));

        Run run = run(script, nodeToolRequest("ioLog", lines -> lines.put("listOut",
            encode(dir.resolve("io.list").toString()))), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "error", "java.lang.Exception", "Server not in cluster mode."),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name)
            .doesNotContain("IBISSoapClient.getObject")
            .last().isEqualTo("IBISSoapClient.logoutUser");
    }

    /**
     * Each vector of the shared file (contracts/helper-protocol-delta.md "Message reference")
     * selects exactly its entry among all of them: the helper computes the same reference as
     * {@code IoMessageRef.of}, and the download name is the workflow and that listed entry.
     */
    @ParameterizedTest
    @MethodSource("ioMessageRefVectors")
    void eachVectorsReferenceDownloadsExactlyItsListedEntry(String entry, String ref)
        throws Exception {
        List<String> all = ioMessageRefVectors().map(vector -> (String) vector.get()[0]).toList();
        byte[] message = ("<message of " + entry + "/>").getBytes(StandardCharsets.UTF_8);
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, String.join("###", all))
            .file("Workflow-0001###" + entry, message);
        Path listOut = dir.resolve("io.list");
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(listOut, messageOut, ref), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("io", "list", "ok", Integer.toString(all.size())),
            List.of("io", "match", entry),
            List.of("part", "message", "ok", Integer.toString(message.length)),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames()).contains(
            new HelperFrames.IoMatch(Optional.of(entry)),
            new HelperFrames.PartOk(HelperFrames.Part.MESSAGE, message.length));
        assertThat(messageOut).hasBinaryContent(message);
        assertThat(Files.readAllLines(listOut, StandardCharsets.UTF_8))
            .containsExactlyElementsOf(all);
        assertThat(script.calls()).as("the listing, then one download of the matched entry")
            .containsExactly(
                call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                    TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
                call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
                call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
                call("ClusterClient.getNode"),
                call("IBISSoapClient.getObject", "P-0001_a@@@Workflow-0001", USER, IO_LISTING),
                call("MultipartDownloadThread.<init>", "IBISSoapClient", USER,
                    "Workflow-0001###" + entry, "InputOutputLogging", "null", "null"),
                call("MultipartDownloadThread.setOutputFile", messageOut.toString()),
                call("MultipartDownloadThread.start"),
                call("MultipartDownloadThread.exceptionOccured"),
                call("IBISSoapClient.logoutUser", USER));
        TestSecrets.assertNoLeak(run.out());
    }

    /** The lines of {@code fixtures/node-tools/io-message-ref.vectors} (T007), unchanged. */
    static Stream<Arguments> ioMessageRefVectors() throws IOException {
        return Files.readAllLines(IO_VECTORS, StandardCharsets.UTF_8).stream()
            .map(line -> line.split("\t", -1))
            .map(parts -> Arguments.of(parts[0], parts[1]));
    }

    /**
     * A reference that no listed entry has gives {@code io match none}: no download thread is
     * created and the request's reference never becomes a name.
     */
    @Test
    void aReferenceOfNoListedEntryIsIoMatchNoneWithoutADownloadThread() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .file("Workflow-0001###" + IO_ENTRY, DATA);
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"), messageOut, REF),
            vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("io", "list", "ok", "1"),
            List.of("io", "match", "none"),
            List.of("end"));
        assertThat(HelperFrames.parse(NONCE, run.out()).frames())
            .contains(new HelperFrames.IoMatch(Optional.empty()));
        assertThat(messageOut).doesNotExist();
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"));
        assertThat(script.calls()).flatExtracting(Call::arguments)
            .noneMatch(argument -> argument.contains(REF));
    }

    @Test
    void anEmptyListingWithAReferenceIsIoMatchNone() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, "");

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"),
            dir.resolve("message.bin"), IO_REF), vendor());

        assertThat(frames(run.out())).endsWith(
            List.of("io", "list", "ok", "0"),
            List.of("io", "match", "none"),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name)
            .noneMatch(name -> name.startsWith("MultipartDownloadThread"));
    }

    /** A message of 0 bytes is downloaded like any other (FR-023: the server reports it). */
    @Test
    void anEmptyMessageIsPartMessageOkZero() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .file("Workflow-0001###" + IO_ENTRY, new byte[0]);
        Path messageOut = dir.resolve("message.bin");

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"), messageOut, IO_REF),
            vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("io", "match", IO_ENTRY),
            List.of("part", "message", "ok", "0"),
            List.of("end"));
        assertThat(messageOut).isEmptyFile();
    }

    @Test
    void aFailedDownloadIsAPartMessageErrorWithMaskedMessages() throws Exception {
        String echo = "user jdoe with " + TestSecrets.CONFIGURED_PASSWORD + " refused";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .fileFails("Workflow-0001###" + IO_ENTRY,
                new Failure("java.lang.Exception", "Unable to download"),
                new Failure("java.io.IOException", echo));

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"),
            dir.resolve("message.bin"), IO_REF), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("io", "match", IO_ENTRY),
            List.of("part", "message", "error",
                "java.lang.Exception>java.io.IOException",
                "Unable to download\nuser jdoe with ****** refused"),
            List.of("end"));
        assertThat(script.calls()).extracting(Call::name).containsSubsequence(
            "MultipartDownloadThread.exceptionOccured", "MultipartDownloadThread.getException",
            "IBISSoapClient.logoutUser");
        TestSecrets.assertNoLeak(run.out());
        TestSecrets.assertNoLeak(decoded(run.out()));
    }

    @Test
    void aDownloadFailingWithoutAnExceptionIsAPartMessageErrorWithoutChain() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .fileFailsWithoutException("Workflow-0001###" + IO_ENTRY);

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"),
            dir.resolve("message.bin"), IO_REF), vendor());

        assertThat(run.exit()).isZero();
        assertThat(frames(run.out())).endsWith(
            List.of("part", "message", "error", "-", "-"),
            List.of("end"));
    }

    /**
     * The download thread gets the session's additional user group as its last argument, as
     * the Workbench's I/O view does (research "Static facts").
     */
    @Test
    void theDownloadCarriesTheAdditionalUserGroupOfTheSession() throws Exception {
        String currentUser = "com.inubit.ibis.common.CurrentUser";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY)
            .file("Workflow-0001###" + IO_ENTRY, DATA);

        Run run = run(script, ioMessageRequest(dir.resolve("io.list"),
            dir.resolve("message.bin"), IO_REF), replacing(currentUser, stub(currentUser,
                "public static String getClientUID() { return \"" + CLIENT_UID + "\"; }",
                "public static String getUsedAdditionalUserGroup() { return \"group-0001\"; }")));

        assertThat(run.exit()).isZero();
        assertThat(script.calls()).contains(
            call("MultipartDownloadThread.<init>", "IBISSoapClient", USER,
                "Workflow-0001###" + IO_ENTRY, "InputOutputLogging", "null", "group-0001"));
    }

    /** The listing goes to the file, so a long run's listing leaves the stdout small. */
    @Test
    void aLongListingStaysOutOfTheStdout() throws Exception {
        List<String> entries = new ArrayList<>();
        for (int i = 1; i <= 20_000; i++) {
            entries.add("P-0001_a@@@Bestellprüfung " + "€".repeat(40) + "(Module-0001)@@@Input."
                + i);
        }
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, String.join("###", entries));
        Path listOut = dir.resolve("io.list");

        Run run = run(script, nodeToolRequest("ioLog", lines -> lines.put("listOut",
            encode(listOut.toString()))), vendor());

        assertThat(run.out().getBytes(StandardCharsets.UTF_8).length).isLessThan(1_024);
        assertThat(frames(run.out())).contains(List.of("io", "list", "ok", "20000"));
        assertThat(Files.size(listOut)).isGreaterThan(ProcessRun.MAX_OUTPUT_BYTES);
        assertThat(Files.readAllLines(listOut, StandardCharsets.UTF_8))
            .containsExactlyElementsOf(entries);
    }

    @Test
    void ioLogWithoutGetObjectEndsWithApiMissingAndExitFourBeforeAnyCall() throws Exception {
        String client = "com.inubit.ibis.utils.I_IBISClientInterface";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY);

        Run run = run(script, nodeToolRequest("ioLog", lines -> { }), replacing(client,
            "package com.inubit.ibis.utils;\n"
                + "public interface I_IBISClientInterface {\n"
                + "void loginUserCli(String user, String password) throws Exception;\n}\n"));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing",
            client + ".getObject(java.lang.String,java.lang.String,java.lang.String)"));
        assertThat(script.calls()).isEmpty();
    }

    @Test
    void ioLogWithoutTheDownloadThreadEndsWithApiMissingAndExitFourBeforeAnyCall()
        throws Exception {
        String download = "com.inubit.ibis.configuration.utils.io.MultipartDownloadThread";
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .object("P-0001_a@@@Workflow-0001", IO_LISTING, IO_ENTRY);

        Run run = run(script, nodeToolRequest("ioLog", lines -> { }), hiding(download));

        assertThat(run.exit()).isEqualTo(4);
        assertThat(frames(run.out())).containsExactly(List.of("api", "missing", download));
        assertThat(script.calls()).isEmpty();
    }

    // --- the frame writer (D-4) -----------------------------------------------------------

    @Test
    void framesCarryTheNoncePrefixAndPercentEncodedTokens() throws Exception {
        Method frame = helper.getDeclaredMethod("frame", PrintStream.class, String.class,
            String[].class);
        frame.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        frame.invoke(null, out, NONCE, new String[] {"part", "data", "error",
            "java.io.IOException>java.net.ConnectException",
            "@Start@Unable to download 100% of Order-0001.xml\nnext \u00fc #QE1:x"});
        frame.invoke(null, out, NONCE, new String[] {"login", "error", "", ""});
        frame.invoke(null, out, NONCE, new String[] {"end"});

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(
            "#QE1:" + NONCE + " part data error java.io.IOException%3Ejava.net.ConnectException"
                + " %40Start%40Unable%20to%20download%20100%25%20of%20Order-0001.xml%0Anext%20"
                + "%C3%BC%20%23QE1%3Ax\n"
                + "#QE1:" + NONCE + " login error - -\n"
                + "#QE1:" + NONCE + " end\n");
    }

    // --- helpers --------------------------------------------------------------------------

    /** A well-formed 8.1 request (D-4), every value percent-encoded, changed by {@code edit}. */
    static String request(Consumer<Map<String, String>> edit) {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("qe", "1");
        lines.put("nonce", NONCE);
        lines.put("variant", "SOAP_81");
        lines.put("url", "https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS");
        lines.put("trustStore", "%2Fvar%2Fqe-0001%2Ftrust.p12");
        lines.put("trustStorePassword", TestSecrets.TRUST_STORE_PASSWORD);
        lines.put("hostnameVerification", "true");
        lines.put("objectType", "queue_manager%40%40%40Workflow-0001%40%40%40error");
        lines.put("fileName", "Order-0001.xml");
        lines.put("parts", "data%2Cvariables");
        lines.put("dataOut", "%2Fvar%2Fqe-0001%2Fout%2Fdata.bin");
        lines.put("variablesOut", "%2Fvar%2Fqe-0001%2Fout%2Fvariables.wfsp");
        lines.put("user", "jdoe");
        lines.put("password", TestSecrets.CONFIGURED_PASSWORD);
        edit.accept(lines);
        StringBuilder text = new StringBuilder();
        lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.append("end\n").toString();
    }

    /** The well-formed 8.1 request with {@code opLines} as the lines after {@code qe=1}. */
    static String withOp(String opLines) {
        return request(lines -> { }).replace("qe=1\n", "qe=1\n" + opLines + "\n");
    }

    /**
     * A well-formed 8.1 {@code serverLog} request (feature 021, D-4), every value
     * percent-encoded, changed by {@code edit}.
     */
    static String serverLogRequest(Consumer<Map<String, String>> edit) {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("qe", "1");
        lines.put("op", "serverLog");
        lines.put("nonce", NONCE);
        lines.put("variant", "SOAP_81");
        lines.put("url", "https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS");
        lines.put("trustStore", "%2Fvar%2Fqe-0001%2Ftrust.p12");
        lines.put("trustStorePassword", TestSecrets.TRUST_STORE_PASSWORD);
        lines.put("hostnameVerification", "true");
        lines.put("log", "webservice");
        lines.put("logOut", "%2Fvar%2Fqe-0001%2Fout%2Fwebservice.log");
        lines.put("user", "jdoe");
        lines.put("password", TestSecrets.CONFIGURED_PASSWORD);
        edit.accept(lines);
        StringBuilder text = new StringBuilder();
        lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.append("end\n").toString();
    }

    /**
     * A well-formed 8.1 request of the node-tool operation {@code op} (feature 024, D-2) with
     * valid keys of that operation, every value percent-encoded, changed by {@code edit}.
     */
    static String nodeToolRequest(String op, Consumer<Map<String, String>> edit) {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("qe", "1");
        lines.put("op", op);
        lines.put("nonce", NONCE);
        lines.put("variant", "SOAP_81");
        lines.put("url", "https%3A%2F%2Finubit.example.test%3A8443%2Fibis%2Fservlet%2FIBIS");
        lines.put("trustStore", "%2Fvar%2Fqe-0001%2Ftrust.p12");
        lines.put("trustStorePassword", TestSecrets.TRUST_STORE_PASSWORD);
        lines.put("hostnameVerification", "true");
        switch (op) {
            case "clusterNodes" -> lines.put("details", "");
            case "nodeConfig" -> lines.put("names", "OperationMode%2CNodeName");
            case "ioLog" -> {
                lines.put("processId", "P-0001_a");
                lines.put("workflow", "Workflow-0001");
                lines.put("ref", "");
                lines.put("listOut", "%2Fvar%2Fqe-0001%2Fout%2Fio.list");
                lines.put("messageOut", "");
            }
            default -> { }
        }
        lines.put("user", "jdoe");
        lines.put("password", TestSecrets.CONFIGURED_PASSWORD);
        edit.accept(lines);
        StringBuilder text = new StringBuilder();
        lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.append("end\n").toString();
    }

    /** A well-formed {@code ioLog} request for the message {@code ref} of the default run. */
    static String ioMessageRequest(Path listOut, Path messageOut, String ref) {
        return nodeToolRequest("ioLog", lines -> {
            lines.put("ref", ref);
            lines.put("listOut", encode(listOut.toString()));
            lines.put("messageOut", encode(messageOut.toString()));
        });
    }

    /** Runs the helper with {@code script} active. */
    static Run run(FakeInubitScript script, String request, ClassLoader vendor) throws Exception {
        try (FakeInubitScript.Activation ignored = script.activate()) {
            return run(request, vendor);
        }
    }

    /** The test class loader, which sees the fake vendor classes under {@code com.inubit.ibis}. */
    static ClassLoader vendor() {
        return ClientHelperTest.class.getClassLoader();
    }

    /**
     * A vendor class loader that defines its own copy of {@code className} from the test
     * classes, so that copy's static initializer runs again.
     */
    static ClassLoader reloading(String className) throws IOException {
        try (InputStream in = vendor().getResourceAsStream(
            className.replace('.', '/') + ".class")) {
            return defining(className, in.readAllBytes());
        }
    }

    /**
     * A vendor class loader that defines {@code className} from the stub {@code source},
     * compiled here against the test classes, so a vendor class can lack one member.
     */
    ClassLoader replacing(String className, String source) throws Exception {
        String path = className.replace('.', '/');
        Path file = dir.resolve("stub-src").resolve(path + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Path classes = Files.createDirectories(dir.resolve("stub-classes"));
        String testClasses = Path.of(ClientHelperTest.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath",
            testClasses, "-d", classes.toString(), file.toString());
        assertThat(exit).as("the stub compiles").isZero();
        byte[] bytes = Files.readAllBytes(classes.resolve(path + ".class"));
        return defining(className, bytes);
    }

    /** The source of the public class {@code className} with the given members only. */
    static String stub(String className, String... members) {
        int dot = className.lastIndexOf('.');
        return "package " + className.substring(0, dot) + ";\npublic class "
            + className.substring(dot + 1) + " {\n" + String.join("\n", members) + "\n}\n";
    }

    /**
     * A vendor class loader that defines its own {@code className} from the class file
     * {@code bytes} and delegates every other class to the test class loader.
     */
    static ClassLoader defining(String className, byte[] bytes) {
        return new ClassLoader(vendor()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (!name.equals(className)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            }
        };
    }

    /** A vendor class loader without the {@code hiddenClasses}. */
    static ClassLoader hiding(String... hiddenClasses) {
        return new ClassLoader(vendor()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (Arrays.asList(hiddenClasses).contains(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    static void outputs(Map<String, String> lines, Path data, Path variables) {
        lines.put("dataOut", encode(data.toString()));
        lines.put("variablesOut", encode(variables.toString()));
    }

    static void variablesOnly(Map<String, String> lines, Path variables) {
        lines.put("parts", "variables");
        lines.put("dataOut", "");
        lines.put("variablesOut", encode(variables.toString()));
    }

    /** The decoded tokens of every frame of this test's nonce, in order. */
    static List<List<String>> frames(String out) {
        String prefix = "#QE1:" + NONCE + " ";
        List<List<String>> frames = new ArrayList<>();
        for (String line : out.split("\n")) {
            assertThat(line).as("the helper prints frames only").startsWith(prefix);
            frames.add(Arrays.stream(line.substring(prefix.length()).split(" ", -1))
                .map(token -> URLDecoder.decode(token, StandardCharsets.UTF_8)).toList());
        }
        return frames;
    }

    /** The decoded frames whose first token is {@code first}. */
    static List<List<String>> framesStartingWith(String out, String first) {
        return frames(out).stream().filter(frame -> frame.get(0).equals(first)).toList();
    }

    static String decoded(String out) {
        return URLDecoder.decode(out, StandardCharsets.UTF_8);
    }

    /** RFC 3986 percent-encoding of a request value. */
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (Character.isLetterOrDigit(c) && c < 0x80 || "-._~".indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append(String.format("%%%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    static Call call(String name, String... arguments) {
        return new Call(name, List.of(arguments));
    }

    /** Calls {@code ClientHelper.run} with {@code request} on stdin. */
    static Run run(String request, ClassLoader vendor) throws Exception {
        return run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), vendor);
    }

    /** Calls {@code ClientHelper.run} with {@code in} as stdin. */
    static Run run(InputStream in, ClassLoader vendor) throws Exception {
        Method run = helper.getDeclaredMethod("run", InputStream.class, PrintStream.class,
            ClassLoader.class);
        run.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = (int) run.invoke(null, in, new PrintStream(bytes, true, StandardCharsets.UTF_8), vendor);
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    record Run(int exit, String out) {
    }
}
