package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Call;
import de.dadecker.inubit.mcp.testing.FakeInubitScript.Failure;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The queue-entry helper of feature 020 (research D-2, D-4) in-process: it is loaded from its
 * resource directory {@code META-INF/inubit-mcp/queue-entry-helper/} of the compiled classes by a
 * {@link URLClassLoader} whose parent is the test class loader (so the fake vendor classes under
 * {@code com.inubit.ibis} are visible), and {@code run(InputStream, PrintStream, ClassLoader)} is
 * called reflectively; {@code main} and its {@code System.exit} are never called here.
 */
@Timeout(60)
class QueueEntryHelperTest {

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

    private static URLClassLoader loader;
    private static Class<?> helper;

    @TempDir
    Path dir;

    @BeforeAll
    static void loadHelper() throws Exception {
        URL dir = QueueEntryHelperPackagingTest.resourceDirectory().toUri().toURL();
        loader = new URLClassLoader(new URL[] {dir}, QueueEntryHelperTest.class.getClassLoader());
        helper = Class.forName(QueueEntryHelperPackagingTest.HELPER_CLASS, true, loader);
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
                "trustStorePassword", TestSecrets.TRUST_STORE_PASSWORD + "%FF"))));
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
            Arguments.of("CRLF line ends", request(lines -> { }).replace("\n", "\r\n")));
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

    /** Runs the helper with {@code script} active. */
    static Run run(FakeInubitScript script, String request, ClassLoader vendor) throws Exception {
        try (FakeInubitScript.Activation ignored = script.activate()) {
            return run(request, vendor);
        }
    }

    /** The test class loader, which sees the fake vendor classes under {@code com.inubit.ibis}. */
    static ClassLoader vendor() {
        return QueueEntryHelperTest.class.getClassLoader();
    }

    /**
     * A vendor class loader that defines its own copy of {@code className} from the test
     * classes, so that copy's static initializer runs again.
     */
    static ClassLoader reloading(String className) {
        return new ClassLoader(vendor()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (!name.equals(className)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) {
                        return loaded;
                    }
                    try (InputStream in = getParent().getResourceAsStream(
                        name.replace('.', '/') + ".class")) {
                        byte[] bytes = in.readAllBytes();
                        return defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
            }
        };
    }

    /** A vendor class loader without {@code hiddenClass}. */
    static ClassLoader hiding(String hiddenClass) {
        return new ClassLoader(vendor()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
                if (name.equals(hiddenClass)) {
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

    /** Calls {@code QueueEntryHelper.run} with {@code request} on stdin. */
    static Run run(String request, ClassLoader vendor) throws Exception {
        return run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), vendor);
    }

    /** Calls {@code QueueEntryHelper.run} with {@code in} as stdin. */
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
