package de.dadecker.inubit.mcp.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inubit.ibis.client.rest.InubitClientFactory;
import com.inubit.ibis.common.CurrentUser;
import com.inubit.ibis.configuration.utils.ConnectionData;
import com.inubit.ibis.configuration.utils.io.MultipartDownloadThread;
import com.inubit.ibis.server.cache.NodeName;
import com.inubit.ibis.server.cluster.ClusterClient;
import com.inubit.ibis.server.cluster.data.Node;
import com.inubit.ibis.soap.IBISSoapClient;
import com.inubit.ibis.utils.I_IBISClientInterface;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fake INUBIT client of feature 020 (research D-19): the fake vendor classes under
 * {@code com.inubit.ibis}, driven by a {@link FakeInubitScript}, in-process and as the
 * {@code lib/ibis.jar} of a {@link FakeInubitClient} home in a child JVM.
 */
@Timeout(60)
class FakeInubitClientTest {

    private static final String USER = "jdoe";
    private static final String FILE = "Order-0001.xml";
    private static final String OBJECT_TYPE = "queue_manager@@@Workflow-0001@@@error";
    private static final String MISSING = "@Start@Unable to download@@@" + FILE
        + "@End@acme/workflow/Workflow-0001/error/" + FILE;
    private static final String NODE = "inubit-node-01";
    private static final String LOG = "webservice.log";

    @TempDir
    Path dir;

    @Test
    void soapClientLogsInDownloadsAndLogsOutAsScripted() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .file(FILE, "<order/>".getBytes(StandardCharsets.UTF_8))
            .file(FILE + ".wfsp", "<Properties version=\"4.1\"/>".getBytes(StandardCharsets.UTF_8));
        Path data = dir.resolve("data.bin");
        Path variables = dir.resolve("variables.wfsp");
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = new IBISSoapClient("https://inubit.example.test/ibis?isCli",
                "/trust.p12", TestSecrets.TRUST_STORE_PASSWORD, null, null, true);
            client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD);
            assertThat(download(client, FILE, data)).isNull();
            assertThat(download(client, FILE + ".wfsp", variables)).isNull();
            client.logoutUser(USER);
        }
        assertThat(data).hasContent("<order/>");
        assertThat(variables).hasContent("<Properties version=\"4.1\"/>");
        assertThat(script.calls()).containsExactly(
            call("IBISSoapClient.<init>", "https://inubit.example.test/ibis?isCli", "/trust.p12",
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
    }

    @Test
    void restClientComesFromTheFactoryAndHasNoLogout() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .file(FILE, "<order/>".getBytes(StandardCharsets.UTF_8));
        Path data = dir.resolve("data.bin");
        try (FakeInubitScript.Activation ignored = script.activate()) {
            ConnectionData connection = new ConnectionData();
            connection.setServerURL("https://inubit.example.test/ibis");
            connection.setUserName(USER);
            connection.setPassword(TestSecrets.CONFIGURED_PASSWORD);
            connection.setServerAuth(true);
            connection.setServerTrustStoreFile(new File("/trust.p12"));
            connection.setServerTrustStorePassword(TestSecrets.TRUST_STORE_PASSWORD);
            connection.setSkipHostnameVerification(false);
            I_IBISClientInterface client = new InubitClientFactory().getRestInstance(connection);
            client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD);
            assertThat(download(client, FILE, data)).isNull();
            assertThat(client.getClass().getMethods()).noneMatch(
                m -> m.getName().equals("logoutUser"));
        }
        assertThat(data).hasContent("<order/>");
        assertThat(script.calls()).startsWith(
            call("ConnectionData.setServerURL", "https://inubit.example.test/ibis"),
            call("ConnectionData.setUserName", USER),
            call("ConnectionData.setPassword", TestSecrets.CONFIGURED_PASSWORD),
            call("ConnectionData.setServerAuth", "true"),
            call("ConnectionData.setServerTrustStoreFile", new File("/trust.p12").getPath()),
            call("ConnectionData.setServerTrustStorePassword", TestSecrets.TRUST_STORE_PASSWORD),
            call("ConnectionData.setSkipHostnameVerification", "false"),
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("MultipartDownloadThread.<init>", "RestClient", USER, FILE, OBJECT_TYPE, "null",
                "null"));
    }

    @Test
    void loginFailureThrowsTheScriptedChain() throws Exception {
        FakeInubitScript script = new FakeInubitScript().loginFails(
            new Failure(IOException.class.getName(), "Login failed"),
            new Failure(SSLHandshakeException.class.getName(), "PKIX path building failed"));
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = new IBISSoapClient("https://inubit.example.test/ibis?isCli",
                null, null, null, null, true);
            assertThatThrownBy(() -> client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD))
                .isExactlyInstanceOf(IOException.class).hasMessage("Login failed")
                .cause().isExactlyInstanceOf(SSLHandshakeException.class)
                .hasMessage("PKIX path building failed");
        }
    }

    @Test
    void partFailureIsReportedByTheThreadAndWritesNothing() throws Exception {
        FakeInubitScript script = new FakeInubitScript()
            .fileFails(FILE, new Failure(Exception.class.getName(), MISSING));
        Path data = dir.resolve("data.bin");
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = new IBISSoapClient("http://inubit.example.test/ibis?isCli",
                null, null, null, null, false);
            client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD);
            Exception failure = download(client, FILE, data);
            assertThat(failure).isExactlyInstanceOf(Exception.class).hasMessage(MISSING);
        }
        assertThat(data).doesNotExist();
    }

    @Test
    void unscriptedFileIsAFailureNamingTheFile() throws Exception {
        try (FakeInubitScript.Activation ignored = new FakeInubitScript().activate()) {
            IBISSoapClient client = new IBISSoapClient("http://inubit.example.test/ibis?isCli",
                null, null, null, null, false);
            assertThat(download(client, FILE, dir.resolve("data.bin")))
                .hasMessageContaining(FILE);
        }
    }

    /** Feature 021 (research D-2, D-22): the cluster client's node and log download. */
    @Test
    void clusterClientReturnsTheScriptedNodeAndANotStartedLogDownload() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .file(LOG, "2026-11-12 10:00:00,000 1 [WebService]\n".getBytes(StandardCharsets.UTF_8));
        Path log = dir.resolve(LOG);
        try (FakeInubitScript.Activation ignored = script.activate()) {
            IBISSoapClient client = new IBISSoapClient("https://inubit.example.test/ibis?isCli",
                null, null, null, null, false);
            client.loginUserCli(USER, TestSecrets.CONFIGURED_PASSWORD);
            assertThat(CurrentUser.getClientUID()).isNotEmpty();
            assertThat(CurrentUser.getUsedAdditionalUserGroup()).isNull();
            ClusterClient cluster = new ClusterClient(client, USER, CurrentUser.getClientUID(),
                CurrentUser.getUsedAdditionalUserGroup());
            Node node = cluster.getNode();
            NodeName nodeName = node.getNodeName();
            assertThat(nodeName.getName()).isEqualTo(NODE);
            MultipartDownloadThread thread = cluster.clusterDownload(log.toFile(), nodeName, LOG);
            assertThat(thread.getState()).isEqualTo(Thread.State.NEW);
            assertThat(log).doesNotExist();
            thread.start();
            thread.join();
            assertThat(thread.exceptionOccured()).isFalse();
            client.logoutUser(USER);
        }
        assertThat(log).hasContent("2026-11-12 10:00:00,000 1 [WebService]");
        assertThat(script.calls()).containsExactly(
            call("IBISSoapClient.<init>", "https://inubit.example.test/ibis?isCli", "null",
                "null", "null", "null", "false"),
            call("IBISSoapClient.loginUserCli", USER, TestSecrets.CONFIGURED_PASSWORD),
            call("ClusterClient.<init>", "IBISSoapClient", USER, CurrentUser.getClientUID(),
                "null"),
            call("ClusterClient.getNode"),
            call("ClusterClient.clusterDownload", log.toString(), NODE, LOG),
            call("MultipartDownloadThread.start"),
            call("MultipartDownloadThread.exceptionOccured"),
            call("IBISSoapClient.logoutUser", USER));
    }

    @Test
    void nodeNameFactoryKeepsTheName() {
        assertThat(NodeName.nodeName(NODE).getName()).isEqualTo(NODE);
    }

    @Test
    void getNodeFailureThrowsTheScriptedChain() {
        FakeInubitScript script = new FakeInubitScript().nodeFails(
            new Failure(Exception.class.getName(), "Server not in cluster mode."));
        try (FakeInubitScript.Activation ignored = script.activate()) {
            ClusterClient cluster = new ClusterClient(new IBISSoapClient(
                "http://inubit.example.test/ibis?isCli", null, null, null, null, false), USER,
                CurrentUser.getClientUID(), null);
            assertThatThrownBy(cluster::getNode).isExactlyInstanceOf(Exception.class)
                .hasMessage("Server not in cluster mode.");
        }
        assertThat(script.calls()).endsWith(call("ClusterClient.getNode"));
    }

    @Test
    void unscriptedNodeIsAFailure() {
        try (FakeInubitScript.Activation ignored = new FakeInubitScript().activate()) {
            ClusterClient cluster = new ClusterClient(new IBISSoapClient(
                "http://inubit.example.test/ibis?isCli", null, null, null, null, false), USER,
                CurrentUser.getClientUID(), null);
            assertThatThrownBy(cluster::getNode).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("node");
        }
    }

    @Test
    void logDownloadFailureIsReportedByTheThreadAndWritesNothing() throws Exception {
        FakeInubitScript script = new FakeInubitScript().node(NODE)
            .fileFails(LOG, new Failure(Exception.class.getName(), "Unable to download " + LOG));
        Path log = dir.resolve(LOG);
        try (FakeInubitScript.Activation ignored = script.activate()) {
            ClusterClient cluster = new ClusterClient(new IBISSoapClient(
                "http://inubit.example.test/ibis?isCli", null, null, null, null, false), USER,
                CurrentUser.getClientUID(), null);
            MultipartDownloadThread thread = cluster.clusterDownload(log.toFile(),
                cluster.getNode().getNodeName(), LOG);
            thread.start();
            thread.join();
            assertThat(thread.exceptionOccured()).isTrue();
            assertThat(thread.getException()).isExactlyInstanceOf(Exception.class)
                .hasMessage("Unable to download " + LOG);
        }
        assertThat(log).doesNotExist();
    }

    @Test
    void onlyOneScriptIsActiveAndClosingDeactivatesIt() {
        FakeInubitScript script = new FakeInubitScript();
        try (FakeInubitScript.Activation ignored = script.activate()) {
            assertThatThrownBy(() -> new FakeInubitScript().activate())
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new IBISSoapClient("http://inubit.example.test/ibis?isCli",
            null, null, null, null, false)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FakeInubitScript");
    }

    @Test
    void installWritesAClientHomeWithTheFakeClassesInLibIbisJar() throws IOException {
        FakeInubitClient client = FakeInubitClient.install(dir.resolve("client"),
            new FakeInubitScript());
        assertThat(client.home()).isEqualTo(dir.resolve("client"));
        assertThat(client.home().resolve("lib/shared")).isDirectory();
        assertThat(client.home().resolve("conf")).isDirectory();
        try (JarFile jar = new JarFile(client.home().resolve("lib/ibis.jar").toFile())) {
            List<String> classes = jar.stream().map(ZipEntry::getName)
                .filter(name -> name.endsWith(".class")).toList();
            assertThat(classes).contains(
                "com/inubit/ibis/soap/IBISSoapClient.class",
                "com/inubit/ibis/configuration/utils/ConnectionData.class",
                "com/inubit/ibis/client/rest/InubitClientFactory.class",
                "com/inubit/ibis/configuration/utils/io/MultipartDownloadThread.class",
                "com/inubit/ibis/utils/I_IBISClientInterface.class",
                "com/inubit/ibis/server/cluster/ClusterClient.class",
                "com/inubit/ibis/server/cluster/data/Node.class",
                "com/inubit/ibis/server/cache/NodeName.class",
                "com/inubit/ibis/common/CurrentUser.class",
                "de/dadecker/inubit/mcp/testing/FakeInubitScript.class");
            assertThat(classes).allMatch(name -> name.startsWith("com/inubit/ibis/")
                || name.startsWith("de/dadecker/inubit/mcp/testing/FakeInubitScript"));
        }
        assertThat(client.calls()).isEmpty();
    }

    /**
     * A child JVM with only {@code <home>/lib/*} on its class path runs a driver against the
     * fake 9.0 client: the script comes from {@code conf/}, the calls are recorded back there,
     * the vendor noise goes to stdout and a lingering non-daemon thread stays behind.
     */
    @Test
    void childJvmUsesTheScriptAndRecordsTheCallsInTheClientHome() throws Exception {
        FakeInubitClient client = FakeInubitClient.install(dir.resolve("client"),
            new FakeInubitScript()
                .file(FILE, "<order/>".getBytes(StandardCharsets.UTF_8))
                .fileFails(FILE + ".wfsp",
                    new Failure(ConnectException.class.getName(), "Connection refused"))
                .printsOnLogin("log4j:WARN No appenders could be found")
                .leavesNonDaemonThread());
        Path data = dir.resolve("data.bin");
        Path driver = Files.writeString(dir.resolve("Driver.java"), """
            import com.inubit.ibis.client.rest.InubitClientFactory;
            import com.inubit.ibis.configuration.utils.ConnectionData;
            import com.inubit.ibis.configuration.utils.io.MultipartDownloadThread;
            import com.inubit.ibis.utils.I_IBISClientInterface;
            import java.io.File;

            public class Driver {
                public static void main(String[] args) throws Exception {
                    ConnectionData connection = new ConnectionData();
                    connection.setServerURL("https://inubit.example.test/ibis");
                    I_IBISClientInterface client = new InubitClientFactory()
                        .getRestInstance(connection);
                    client.loginUserCli("jdoe", "secret");
                    for (String name : new String[] {"Order-0001.xml", "Order-0001.xml.wfsp"}) {
                        MultipartDownloadThread thread = new MultipartDownloadThread(client,
                            "jdoe", name, "queue_manager@@@Workflow-0001@@@error", null, null);
                        thread.setOutputFile(new File(args[0]));
                        thread.start();
                        thread.join();
                        System.out.println(name + " failed=" + thread.exceptionOccured()
                            + (thread.exceptionOccured()
                                ? " " + thread.getException().getClass().getName() : ""));
                    }
                    boolean lingering = Thread.getAllStackTraces().keySet().stream()
                        .anyMatch(t -> t.getName().equals("fake-inubit-lingering") && t.isAlive()
                            && !t.isDaemon());
                    System.out.println("lingering=" + lingering);
                    System.exit(0);
                }
            }
            """);
        Process process = new ProcessBuilder(Platform.javaExecutable().toString(), "-cp",
            client.home().resolve("lib") + File.separator + "*", driver.toString(),
            data.toString()).directory(client.home().toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS))
            .isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("log4j:WARN No appenders could be found",
            "Order-0001.xml failed=false",
            "Order-0001.xml.wfsp failed=true java.net.ConnectException", "lingering=true");
        assertThat(data).hasContent("<order/>");
        assertThat(client.calls()).contains(
            call("ConnectionData.setServerURL", "https://inubit.example.test/ibis"),
            call("InubitClientFactory.getRestInstance"),
            call("RestClient.loginUserCli", USER, "secret"),
            call("MultipartDownloadThread.<init>", "RestClient", USER, FILE + ".wfsp",
                OBJECT_TYPE, "null", "null"),
            call("MultipartDownloadThread.getException"));
    }

    private static Exception download(I_IBISClientInterface client, String fileName, Path out)
        throws InterruptedException {
        MultipartDownloadThread thread = new MultipartDownloadThread(client, USER, fileName,
            OBJECT_TYPE, null, null);
        thread.setOutputFile(out.toFile());
        thread.start();
        thread.join();
        return thread.exceptionOccured() ? thread.getException() : null;
    }

    private static Call call(String name, String... arguments) {
        return new Call(name, List.of(arguments));
    }
}
