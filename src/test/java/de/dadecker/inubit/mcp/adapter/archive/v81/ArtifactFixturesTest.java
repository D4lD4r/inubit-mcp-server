package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.CliRecording;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T002: the recorded artifact fixtures keep the archive structure INUBIT produced, carry only the
 * synthetic secret values listed in their README (Constitution II, research D-14), and the
 * StartCLI recordings of a missing diagram group and module are usable as contract fixtures.
 * Assertion messages name entries and kinds, never values.
 */
class ArtifactFixturesTest {

    private static final Pattern PASSWORD = Pattern.compile(
        "<Property name=\"([^\"]*)\"[^>]*type=\"Password\"[^>]*>([^<]+)</Property>");
    private static final Pattern KEYSTORE = Pattern.compile(
        "<Property name=\"([^\"]*)\"[^>]*type=\"KeyStore\"[^>]*>([^<]+)</Property>");
    private static final Pattern AES_VALUE =
        Pattern.compile("AES(?:-[A-Za-z0-9+/=]{8,}|G[A-Za-z0-9+/=:\\-]{8,})");
    private static final Pattern MODULE_NAME = Pattern.compile("<ModuleName>([^<]*)</ModuleName>");
    /** Secrets without a type attribute (spike recordings of the development stage). */
    private static final Set<String> UNTYPED_SECRETS = Set.of("SSLKeyStoreRemoteConnector",
        "SSLKeyStorePasswordRemoteConnector", "SSLKeyStore", "smime.keystore.data",
        "smime.keystore.alias.password");
    /** Upper bound of a literal or saved test message in a fixture. */
    private static final int MAX_SAMPLE_CHARS = 2048;
    /**
     * SHA-256 of approved synthetic samples whose text carries no marker: the saved test
     * messages {@code <fixture>xslt.source</fixture>} and {@code <fixture>xslt.target</fixture>}
     * of {@code Module-0020}.
     */
    private static final Set<String> APPROVED_SAMPLES = Set.of(
        "d094a3401b52dc91ebde4f3bf54b7219872ff8b9d2168af96fbb7b0074a41504",
        "c6e6d26991b26b34292ff1698c09f23d4e5ef9a1d09b5bc9093f6dcb2eb856f9");

    @Test
    void theReadmeListsEveryKindOfSecretForm() {
        assertThat(ArtifactFixtures.syntheticSecrets()).extracting(SyntheticSecret::kind)
            .contains("legacy", "AES-", "AESG", "plain", "keystore", "literal", "default",
                "sourceVariable");
    }

    @Test
    void everyListedValueOccursInItsFixture() {
        for (SyntheticSecret secret : ArtifactFixtures.syntheticSecrets()) {
            assertThat(ArtifactFixtures.EXPORTS).as("fixture of a %s value", secret.kind())
                .contains(secret.fixture());
            assertThat(texts(secret.fixture()))
                .as("%s value at %s %s", secret.kind(), secret.fixture(), secret.location())
                .anyMatch(text -> text.contains(secret.value()));
        }
    }

    /**
     * Every secret-bearing value — in ZIP entries, the nested {@code Repository.zip}, decoded
     * InternalDocuments and XML embedded in property values — is one of the README's synthetic
     * values. The XML is parsed, and the parsed declarations are counted against the raw texts, so
     * that a different formatting cannot make the guard pass without checking anything.
     */
    @Test
    void everySecretValueIsSynthetic() {
        Set<String> synthetic = ArtifactFixtures.syntheticSecrets().stream()
            .map(SyntheticSecret::value).collect(Collectors.toSet());
        List<String> unlisted = new ArrayList<>();
        int[] parsed = new int[3]; // sourceVariables maps, is:password variables, checked leaves
        int[] raw = new int[2];
        for (String fixture : ArtifactFixtures.EXPORTS) {
            FixtureScan scan = FixtureScan.of(fixture);
            for (FixtureScan.Source source : scan.sources()) {
                raw[0] += count(source.text(), "name=\"xslt.sourceVariables\"");
                raw[1] += count(source.text(), "type=\"is:password\"");
                Matcher aes = AES_VALUE.matcher(source.text());
                while (aes.find()) {
                    if (!synthetic.contains(aes.group())) {
                        unlisted.add(source.location() + " AES value");
                    }
                }
            }
            for (FixtureScan.Doc doc : scan.documents()) {
                FixtureScan.elements(doc.document().root(), element -> {
                    String name = FixtureScan.attribute(element, "name").orElse("");
                    String value = FixtureScan.value(element).orElse("").strip();
                    String at = doc.location() + " " + element.localName() + " " + name;
                    switch (element.localName()) {
                        case "Property" -> {
                            String type = FixtureScan.attribute(element, "type").orElse("");
                            boolean secret = type.equals("Password") || type.equals("KeyStore")
                                || FixtureScan.attribute(element, "encrypted")
                                    .filter("true"::equals).isPresent()
                                || UNTYPED_SECRETS.contains(name);
                            if (secret && !value.isEmpty() && !synthetic.contains(value)) {
                                unlisted.add(at);
                            }
                            if (name.equals("xslt.sourceVariables")) {
                                parsed[0]++;
                                FixtureScan.elements(element, leaf -> FixtureScan.value(leaf)
                                    .filter(v -> leaf != element && !v.isBlank())
                                    .ifPresent(v -> {
                                        parsed[2]++;
                                        if (synthetic.stream().noneMatch(v::contains)) {
                                            unlisted.add(at + "/" + FixtureScan
                                                .attribute(leaf, "name").orElse("?"));
                                        }
                                    }));
                            }
                        }
                        case "literal" -> {
                            if (FixtureScan.attribute(element, "isPassword")
                                .filter("true"::equals).isPresent() && !value.isEmpty()
                                && !synthetic.contains(value)) {
                                unlisted.add(at);
                            }
                        }
                        case "Variable" -> {
                            if (FixtureScan.attribute(element, "type")
                                .filter("is:password"::equals).isPresent()) {
                                parsed[1]++;
                                element.children().stream()
                                    .filter(c -> c instanceof XmlTree.Element e
                                        && e.localName().equals("DefaultValue"))
                                    .map(c -> FixtureScan.value((XmlTree.Element) c).orElse(""))
                                    .filter(v -> !v.isBlank() && !synthetic.contains(v.strip()))
                                    .forEach(v -> unlisted.add(at + " DefaultValue"));
                            }
                        }
                        default -> {
                            // no secret form
                        }
                    }
                });
            }
        }
        assertThat(unlisted).as("secret values not listed as synthetic").isEmpty();
        assertThat(parsed[0]).as("xslt.sourceVariables maps checked").isEqualTo(raw[0])
            .isPositive();
        assertThat(parsed[1]).as("is:password variables checked").isEqualTo(raw[1]).isPositive();
        assertThat(parsed[2]).as("sourceVariables values checked").isPositive();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Literals and saved test messages are where recorded business messages hide: each one in
     * the exports is small and approved ({@link #isApprovedSample}).
     */
    @Test
    void literalsAndSavedTestMessagesAreSmallAndSynthetic() {
        List<String> suspicious = new ArrayList<>();
        int[] checked = {0};
        for (String fixture : ArtifactFixtures.EXPORTS) {
            for (FixtureScan.Doc doc : FixtureScan.of(fixture).documents()) {
                FixtureScan.elements(doc.document().root(), element -> {
                    String name = FixtureScan.attribute(element, "name").orElse("");
                    boolean sample = element.localName().equals("literal")
                        || element.localName().equals("Property")
                        && (name.equals("xslt.source") || name.equals("xslt.target"));
                    String value = FixtureScan.value(element).orElse("");
                    if (sample && !value.isEmpty()) {
                        checked[0]++;
                        if (!isApprovedSample(value)) {
                            suspicious.add(doc.location() + " " + element.localName() + " "
                                + name + " (" + value.length() + " chars)");
                        }
                    }
                });
            }
        }
        assertThat(suspicious).isEmpty();
        assertThat(checked[0]).isPositive();
    }

    @Test
    void aShortNeutralizedRealisticMessageIsNotApproved() {
        // review: neutral hosts and namespaces are no proof that a message is synthetic
        String neutralized = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<isns:Envelope xmlns:isns=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<isns:Body><ns:notification xmlns:ns=\"urn:example:fixture:msg\""
            + " source=\"https://host01.example.test/fixture\">"
            + "<ns:id>00000000-0000-0000-0000-000000000001</ns:id><ns:price>1.00</ns:price>"
            + "<ns:quantity>1</ns:quantity></ns:notification></isns:Body></isns:Envelope>";

        assertThat(isApprovedSample(neutralized)).isFalse();
        assertThat(isApprovedSample(neutralized.replace("<ns:price>",
            "<ns:note>fixture</ns:note><ns:price>"))).isTrue();
        assertThat(isApprovedSample("<a>" + "fixture ".repeat(300) + "</a>")).as("too large")
            .isFalse();
        assertThat(isApprovedSample("a plain sentence of a real message")).isFalse();
        assertThat(isApprovedSample("sftp://host02.example.test:2222")).isTrue();
    }

    /**
     * Approved: at most {@value #MAX_SAMPLE_CHARS} characters, and either an XML document whose
     * <em>text content</em> (not names, namespaces or attributes) says {@code fixture} or
     * {@code synthetic}, a sample listed in {@link #APPROVED_SAMPLES}, or a single short token
     * (a value, never a message).
     */
    static boolean isApprovedSample(String value) {
        if (value.length() > MAX_SAMPLE_CHARS) {
            return false;
        }
        if (APPROVED_SAMPLES.contains(sha256(value))) {
            return true;
        }
        XmlTree.Document document;
        try {
            document = XmlTree.parse(value.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            String plain = value.strip();
            return plain.length() <= 100 && plain.chars().noneMatch(Character::isWhitespace)
                || plain.toLowerCase(Locale.ROOT).contains("fixture")
                || plain.toLowerCase(Locale.ROOT).contains("synthetic");
        }
        StringBuilder text = new StringBuilder();
        FixtureScan.elements(document.root(), element -> element.children().forEach(child -> {
            if (child instanceof XmlTree.Text t) {
                text.append(t.value()).append(' ');
            }
        }));
        String lower = text.toString().toLowerCase(Locale.ROOT);
        return lower.contains("fixture") || lower.contains("synthetic");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"grp-a.zip", "grp-b.zip"})
    void diagramGroupExportsKeepTheRecordedLayout(String fixture) {
        Map<String, byte[]> entries = ArtifactFixtures.entries(fixture);

        assertThat(List.copyOf(entries.keySet()).subList(0, 3))
            .containsExactly("archive.properties", "Repository.zip", "workflow/workflow.xml");
        assertThat(entries.keySet()).as("no directory entries").noneMatch(n -> n.endsWith("/"));
        assertModuleFilesMatchTheIndex(entries);
        assertThat(ArtifactFixtures.entries(entries.get("Repository.zip")).keySet())
            .allMatch(n -> n.endsWith(".dat") || n.endsWith(".xml"));
    }

    @ParameterizedTest
    @CsvSource({"module-one.zip, module/module-0023.xml",
        "module-smime.zip, module/module-0029.xml"})
    void theModuleOnlyExportsHaveTheEmptyWorkflowDirectoryEntry(String fixture, String module) {
        Map<String, byte[]> entries = ArtifactFixtures.entries(fixture);

        assertThat(entries.keySet()).containsExactly("archive.properties", "Repository.zip",
            "workflow/", "module/module.xml", module);
        assertThat(ArtifactFixtures.entries(entries.get("Repository.zip"))).isEmpty();
        assertModuleFilesMatchTheIndex(entries);
    }

    @Test
    void theRecordingsOfMissingArtifactsAreFailuresNamingTheArtifact() {
        CliRecording group = ArtifactFixtures.cliRecording("export-group-missing");
        CliRecording module = ArtifactFixtures.cliRecording("export-module-missing");

        assertThat(group.exitCode()).isEqualTo(1);
        assertThat(group.stdout())
            .contains("2-NOK: Workflow group not found: MCP-FIXTURE-NO-SUCH-GROUP")
            .doesNotContain("-OK:");
        assertThat(module.exitCode()).isEqualTo(1);
        assertThat(module.stdout())
            .contains("2-NOK: The module MCP-FIXTURE-NO-SUCH-MODULE not found")
            .doesNotContain("-OK:");
        // StartCLI writes everything to stdout; stderr holds only the JVM's notice
        assertThat(List.of(group.stderr(), module.stderr())).allSatisfy(stderr ->
            assertThat(stderr.strip()).isEqualTo(
                "Picked up JAVA_TOOL_OPTIONS: -Duser.language=en -Duser.country=US"));
    }

    /** Every index entry has {@code module/<lower-case name>.xml} and vice versa (spike §2). */
    private static void assertModuleFilesMatchTheIndex(Map<String, byte[]> entries) {
        String index = new String(entries.get("module/module.xml"), StandardCharsets.UTF_8);
        Set<String> expected = MODULE_NAME.matcher(index).results()
            .map(m -> "module/" + m.group(1).toLowerCase(Locale.ROOT) + ".xml")
            .collect(Collectors.toSet());
        Set<String> files = entries.keySet().stream()
            .filter(n -> n.startsWith("module/") && !n.equals("module/module.xml"))
            .collect(Collectors.toSet());
        assertThat(files).isNotEmpty().isEqualTo(expected);
    }

    /** The text of every entry of {@code fixture}, nested {@code Repository.zip} included. */
    private static List<String> texts(String fixture) {
        List<String> texts = new ArrayList<>();
        ArtifactFixtures.entries(fixture).forEach((entry, bytes) -> {
            texts.add(new String(bytes, StandardCharsets.UTF_8));
            if (entry.equals("Repository.zip")) {
                ArtifactFixtures.entries(bytes).values()
                    .forEach(b -> texts.add(new String(b, StandardCharsets.UTF_8)));
            }
        });
        return texts;
    }
}
