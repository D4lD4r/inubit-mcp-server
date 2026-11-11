package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The stdin request of the queue-entry helper (research D-4, data-model "Helper request"): its
 * encoding, its validation and its {@code toString}, and a round trip through the helper itself
 * (loaded as in {@link ClientHelperTest}), so the server writes exactly what the helper
 * accepts.
 */
@Timeout(60)
class HelperRequestTest {

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

    // --- helpers ---------------------------------------------------------------------------

    /** The fields of a request; {@link #request} builds a valid 8.1 one, changed by an edit. */
    static final class Builder {
        String nonce = NONCE;
        String variant = "SOAP_81";
        URI url = URL_81;
        Optional<HelperRequest.TrustStore> trustStore = Optional.of(
            new HelperRequest.TrustStore(TRUST_STORE, Secret.of(TestSecrets.TRUST_STORE_PASSWORD)));
        boolean hostnameVerification = true;
        String objectType = OBJECT_TYPE;
        String fileName = FILE;
        Optional<Path> dataOut = Optional.of(DATA_OUT);
        Path variablesOut = VARIABLES_OUT;
        String user = USER;
        Secret password = Secret.of(TestSecrets.CONFIGURED_PASSWORD);
    }

    static HelperRequest request(Consumer<Builder> edit) {
        Builder r = new Builder();
        edit.accept(r);
        return new HelperRequest(r.nonce, r.variant, r.url, r.trustStore, r.hostnameVerification,
            r.objectType, r.fileName, r.dataOut, r.variablesOut, r.user, r.password);
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

    record Run(int exit, String out) {
    }
}
