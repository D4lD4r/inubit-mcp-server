package de.dadecker.inubit.mcp.adapter.cli.queue;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    private static URLClassLoader loader;
    private static Class<?> helper;

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
        frame.invoke(null, out, NONCE, new String[] {"end"});

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(
            "#QE1:" + NONCE + " part data error java.io.IOException%3Ejava.net.ConnectException"
                + " %40Start%40Unable%20to%20download%20100%25%20of%20Order-0001.xml%0Anext%20"
                + "%C3%BC%20%23QE1%3Ax\n"
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

    /** Calls {@code QueueEntryHelper.run} with {@code request} on stdin. */
    static Run run(String request, ClassLoader vendor) throws Exception {
        Method run = helper.getDeclaredMethod("run", InputStream.class, PrintStream.class,
            ClassLoader.class);
        run.setAccessible(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = (int) run.invoke(null,
            new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(bytes, true, StandardCharsets.UTF_8), vendor);
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    record Run(int exit, String out) {
    }
}
