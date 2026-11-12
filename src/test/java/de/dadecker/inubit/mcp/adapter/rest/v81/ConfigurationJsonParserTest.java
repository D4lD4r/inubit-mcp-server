package de.dadecker.inubit.mcp.adapter.rest.v81;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.rest.RestFixtures;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView.Leaf;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.ResultJson;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T017 (feature 022, US1): the configuration of {@code GET /ibis/rest/configurations}, by section.
 * The fixture {@code admin-configurations-reconstructed.json} is shortened from the vendor example
 * with planted secrets until T014 records the real answer.
 */
class ConfigurationJsonParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final int MAX_VALUES = 100;
    private static final int MAX_CHARS = 50_000;
    private static final String[] PLANTED = {"synthetic-secret-0301", "synthetic-secret-0302",
        "synthetic-secret-0303", "synthetic-secret-0304", "PRIVATE KEY", "(?=^.{8,255}$)"};

    private static ConfigurationView fixture(String section) {
        return ConfigurationJsonParser.parse(DEV,
            RestFixtures.bytes("admin-configurations-reconstructed.json"),
            Optional.ofNullable(section), MAX_VALUES, MAX_CHARS);
    }

    private static ConfigurationView parse(String json, String section, int maxValues,
        int maxChars) {
        return ConfigurationJsonParser.parse(DEV, json.getBytes(StandardCharsets.UTF_8),
            Optional.ofNullable(section), maxValues, maxChars);
    }

    private static ConfigurationView parse(String json, String section) {
        return parse(json, section, MAX_VALUES, MAX_CHARS);
    }

    @Test
    void withoutASectionOnlyTheSectionNamesAreListedInDocumentOrder() {
        ConfigurationView view = fixture(null);

        assertThat(view.sections()).containsExactly("JschConfigurationType", "GUIThreadsCount",
            "NotifyWorkflow", "UserPasswordLimitPattern", "ProcessUserServer", "SMTPProperties",
            "LDAPProperties", "SystemProperties", "MimetypesFileType", "TracingCustom");
        assertThat(view.section()).isEmpty();
        assertThat(view.values()).isEmpty();
        assertThat(view.maskedCount()).isZero();
        assertThat(view.truncated()).isFalse();
        assertThat(ResultJson.mapper().writeValueAsString(view)).doesNotContain(PLANTED)
            .doesNotContain("synthetic-login-0001", "example.invalid");
    }

    @Test
    void theRootKeyOfThePutBodyIsAcceptedToo() {
        ConfigurationView view = parse("{\"ServerConfiguration\": {\"GUIThreadsCount\": 10,"
            + " \"SNMPProperties\": {\"SNMPPort\": \"162\"}}}", "SNMPProperties");

        assertThat(view.section()).contains("SNMPProperties");
        assertThat(view.values()).containsExactly(Leaf.shown("/SNMPPort", "162"));
        assertThat(parse("{\"ServerConfiguration\": {\"GUIThreadsCount\": 10}}", null)
            .sections()).containsExactly("GUIThreadsCount");
    }

    @Test
    void oneSectionIsFlattenedToItsLeavesWithArrayElementsByIndex() {
        ConfigurationView view = fixture("JschConfigurationType");

        assertThat(view.sections()).isEmpty();
        assertThat(view.section()).contains("JschConfigurationType");
        assertThat(view.values()).containsExactly(
            Leaf.shown("/MaxAuthTries", "9"),
            Leaf.shown("/PubkeyAcceptedAlgorithms/0", "ssh-ed25519"),
            Leaf.shown("/PubkeyAcceptedAlgorithms/1", "rsa-sha2-512"),
            Leaf.shown("/compression.s2c", "none"),
            Leaf.shown("/enable_server_sig_algs", "true"));
        assertThat(view.maskedCount()).isZero();
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void aScalarSectionIsOneLeafWithAnEmptyPath() {
        assertThat(fixture("GUIThreadsCount").values()).containsExactly(Leaf.shown("", "10"));
        assertThat(fixture("NotifyWorkflow").values()).containsExactly(Leaf.shown("", ""));
    }

    @Test
    void anEmptySectionHasNoLeaves() {
        ConfigurationView view = fixture("TracingCustom");

        assertThat(view.section()).contains("TracingCustom");
        assertThat(view.values()).isEmpty();
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void aKeyWithASlashIsEscapedInThePathAsInAJsonPointer() {
        assertThat(fixture("MimetypesFileType").values()).containsExactly(
            Leaf.shown("/application~1xml", "xml XML"), Leaf.shown("/text~1csv", "csv CSV"));
        assertThat(parse("{\"SeverConfiguration\": {\"S\": {\"a~b\": \"x\"}}}", "S").values())
            .containsExactly(Leaf.shown("/a~0b", "x"));
    }

    @Test
    void aJsonNullIsShownAsTheTextNullNeverAsMasked() {
        ConfigurationView view = parse("{\"SeverConfiguration\": {\"Proxy\": {\"ProxyHost\":"
            + " null, \"ProxyPort\": \"\"}, \"ProxyUserName\": null}}", "Proxy");

        assertThat(view.values()).containsExactly(Leaf.shown("/ProxyHost", "null"),
            Leaf.shown("/ProxyPort", ""));
        assertThat(view.values()).noneMatch(Leaf::masked);
        assertThat(view.maskedCount()).isZero();
        assertThat(parse("{\"SeverConfiguration\": {\"ProxyUserName\": null}}", "ProxyUserName")
            .values()).containsExactly(Leaf.shown("", "null"));
    }

    @Test
    void aSecretNameMasksTheLeafAndItsValueNeverLeaves() {
        ConfigurationView view = fixture("ProcessUserServer");

        assertThat(view.values()).containsExactly(
            Leaf.shown("/Url", "http://process-user.example.invalid:8080"),
            Leaf.shown("/Login", "synthetic-login-0001"),
            Leaf.masked("/KeyCloakClientCredential"));
        assertThat(view.maskedCount()).isOne();

        ConfigurationView smtp = fixture("SMTPProperties");
        assertThat(smtp.values()).contains(Leaf.shown("/ErrorUser", "synthetic-user-0001"),
            Leaf.masked("/ErrorPassword"));
        assertThat(smtp.maskedCount()).isOne();
    }

    @Test
    void anEncryptedFlagMasksItsSiblingsAndTheFlagStays() {
        ConfigurationView view = fixture("LDAPProperties");

        assertThat(view.values()).containsExactly(
            Leaf.shown("/LDAPHost", "ldap.example.invalid"),
            Leaf.shown("/LoginDN", "cn=Synthetic,dc=example,dc=invalid"),
            Leaf.shown("/BindValue/encrypted", "true"),
            Leaf.masked("/BindValue/value"));
        assertThat(view.maskedCount()).isOne();
    }

    @Test
    void keyMaterialIsMaskedWhateverItsName() {
        ConfigurationView view = fixture("SystemProperties");

        assertThat(view.values()).containsExactly(Leaf.shown("/synthetic.timeout", "36000"),
            Leaf.masked("/synthetic.trust.material"));
        assertThat(view.maskedCount()).isOne();
    }

    @Test
    void aSectionWithASecretNameIsMaskedAsAWhole() {
        ConfigurationView view = fixture("UserPasswordLimitPattern");

        assertThat(view.values()).containsExactly(Leaf.masked(""));
        assertThat(view.maskedCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JschConfigurationType", "GUIThreadsCount", "NotifyWorkflow",
        "UserPasswordLimitPattern", "ProcessUserServer", "SMTPProperties", "LDAPProperties",
        "SystemProperties", "MimetypesFileType", "TracingCustom"})
    void noPlantedSecretLeavesInAnySection(String section) {
        ConfigurationView view = fixture(section);

        assertThat(ResultJson.mapper().writeValueAsString(view)).doesNotContain(PLANTED);
        assertThat(view.toString()).doesNotContain(PLANTED);
    }

    @Test
    void anUnknownSectionIsNotFoundAndNamesTheListingAsTheNextStep() {
        assertThatThrownBy(() -> fixture("NoSuchSection"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                ToolError error = e.error();
                assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
                assertThat(error.node()).contains(DEV);
                assertThat(error.message()).contains("NoSuchSection");
                assertThat(error.nextStep()).contains("configurationSection")
                    .contains("section names");
            });
    }

    @Test
    void theSectionNameMatchesExactly() {
        assertThatThrownBy(() -> fixture("guithreadscount"))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void theValuesAreCutAtTheMaximumNumberAndMarkedTruncated() {
        ConfigurationView view = parse(
            "{\"SeverConfiguration\": {\"S\": {\"a\": 1, \"b\": 2, \"c\": 3}}}", "S", 2,
            MAX_CHARS);

        assertThat(view.values()).containsExactly(Leaf.shown("/a", "1"), Leaf.shown("/b", "2"));
        assertThat(view.truncated()).isTrue();
        assertThat(parse("{\"SeverConfiguration\": {\"S\": {\"a\": 1, \"b\": 2}}}", "S", 2,
            MAX_CHARS).truncated()).isFalse();
    }

    @Test
    void theValuesAreCutAtTheCharBudgetAndMarkedTruncated() {
        String json = "{\"SeverConfiguration\": {\"S\": {\"a\": \"" + "x".repeat(1_000)
            + "\", \"b\": \"" + "y".repeat(1_000) + "\"}}}";
        int one = ResultJson.size(Leaf.shown("/a", "x".repeat(1_000)));

        ConfigurationView view = parse(json, "S", MAX_VALUES, one + one / 2);

        assertThat(view.values()).extracting(Leaf::path).containsExactly("/a");
        assertThat(view.truncated()).isTrue();
        assertThat(parse(json, "S", MAX_VALUES, 2 * one).truncated()).isFalse();
    }

    @Test
    void aLongValueIsCutWithTheTruncationMarker() {
        ConfigurationView view = parse("{\"SeverConfiguration\": {\"S\": {\"a\": \""
            + "x".repeat(ItemBounds.MAX_MESSAGE_CHARS + 500) + "\"}}}", "S");

        String value = view.values().getFirst().value().orElseThrow();
        assertThat(value).hasSize(ItemBounds.MAX_MESSAGE_CHARS)
            .endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(view.truncated()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aMaximumBelowOneValueIsRefused(int maxValues) {
        // T020 (T017 review): the limits come from the call's ResultLimiter, which never has
        // fewer than one item
        assertThatThrownBy(() -> parse("{\"SeverConfiguration\": {\"S\": {\"a\": 1}}}", "S",
            maxValues, MAX_CHARS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxValues must be >= 1: " + maxValues);
    }

    @Test
    void aLongPathIsCutWithTheTruncationMarker() {
        // T020 (T017 review): a path is a short text value (ItemBounds.MAX_FIELD_CHARS)
        String key = "k".repeat(ItemBounds.MAX_FIELD_CHARS + 50);
        ConfigurationView view = parse("{\"SeverConfiguration\": {\"S\": {\"" + key
            + "\": 1, \"password\": \"synthetic-secret-0306\", \"" + key
            + "x\": {\"password\": \"synthetic-secret-0307\"}}}}", "S");

        assertThat(view.values()).extracting(Leaf::path).allSatisfy(path ->
            assertThat(path.length()).isLessThanOrEqualTo(ItemBounds.MAX_FIELD_CHARS));
        assertThat(view.values().getFirst().path()).hasSize(ItemBounds.MAX_FIELD_CHARS)
            .startsWith("/kkk").endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(view.values().getFirst().value()).contains("1");
        assertThat(view.values().get(1)).isEqualTo(Leaf.masked("/password"));
        assertThat(view.values().get(2).path()).endsWith(ItemBounds.TRUNCATION_MARKER);
        assertThat(view.values().get(2).masked()).as("masked by its full path").isTrue();
        assertThat(view.maskedCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json synthetic-secret-0305", "[1, 2]",
        "{\"OtherRoot\": {\"a\": \"synthetic-secret-0305\"}}",
        "{\"SeverConfiguration\": \"synthetic-secret-0305\"}", ""})
    void anUnexpectedDocumentIsAnUnexpectedResponseWithoutItsContent(String body) {
        assertThatThrownBy(() -> parse(body, null))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().node()).contains(DEV);
                assertThat(e.error().toString()).doesNotContain("synthetic-secret-0305");
            });
    }
}
