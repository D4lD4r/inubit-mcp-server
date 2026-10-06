package de.dadecker.inubit.mcp.adapter.archive.v81;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.CliRecording;
import de.dadecker.inubit.mcp.adapter.archive.v81.ArtifactFixtures.SyntheticSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    /** Any property marked encrypted (Password, MaskedString, ...). */
    private static final Pattern ENCRYPTED = Pattern.compile(
        "<Property name=\"([^\"]*)\"[^>]*encrypted=\"true\"[^>]*>([^<]+)</Property>");
    /** Secrets without a type attribute (spike recordings of the development stage). */
    private static final Pattern UNTYPED_SECRET = Pattern.compile("<Property name=\"(SSLKeyStore"
        + "[A-Za-z]*|smime\\.keystore\\.data|smime\\.keystore\\.alias\\.password)\"[^>]*>([^<]+)"
        + "</Property>");
    private static final Pattern PASSWORD_LITERAL =
        Pattern.compile("<literal[^>]*isPassword=\"true\"[^>]*>([^<]+)</literal>");
    private static final Pattern PASSWORD_DEFAULT = Pattern.compile(
        "<Variable[^>]*type=\"is:password\"[^>]*>\\s*<DefaultValue>([^<]+)</DefaultValue>");
    private static final Pattern SOURCE_VARIABLES = Pattern.compile(
        "<Property name=\"xslt\\.sourceVariables\" type=\"Map\">(.*?)\n\t</Property>",
        Pattern.DOTALL);
    private static final Pattern LEAF = Pattern.compile(
        "<Property name=\"([^\"]*)\"[^>]*>([^<]+)</Property>");
    private static final Pattern LITERAL = Pattern.compile("<literal[^>]*>(.*?)</literal>",
        Pattern.DOTALL);
    private static final Pattern SAVED_MESSAGE = Pattern.compile(
        "<Property name=\"(xslt\\.source|xslt\\.target)\"[^>]*>(.*?)</Property>",
        Pattern.DOTALL);
    /** Upper bound of a literal or saved test message in a fixture. */
    private static final int MAX_SAMPLE_CHARS = 2048;

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

    @Test
    void everySecretValueIsSynthetic() {
        Set<String> synthetic = ArtifactFixtures.syntheticSecrets().stream()
            .map(SyntheticSecret::value).collect(Collectors.toSet());
        List<String> unlisted = new ArrayList<>();
        for (String fixture : ArtifactFixtures.EXPORTS) {
            ArtifactFixtures.entries(fixture).forEach((entry, bytes) -> {
                String text = new String(bytes, StandardCharsets.UTF_8);
                String at = fixture + "!" + entry;
                for (Pattern pattern : List.of(PASSWORD, KEYSTORE, ENCRYPTED, UNTYPED_SECRET)) {
                    pattern.matcher(text).results().filter(m -> !synthetic.contains(m.group(2)))
                        .forEach(m -> unlisted.add(at + " " + m.group(1)));
                }
                for (Pattern pattern : List.of(PASSWORD_LITERAL, PASSWORD_DEFAULT)) {
                    pattern.matcher(text).results().filter(m -> !synthetic.contains(m.group(1)))
                        .forEach(m -> unlisted.add(at + " " + pattern.pattern()));
                }
                SOURCE_VARIABLES.matcher(text).results().forEach(map -> LEAF.matcher(map.group(1))
                    .results().filter(leaf -> synthetic.stream().noneMatch(leaf.group(2)::contains))
                    .forEach(leaf -> unlisted.add(at + " xslt.sourceVariables/" + leaf.group(1))));
                Matcher aes = AES_VALUE.matcher(text);
                while (aes.find()) {
                    if (!synthetic.contains(aes.group())) {
                        unlisted.add(at + " AES value");
                    }
                }
            });
        }
        assertThat(unlisted).as("secret values not listed as synthetic").isEmpty();
    }

    /**
     * Literals and saved test messages are where recorded business messages hide: each one in
     * the exports is small and visibly synthetic ({@code fixture}, {@code synthetic} or an
     * {@code example.test} host).
     */
    @Test
    void literalsAndSavedTestMessagesAreSmallAndSynthetic() {
        List<String> suspicious = new ArrayList<>();
        for (String fixture : ArtifactFixtures.EXPORTS) {
            ArtifactFixtures.entries(fixture).forEach((entry, bytes) -> {
                String text = new String(bytes, StandardCharsets.UTF_8);
                LITERAL.matcher(text).results().map(m -> m.group(1))
                    .filter(value -> !isSmallAndSynthetic(value))
                    .forEach(value -> suspicious.add(fixture + "!" + entry + " literal ("
                        + value.length() + " chars)"));
                SAVED_MESSAGE.matcher(text).results()
                    .filter(m -> !m.group(2).isEmpty() && !isSmallAndSynthetic(m.group(2)))
                    .forEach(m -> suspicious.add(fixture + "!" + entry + " " + m.group(1) + " ("
                        + m.group(2).length() + " chars)"));
            });
        }
        assertThat(suspicious).isEmpty();
    }

    private static boolean isSmallAndSynthetic(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.length() <= MAX_SAMPLE_CHARS && (lower.contains("fixture")
            || lower.contains("synthetic") || lower.contains("example.test"));
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
    @CsvSource({"module-one.zip, module/module-0023.xml", "module-smime.zip, module/module-0029.xml"})
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
