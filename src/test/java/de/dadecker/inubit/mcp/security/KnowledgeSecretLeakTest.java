package de.dadecker.inubit.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.TestWiring;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.config.ProfileConfig;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.mcp.McpTestClient;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 019 T036 (SC-002, FR-010, FR-013, Constitution II): every kind of secret, in a
 * hand-written note's name, title, topics, {@code source}, {@code lastWriter} and body, never
 * appears in the instructions, the hits, the pages, the version tokens, the refs, the audit lines,
 * the log lines (our loggers at TRACE) or an error result; and every kind in new text is refused
 * without echo, also in an invalid name. Runs on the production wiring ({@link TestWiring}).
 */
@Timeout(120)
class KnowledgeSecretLeakTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The configured credential: its forms differ in every spelling of the scrubber. */
    private static final String PASSWORD = "kn0w-Leak<&>\"'/+ \u00e97";
    private static final String PEM_BODY =
        "MIIBOgIBAAJBAKleakPemBodyNotARealKey0123456789abcdefghijABCDEFGHIJ";
    private static final String PEM = "-----BEGIN RSA PRIVATE KEY-----\n" + PEM_BODY
        + "\n-----END RSA PRIVATE KEY-----";
    /** A JCEKS keystore in base64 without {@code +} and {@code /}: usable as a file name. */
    private static final String JCEKS = Base64.getEncoder().encodeToString(jceks());
    /** Built once: a PKCS#12 holds a random salt and MAC. */
    private static final byte[] PKCS12_BYTES = pkcs12();
    private static final String PKCS12 = Base64.getEncoder().encodeToString(PKCS12_BYTES);
    /** From the second line of the wrapped form: past the header every empty store shares. */
    private static final String PKCS12_LINE = PKCS12.substring(64, 100);
    private static final String AES = "U1lOVEgtQUVTLTAwMDAwMDAx";
    private static final String AESG = "U1lOQUVTRzAwMDAwMDE";
    private static final String AESG_TAIL = "U1lOVEgtQUVTRy0wMDE";
    private static final String ENCRYPTED_12 = "Xq7Lm2Np9Rt4";
    /** The part after a "/" inside an encrypted value of a name: the title of a note without. */
    private static final String SPLIT_TAIL = "7Kp2mXv9TnQwL4s";
    private static final String SPLIT_NAME = "notes/AES-q8Zr/" + SPLIT_TAIL;
    /** Single-line secrets, one hand-written note each with the secret in every field. */
    private static final List<String> FIELD_SECRETS = Stream.concat(forms(PASSWORD).stream(),
        Stream.of(JCEKS, PKCS12, "AES-" + AES, "AESGs1-y+" + AESG + ":U1lOVEhJVjE:" + AESG_TAIL
            + "==", "password: leakFieldColon", "password=leakFieldEquals",
            "\"token\": \"leakFieldJson\"", "apikey=\"leakFieldAttribute\"")).toList();
    /** Secrets that are a valid file name on every platform. */
    private static final List<String> NAME_SECRETS = List.of(
        URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8), JCEKS, "AES-" + AES,
        "password=leakNameValue");
    private static final String BLOCKS = PEM + """

        A YAML block:
        secret: |
          leakBlockValue
          leakBlockSecondLine
        An XML element over lines:
        <password>
          leakXmlValue
        </password>
        <Property name="Login.Password">leakPropertyValue</Property>
        <Property name="x" encrypted="true">%s</Property>
        <Field type="Password">leakTypeValue</Field>
        <api apikey="leakAttributeValue"/>
        A wrapped truststore:
        %s
        """.formatted(ENCRYPTED_12, Base64.getMimeEncoder(64, "\n".getBytes(
            StandardCharsets.US_ASCII)).encodeToString(PKCS12_BYTES));
    /** What must never be shown: every value and every form, and parts of the long ones. */
    private static final List<String> LEAKS = Stream.concat(forms(PASSWORD).stream(), Stream.of(
        PEM_BODY, JCEKS, JCEKS.substring(0, 12), PKCS12, PKCS12_LINE, AES, AESG,
        AESG_TAIL, ENCRYPTED_12, "leakFieldColon", "leakFieldEquals", "leakFieldJson",
        "leakFieldAttribute", "leakNameValue", "leakBlockValue", "leakBlockSecondLine",
        "leakXmlValue", "leakPropertyValue", "leakTypeValue", "leakAttributeValue",
        "leakNewValue", SPLIT_TAIL)).toList();

    @TempDir
    Path temp;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;
    private TestWiring wiring;
    private McpTestClient client;

    private static byte[] jceks() {
        byte[] content = new byte[96];
        Arrays.fill(content, 0, 4, (byte) 0xce);
        Arrays.fill(content, 4, content.length, (byte) 7);
        return content;
    }

    private static byte[] pkcs12() {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.store(out, "changeit".toCharArray());
            return out.toByteArray();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The spellings of {@code infra.SecretForms}: plain, JSON, XML, URL-encoded, Base64. */
    private static List<String> forms(String value) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(value);
        String json = value.replace("\\", "\\\\").replace("\"", "\\\"");
        forms.add(json);
        forms.add(json.replace("/", "\\/"));
        forms.add(json.replace("\u00e9", "\\u00e9"));
        forms.add(json.replace("\u00e9", "\\u00E9"));
        String xml = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        forms.add(xml);
        forms.add(xml.replace("\"", "&quot;").replace("'", "&apos;"));
        forms.add(xml.replace("\"", "&#34;").replace("'", "&#39;"));
        forms.add(xml.replace("\"", "&#x22;").replace("'", "&#x27;"));
        String url = URLEncoder.encode(value, StandardCharsets.UTF_8);
        for (String form : List.of(url, url.replace("+", "%20"))) {
            forms.add(form);
            StringBuilder lower = new StringBuilder(form);
            for (int i = form.indexOf('%'); i >= 0; i = form.indexOf('%', i + 1)) {
                lower.replace(i + 1, i + 3, form.substring(i + 1, i + 3).toLowerCase(Locale.ROOT));
            }
            forms.add(lower.toString());
        }
        forms.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        return List.copyOf(forms);
    }

    private static String quoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @BeforeEach
    void setUp() throws IOException {
        captureLogs();
        for (int i = 0; i < FIELD_SECRETS.size(); i++) {
            String secret = quoted(FIELD_SECRETS.get(i));
            note("field-" + i, "---\ntitle: " + secret + "\ntopics: [" + secret + ", plain]\n"
                + "source: " + secret + "\nlastWriter: " + secret + "\n---\nThe body holds "
                + FIELD_SECRETS.get(i) + " in a line.\n");
        }
        note("blocks", "---\ntitle: Blocks\ntopics: [plain]\n---\n" + BLOCKS);
        note(SPLIT_NAME, "A note without metadata: its title is its last name segment.\n");
        for (String secret : NAME_SECRETS) {
            note(secret, "A note whose name holds a secret.\n");
            note("kinds/" + secret, "A nested note whose name holds a secret.\n");
        }
        String yaml = """
            profile:
              name: acme
            workspace: '%s'
            auditDirectory: '%s'
            knowledge:
              directory: '%s'
              recording: true
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://localhost:1
            """.formatted(temp.resolve("workspace"), temp.resolve("audit"),
            temp.resolve("knowledge"));
        ProfileConfig config = new ConfigLoader(Map.of(), temp, false)
            .parse(yaml, temp.resolve("config.yaml")).config();
        wiring = TestWiring.of(config, TestSecrets.store(Map.of("INUBIT_ACME_DEV_USERNAME",
            "jdoe", "INUBIT_ACME_DEV_PASSWORD", PASSWORD)), new SecretScrubber(),
            Files::exists, false);
        client = McpTestClient.start(wiring.serverFactory("0.0.0-test"));
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (wiring != null) {
            wiring.close();
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
        logs.stop();
        context.getLogger("de.dadecker").setLevel(previousLevel);
    }

    private void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logs.setContext(context);
        logs.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
        Logger ours = context.getLogger("de.dadecker");
        previousLevel = ours.getLevel();
        ours.setLevel(Level.TRACE);
    }

    private void note(String name, String text) throws IOException {
        Path file = temp.resolve("knowledge").resolve(name + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private JsonNode read(Map<String, Object> arguments) {
        JsonNode result = client.callTool("read_knowledge", arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("structuredContent");
    }

    private JsonNode record(Map<String, Object> arguments) {
        return client.callTool("record_knowledge", arguments);
    }

    private static JsonNode toolError(JsonNode result) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        return JSON.readTree(result.path("content").get(0).path("text").asString())
            .path("error");
    }

    private static void assertNoLeak(String where, String text) {
        for (String leak : LEAKS) {
            assertThat(text).as(where + " must not contain " + leak).doesNotContain(leak);
        }
    }

    @Test
    void noSecretOfAHandWrittenNoteIsShownAndNoNewSecretIsWritten() throws IOException {
        String instructions = client.initialize().path("instructions").asString();
        List<JsonNode> hits = new ArrayList<>();
        int offset = 0;
        while (true) {
            JsonNode page = read(Map.of("limit", 50, "offset", offset));
            page.path("notes").forEach(hits::add);
            if (!page.has("nextOffset")) {
                break;
            }
            offset = page.path("nextOffset").asInt();
        }
        List<String> pages = new ArrayList<>();
        for (JsonNode hit : hits) {
            int from = 0;
            while (true) {
                JsonNode page = read(Map.of("ref", hit.path("ref").asString(), "offset", from));
                pages.add(page.toString());
                if (!page.has("nextOffset")) {
                    break;
                }
                from = page.path("nextOffset").asInt();
            }
        }
        Map<String, Integer> searches = new LinkedHashMap<>();
        for (String leak : LEAKS) {
            searches.put(leak, read(Map.of("query", leak)).path("total").asInt());
        }
        JsonNode topic = read(Map.of("topic", "plain", "limit", 50));
        JsonNode notFound = toolError(client.callTool("read_knowledge", Map.of("name",
            PASSWORD)));

        assertThat(instructions).contains("Notes (newest first):");
        int notes = FIELD_SECRETS.size() + 2 + 2 * NAME_SECRETS.size();
        assertThat(BLOCKS).as("the wrapped truststore holds the checked line")
            .contains(PKCS12_LINE);
        assertThat(hits).hasSize(notes);
        // an assignment masked inside a quoted value leaves its block malformed: shown as body
        assertThat(topic.path("total").asInt()).isPositive();
        assertThat(pages).allSatisfy(page -> assertThat(page).contains("***"));
        assertThat(searches).allSatisfy((leak, total) -> assertThat(total).as(leak).isZero());
        assertThat(notFound.path("code").asString()).isEqualTo("NOT_FOUND");
        assertNoLeak("instructions", instructions);
        hits.forEach(hit -> assertNoLeak("hit", hit.toString()));
        pages.forEach(page -> assertNoLeak("page", page));

        List<String> refusals = refusedWrites(hits);

        assertThat(refusals).allSatisfy(message -> assertNoLeak("refusal", message));
        try (Stream<Path> files = Files.walk(temp.resolve("knowledge"))) {
            assertThat(files.filter(Files::isRegularFile)
                .map(file -> temp.resolve("knowledge").relativize(file).toString())
                .filter(name -> name.startsWith("new"))).isEmpty();
        }
        assertNoLeak("output", client.rawOutput());
        List<String> audit = new ArrayList<>();
        try (Stream<Path> files = Files.list(temp.resolve("audit"))) {
            for (Path file : files.toList()) {
                audit.addAll(Files.readAllLines(file));
            }
        }
        assertThat(audit).isNotEmpty();
        audit.forEach(line -> assertNoLeak("audit", line));
        List<String> lines = logs.list.stream().map(event -> event.getFormattedMessage()
            + (event.getThrowableProxy() == null ? ""
                : ThrowableProxyUtil.asString(event.getThrowableProxy()))).toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("knowledge.read"));
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("knowledge.record"));
        lines.forEach(line -> assertNoLeak("log", line));
    }

    /** Every kind in new text and in a name refused; a harmless append written. */
    private List<String> refusedWrites(List<JsonNode> hits) {
        List<String> refusals = new ArrayList<>();
        JsonNode appended = record(Map.of("name", "field-0", "mode", "append", "content",
            "A harmless addition."));
        assertThat(appended.path("isError").asBoolean()).as(appended.toString()).isFalse();
        JsonNode field1 = hits.stream().filter(hit -> hit.path("name").asString()
            .equals("field-1")).findFirst().orElseThrow();
        JsonNode replace = toolError(record(Map.of("name", "field-1", "mode", "replace",
            "title", "T", "content", "clean", "versionToken",
            field1.path("versionToken").asString())));
        assertThat(replace.path("code").asString()).isEqualTo("PRECONDITION_FAILED");
        refusals.add(replace.toString());
        for (JsonNode hit : hits) {
            if (hit.path("name").asString().contains("***")) {
                JsonNode byRef = toolError(record(Map.of("ref", hit.path("ref").asString(),
                    "mode", "append", "content", "more")));
                assertThat(byRef.path("code").asString()).isEqualTo("PRECONDITION_FAILED");
                refusals.add(byRef.toString());
            }
        }
        List<String> secrets = new ArrayList<>(FIELD_SECRETS);
        secrets.addAll(List.of(PEM, "secret: |\n  leakNewValue\n", "<password>\n  leakNewValue\n"
            + "</password>", "<P name=\"x\" encrypted=\"true\">" + ENCRYPTED_12 + "</P>"));
        for (String secret : secrets) {
            refusals.add(refused(Map.of("name", "new-content", "mode", "create", "title", "N",
                "content", "line one\n" + secret), "content line"));
            refusals.add(refused(Map.of("name", "field-0", "mode", "append", "content",
                secret), "content line"));
            if (!secret.contains("\n")) {
                refusals.add(refused(Map.of("name", "new-title", "mode", "create", "title",
                    secret, "content", "x"), "in title"));
                refusals.add(refused(Map.of("name", "new-topics", "mode", "create", "title",
                    "N", "topics", List.of("plain", secret), "content", "x"), "in topics"));
                refusals.add(refused(Map.of("name", secret, "mode", "create", "title", "N",
                    "content", "x"), "in name"));
                refusals.add(refused(Map.of("name", "new/../" + secret + ".md", "mode",
                    "create", "title", "N", "content", "x"), "in name"));
            }
        }
        return refusals;
    }

    private String refused(Map<String, Object> arguments, String where) {
        JsonNode error = toolError(record(arguments));
        assertThat(error.path("code").asString()).as(arguments.toString())
            .isEqualTo("INVALID_INPUT");
        // a too long value is refused for its length first, also without echo
        assertThat(error.path("message").asString()).as(arguments.toString())
            .containsAnyOf(where, "is longer than");
        return error.toString();
    }
}
