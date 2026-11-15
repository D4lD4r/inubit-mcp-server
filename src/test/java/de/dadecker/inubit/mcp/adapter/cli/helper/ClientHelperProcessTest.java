package de.dadecker.inubit.mcp.adapter.cli.helper;

import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.CLIENT_UID;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.call;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.encode;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.ioMessageRequest;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.nodeToolRequest;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.outputs;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.request;
import static de.dadecker.inubit.mcp.adapter.cli.helper.ClientHelperTest.serverLogRequest;
import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliSystem;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.ProcessRun;
import de.dadecker.inubit.mcp.adapter.cli.SystemProcessLauncher;
import de.dadecker.inubit.mcp.adapter.rest.TestCertificates;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.testing.FakeInubitClient;
import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Member;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Property;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import de.dadecker.inubit.mcp.testing.TestCliRunners.FixedSystem;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The packaged helper of feature 020 (research D-3, D-4) in a real child JVM, reading a queue
 * entry, (feature 021, D-4, D-5) the web service log or (feature 024, D-2) answering a
 * node-tool operation:
 * {@link HelperInstallation} installs it in its private directory with a
 * {@link FakeInubitClient} as the client home, and its argument array (with the
 * {@code --add-opens} list) runs through {@link SystemProcessLauncher} and {@link ProcessRun}.
 * The vendor fakes read their script from {@code <home>/conf} and record their calls there.
 */
@Timeout(90)
class ClientHelperProcessTest {

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
    private static final String NODE = "inubit-node-01";
    private static final String NODE_2 = "inubit-node-02";
    /** The trust store of {@link ClientHelperTest#nodeToolRequest}, which the fakes never open. */
    private static final String TRUST_STORE = "/var/qe-0001/trust.p12";
    /** The process id and workflow of {@link ClientHelperTest#ioMessageRequest}. */
    private static final String IO_RUN = "P-0001_a";
    private static final String WORKFLOW = "Workflow-0001";
    /** The object type of the I/O logging listing (research "Static facts"). */
    private static final String IO_LISTING = "inputOutputMessageDetails";
    private static final String WEBSERVICE_LOG = "webservice.log";
    private static final byte[] LOG = ("2026-11-12 10:00:00,000 1 [WebService] request\n"
        + "2026-11-12 10:00:01,000 1 [WebService] response\n").getBytes(StandardCharsets.UTF_8);
    /** A frame-like noise line of another nonce, which a reader of this run must ignore. */
    private static final String FOREIGN_FRAME = "#QE1:ffffffffffffffffffffffffffffffff end";

    @TempDir
    Path tmp;

    private final CliResources resources = new CliResources("acme", PrivateFiles.forHost());
    private HelperInstallation installation;

    @AfterEach
    void closeResources() {
        if (installation != null) {
            installation.close();
            assertThat(installation.directory()).as("deleted on close").doesNotExist();
        }
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
        LaunchSpec spec = launchSpec(client);
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");
        HelperRequest.TrustStore trustStore = HelperTrustStore.write(NodeId.parse("int/a"),
            installation.trustStoreFile(),
            List.of(TestCertificates.get().localhostCertificate()), PrivateFiles.forHost());
        String trustStorePassword = trustStore.password().reveal();

        CliResult result = runHelper(spec, request(lines -> {
            outputs(lines, data, variables);
            lines.put("trustStore", encode(trustStore.file().toString()));
            lines.put("trustStorePassword", encode(trustStorePassword));
        }));

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
            call("IBISSoapClient.<init>", URL_81 + "?isCli", trustStore.file().toString(),
                trustStorePassword, "null", "null", "true"),
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
        assertThat(String.join(" ", spec.command())).doesNotContain(trustStorePassword);
        assertThat(result.stdout() + result.stderr()).as("the one-time password stays on stdin")
            .doesNotContain(trustStorePassword);
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
        LaunchSpec spec = launchSpec(client);
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");

        CliResult result = runHelper(spec, request(lines -> {
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
        LaunchSpec spec = launchSpec(client);
        Path data = out("data.bin");
        Path variables = out("variables.wfsp");

        CliResult result = runHelper(spec, request(lines -> {
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

    @Test
    void soap81ServerLogDownloadsTheWebServiceLogOfItsNodeIntoLogOut() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript()
                .printsOnLogin(NOISE)
                .node(NODE)
                .file(WEBSERVICE_LOG, LOG));
        LaunchSpec spec = launchSpec(client);
        Path logOut = out(WEBSERVICE_LOG);

        CliResult result = runHelper(spec, serverLogRequest(lines -> {
            lines.put("url", encode(URL_81));
            lines.put("logOut", encode(logOut.toString()));
        }));

        assertThat(result.exitCode()).isZero();
        assertThat(frames(result.stdout())).containsExactly(
            List.of("start", "SOAP_81"),
            List.of("login", "ok"),
            List.of("node", "ok", NODE),
            List.of("part", "log", "ok", Integer.toString(LOG.length)),
            List.of("end"));
        assertThat(noise(result.stdout())).containsExactly(NOISE);
        assertThat(logOut).hasBinaryContent(LOG);
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-006)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.clusterDownload", logOut.toString(), NODE, WEBSERVICE_LOG),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("IBISSoapClient.logoutUser", USER));
        assertThat(String.join(" ", spec.command())).as("request values travel on stdin only")
            .doesNotContain(logOut.toString(), URL_81, TestSecrets.CONFIGURED_PASSWORD,
                TestSecrets.TRUST_STORE_PASSWORD);
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    // --- operations of feature 024 (D-2): one child-JVM run each ---------------------------

    @Test
    void soap81OwnNodeAnswersTheNodeOfTheSession() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript().printsOnLogin(NOISE).node(NODE));
        LaunchSpec spec = launchSpec(client);

        CliResult result = runHelper(spec, nodeToolRequest("ownNode", lines -> { }));

        assertThat(result.exitCode()).isZero();
        assertThat(parsed(result.stdout())).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE), new HelperFrames.End());
        assertThat(noise(result.stdout())).containsExactly(NOISE);
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("IBISSoapClient.logoutUser", USER));
        assertRequestValuesStayOffTheArgv(spec);
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    @Test
    void soap81ClusterNodesListsTheMembersAndTheFlattenedDetailsOfOne() throws Exception {
        Map<String, Object> jdk = new LinkedHashMap<>();
        jdk.put("Vendor", "Eclipse Adoptium");
        Map<String, Object> information = new LinkedHashMap<>();
        information.put("ServerVersion", "9.0.10");
        information.put("JDK", jdk);
        information.put("LogDirectory", null);
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript().node(NODE)
                .members(new Member(NODE, "ACTIVE", "9.0.10", "zone-a", "node-id-0001",
                    3_600_000L), Member.unanswered(NODE_2))
                .nodeInformation(NODE_2, information));
        LaunchSpec spec = launchSpec(client);

        CliResult result = runHelper(spec, nodeToolRequest("clusterNodes",
            lines -> lines.put("details", NODE_2)));

        assertThat(result.exitCode()).isZero();
        assertThat(parsed(result.stdout())).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE),
            new HelperFrames.Member(NODE, "ACTIVE", "9.0.10", "zone-a", "node-id-0001",
                Optional.of(3_600_000L)),
            new HelperFrames.Member(NODE_2, "", "", "", "", Optional.empty()),
            new HelperFrames.MembersOk(2),
            new HelperFrames.Detail("ServerVersion", "9.0.10", false),
            new HelperFrames.Detail("JDK.Vendor", "Eclipse Adoptium", false),
            new HelperFrames.Detail("LogDirectory", "", false),
            new HelperFrames.DetailsOk(3, false),
            new HelperFrames.End());
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodes"),
            call("ClusterClient.getNode", NODE_2),
            call("IBISSoapClient.logoutUser", USER));
        assertRequestValuesStayOffTheArgv(spec, NODE_2);
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    @Test
    void soap81NodeConfigAnswersEachRequestedNameOfTheOwnNode() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript().node(NODE)
                .property("socketTimeoutInMillisecs", new Property(Integer.class, "60000",
                    "Socket timeout", "Socket timeout in milliseconds", "Network", true, false)));
        LaunchSpec spec = launchSpec(client);

        CliResult result = runHelper(spec, nodeToolRequest("nodeConfig",
            lines -> lines.put("names", "socketTimeoutInMillisecs%2CZoneName")));

        assertThat(result.exitCode()).isZero();
        assertThat(parsed(result.stdout())).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE),
            new HelperFrames.ConfigOk("socketTimeoutInMillisecs", "Integer", true, false,
                "Socket timeout", "Network", "Socket timeout in milliseconds", "60000"),
            new HelperFrames.ConfigAbsent("ZoneName"),
            new HelperFrames.End());
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.getNodeConfigurationProperty", NODE, "socketTimeoutInMillisecs"),
            call("ClusterClient.getNodeConfigurationProperty", NODE, "ZoneName"),
            call("IBISSoapClient.logoutUser", USER));
        assertRequestValuesStayOffTheArgv(spec, "socketTimeoutInMillisecs");
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    /**
     * The listing and the message travel through files; the reference is computed in this JVM
     * ({@link IoMessageRef}) and matched in the child, over a non-ASCII entry.
     */
    @Test
    void soap81IoLogWritesTheListingAndDownloadsTheReferencedMessage() throws Exception {
        String matched = IO_RUN + "@@@Bestellprüfung Größe(Module-0002)@@@Input.1";
        List<String> entries = List.of(IO_RUN + "@@@Orders(Module-0001)@@@Input.1", matched,
            IO_RUN + "@@@Orders(Module-0001)@@@Output.1");
        byte[] message = "<order id=\"Order-0001\">Größe</order>\n"
            .getBytes(StandardCharsets.UTF_8);
        FakeInubitClient client = FakeInubitClient.install(tmp.resolve("client"),
            new FakeInubitScript().node(NODE)
                .object(IO_RUN + "@@@" + WORKFLOW, IO_LISTING, String.join("###", entries))
                .file(WORKFLOW + "###" + matched, message));
        LaunchSpec spec = launchSpec(client);
        Path listOut = out("io.list");
        Path messageOut = out("message.bin");

        CliResult result = runHelper(spec,
            ioMessageRequest(listOut, messageOut, IoMessageRef.of(matched).value()));

        assertThat(result.exitCode()).isZero();
        assertThat(parsed(result.stdout())).containsExactly(
            new HelperFrames.Start("SOAP_81"), new HelperFrames.LoginOk(),
            new HelperFrames.NodeOk(NODE),
            new HelperFrames.IoListOk(entries.size()),
            new HelperFrames.IoMatch(Optional.of(matched)),
            new HelperFrames.PartOk(HelperFrames.Part.MESSAGE, message.length),
            new HelperFrames.End());
        assertThat(listOut).content(StandardCharsets.UTF_8)
            .isEqualTo(String.join("\n", entries) + "\n");
        assertThat(messageOut).hasBinaryContent(message);
        assertThat(client.calls()).as("the child JVM's vendor calls (FR-007)").containsExactly(
            call("IBISSoapClient.<init>", URL_81 + "?isCli", TRUST_STORE,
                TestSecrets.TRUST_STORE_PASSWORD, "null", "null", "true"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CLIENT_UID, "null"),
            call("ClusterClient.getNode"),
            call("IBISSoapClient.getObject", IO_RUN + "@@@" + WORKFLOW, USER, IO_LISTING),
            call("MultipartDownloadThread.<init>", "IBISSoapClient", USER,
                WORKFLOW + "###" + matched, "InputOutputLogging", "null", "null"),
            call("MultipartDownloadThread.setOutputFile", messageOut.toString()),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("IBISSoapClient.logoutUser", USER));
        assertRequestValuesStayOffTheArgv(spec, IO_RUN, WORKFLOW, listOut.toString(),
            messageOut.toString());
        TestSecrets.assertNoLeak(result.stdout());
        TestSecrets.assertNoLeak(result.stderr());
    }

    // --- helpers --------------------------------------------------------------------------

    /** {@code name} in the installation's {@code out/}. */
    private Path out(String name) {
        return installation.outDirectory().resolve(name);
    }

    /**
     * Installs the helper through {@link HelperInstallation} with {@code client} as the client
     * home and this JVM's runtime as the JDK; its D-3 argument array, environment and working
     * directory.
     */
    private LaunchSpec launchSpec(FakeInubitClient client) throws IOException {
        Path tempRoot = Files.createDirectories(tmp.resolve("tmp"));
        CliSystem system = new FixedSystem(Platform.windows()
            ? new CliSystem.Read(System.getenv("SystemRoot") + "\\system32")
            : new CliSystem.Unreadable("not Windows"), StandardCharsets.UTF_8);
        HelperInstallation.Host host = new HelperInstallation.Host(Platform.windows(),
            System.getenv(), system, ProcessContainment.Bound::new, resources, tempRoot);
        EffectiveNodeConfig server = TestNodeConfig.node().id("int/a").cliHome(client.home())
            .build();
        CliRunner.Selection selection = TestCliRunners.forHost(FakeProcessLauncher.of("", "", 0),
            Map.of(), resources).select(server, Optional.empty());
        installation = HelperInstallation.install(host, selection,
            Optional.of(System.getProperty("java.home")));
        assertThat(installation.launchSpec().command()).as("the vendor --add-opens list")
            .contains("--add-opens=java.base/java.lang=ALL-UNNAMED");
        return installation.launchSpec();
    }

    private CliResult runHelper(LaunchSpec spec, String request) {
        return new ProcessRun(new SystemProcessLauncher(), resources)
            .run(spec, request.getBytes(StandardCharsets.UTF_8), TIMEOUT, HELPER_LABEL);
    }

    private static void assertArgvCarriesOnlyJvmOptionsAndTheClassName(LaunchSpec spec,
        FakeInubitClient client, Path data, Path variables) {
        List<String> arguments = spec.command().subList(1, spec.command().size());
        assertThat(arguments.get(arguments.size() - 1))
            .isEqualTo(ClientHelperPackagingTest.HELPER_CLASS);
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

    /**
     * The request values of a {@link ClientHelperTest#nodeToolRequest} and {@code more} are not
     * in the argument array: they travel on stdin only.
     */
    private static void assertRequestValuesStayOffTheArgv(LaunchSpec spec, String... more) {
        List<String> values = new ArrayList<>(List.of(USER, URL_81, TRUST_STORE,
            TestSecrets.CONFIGURED_PASSWORD, TestSecrets.TRUST_STORE_PASSWORD));
        values.addAll(List.of(more));
        String argv = String.join(" ", spec.command());
        for (String value : values) {
            assertThat(argv).as("request values travel on stdin only").doesNotContain(value);
        }
    }

    /** This run's frames as {@link HelperFrames} parses them: complete, with {@code end}. */
    private static List<HelperFrames.Frame> parsed(String stdout) {
        HelperFrames frames = HelperFrames.parse(NONCE, stdout);
        assertThat(frames.problem()).as("protocol problem").isEmpty();
        assertThat(frames.ended()).as("ended with the end frame").isTrue();
        return frames.frames();
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
