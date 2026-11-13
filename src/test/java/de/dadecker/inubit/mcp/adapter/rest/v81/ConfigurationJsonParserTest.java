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
 * T017, T025 (feature 022, US1): the configuration of {@code GET /ibis/rest/configurations}, by
 * section, against the recording {@code admin-configurations.json} of an 8.1.17 server (T014, with
 * three planted test secrets) and short documents for what the recording does not hold (an
 * {@code encrypted} flag, key material, an empty section, {@code null}).
 */
class ConfigurationJsonParserTest {

    private static final NodeId DEV = NodeId.parse("dev/node1");
    private static final int MAX_VALUES = 100;
    private static final int MAX_CHARS = 50_000;
    /** The planted secrets of the recording (fixtures/v8_1/cli/README.md). */
    private static final String[] PLANTED = {"synthetic-secret-0401", "synthetic-secret-0402",
        "synthetic-secret-0403"};

    private static ConfigurationView fixture(String section) {
        return ConfigurationJsonParser.parse(DEV,
            RestFixtures.bytes("admin-configurations.json"),
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

        assertThat(view.sections()).hasSize(101)
            .startsWith("TracingMaxBackupIndex", "NotifyWorkflow", "AlarmSystem")
            .endsWith("ProxyPort", "SchemaPoolSize", "ServerRepositoryHTTPDirList");
        assertThat(view.section()).isEmpty();
        assertThat(view.values()).isEmpty();
        assertThat(view.maskedCount()).isZero();
        assertThat(view.truncated()).isFalse();
        assertThat(ResultJson.mapper().writeValueAsString(view)).doesNotContain(PLANTED)
            .doesNotContain("proxy-user-0001", "example.test");
    }

    @Test
    void theDocumentedRootKeyIsAcceptedToo() {
        ConfigurationView view = parse("{\"SeverConfiguration\": {\"GUIThreadsCount\": 10,"
            + " \"SNMPProperties\": {\"SNMPPort\": \"162\"}}}", "SNMPProperties");

        assertThat(view.section()).contains("SNMPProperties");
        assertThat(view.values()).containsExactly(Leaf.shown("/SNMPPort", "162"));
        assertThat(parse("{\"SeverConfiguration\": {\"GUIThreadsCount\": 10}}", null)
            .sections()).containsExactly("GUIThreadsCount");
    }

    @Test
    void oneSectionIsFlattenedToItsLeavesWithArrayElementsByIndex() {
        ConfigurationView view = fixture("DeleteStaleData");

        assertThat(view.sections()).isEmpty();
        assertThat(view.section()).contains("DeleteStaleData");
        assertThat(view.values()).containsExactly(
            Leaf.shown("/AutomaticStaleDataDeletion", "true"),
            Leaf.shown("/StaleDataDeletionTimePeriod", "30"));
        assertThat(view.maskedCount()).isZero();
        assertThat(view.truncated()).isFalse();
        assertThat(fixture("MailConnectorSupportedTLSversion").values()).containsExactly(
            Leaf.shown("/0", "TLSv1"), Leaf.shown("/1", "TLSv1.1"), Leaf.shown("/2", "TLSv1.2"));
        assertThat(fixture("JschConfigurationType").values()).startsWith(
            Leaf.shown("/MaxAuthTries", "6"),
            Leaf.shown("/prefer_known_host_key_types", "false"),
            Leaf.shown("/dhgex_max", "8192"),
            Leaf.shown("/PubkeyAcceptedAlgorithms/0", "ssh-ed25519"));
    }

    @Test
    void aScalarSectionIsOneLeafWithAnEmptyPath() {
        assertThat(fixture("GUIThreadsCount").values()).containsExactly(Leaf.shown("", "25"));
        assertThat(fixture("NotifyWorkflow").values()).containsExactly(Leaf.shown("", ""));
    }

    @Test
    void anEmptySectionHasNoLeaves() {
        // the recording has no empty section (T014)
        ConfigurationView view = parse("{\"ServerConfiguration\": {\"TracingCustom\": {}}}",
            "TracingCustom");

        assertThat(view.section()).contains("TracingCustom");
        assertThat(view.values()).isEmpty();
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void aKeyWithASlashIsEscapedInThePathAsInAJsonPointer() {
        assertThat(fixture("MimetypesFileType").values()).startsWith(
            Leaf.shown("/application~1xml", "xml XML pattern Pattern unittest UNITTEST report"
                + " REPORT"), Leaf.shown("/image~1png", "png PNG"));
        assertThat(parse("{\"SeverConfiguration\": {\"S\": {\"a~b\": \"x\"}}}", "S").values())
            .containsExactly(Leaf.shown("/a~0b", "x"));
    }

    @Test
    void aJsonNullIsShownAsTheTextNullNeverAsMasked() {
        // the recording has no null value (T014)
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
    void aSecretNameOnThePathMasksTheLeafAndItsValueNeverLeaves() {
        ConfigurationView smtp = fixture("SMTPProperties");
        assertThat(smtp.values()).contains(Leaf.shown("/ErrorUser", ""),
            Leaf.masked("/ErrorPassword"), Leaf.shown("/ErrorPort", "25"));
        assertThat(smtp.maskedCount()).isOne();

        ConfigurationView user = fixture("ProcessUserServer");
        assertThat(user.values()).contains(Leaf.shown("/Login", "jdoe"),
            Leaf.masked("/ProcessUserImTenantAdminCredentials/Value"));
        assertThat(user.maskedCount()).isOne();
    }

    @Test
    void anEncryptedFlagMasksItsSiblingsAndTheFlagStays() {
        // the recording has no encrypted flag (T014); the secret rules still apply (FR-010)
        ConfigurationView view = parse("{\"ServerConfiguration\": {\"LDAPProperties\": {"
            + "\"LDAPHost\": \"ldap.example.invalid\", \"BindValue\": {\"encrypted\": true,"
            + " \"value\": \"synthetic-secret-0303\"}}}}", "LDAPProperties");

        assertThat(view.values()).containsExactly(
            Leaf.shown("/LDAPHost", "ldap.example.invalid"),
            Leaf.shown("/BindValue/encrypted", "true"),
            Leaf.masked("/BindValue/value"));
        assertThat(view.maskedCount()).isOne();
        assertThat(view.toString()).doesNotContain("synthetic-secret-0303");
    }

    @Test
    void keyMaterialIsMaskedWhateverItsName() {
        // the recording has no key material (T014); the secret rules still apply (FR-010)
        ConfigurationView view = parse("{\"ServerConfiguration\": {\"SystemProperties\": {"
            + "\"synthetic.timeout\": \"36000\", \"synthetic.trust.material\": \"-----BEGIN"
            + " PRIVATE KEY-----\\nMIIBsyntheticsecret0304\\n-----END PRIVATE KEY-----\"}}}",
            "SystemProperties");

        assertThat(view.values()).containsExactly(Leaf.shown("/synthetic.timeout", "36000"),
            Leaf.masked("/synthetic.trust.material"));
        assertThat(view.maskedCount()).isOne();
        assertThat(view.toString()).doesNotContain("MIIBsyntheticsecret0304");
    }

    @Test
    void aSectionWithASecretNameIsMaskedAsAWhole() {
        assertThat(fixture("ProxyPassword").values()).containsExactly(Leaf.masked(""));
        ConfigurationView pattern = fixture("UserPasswordLimitPattern");
        assertThat(pattern.values()).containsExactly(Leaf.masked(""));
        assertThat(pattern.maskedCount()).isOne();
    }

    @Test
    void noPlantedSecretLeavesInAnySection() {
        for (String section : fixture(null).sections()) {
            ConfigurationView view = fixture(section);

            assertThat(ResultJson.mapper().writeValueAsString(view)).as(section)
                .doesNotContain(PLANTED);
            assertThat(view.toString()).as(section).doesNotContain(PLANTED);
        }
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
