package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 002-T008: loading and validation of the {@code profile}, {@code terminology} and
 * {@code credentials} sections (002 contracts/configuration.md → Format, Terminology rules,
 * Startup validation).
 */
class ProfileSectionTest {

    private static final Path HOME = Path.of("/home/test");
    private static final String GROUPS = """
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://node1.dev.example.test:8443
        """;

    private static LoadedConfig load(String yaml) {
        return new ConfigLoader(Map.of(), HOME, false).parse(yaml, HOME.resolve("acme.yaml"));
    }

    private static ValidationReport validate(String yaml) {
        LoadedConfig loaded = load(yaml);
        Map<String, String> env = new HashMap<>();
        env.put(loaded.config().credentialPrefix() + "_DEV_USERNAME", "user");
        env.put(loaded.config().credentialPrefix() + "_DEV_PASSWORD", "secret-value");
        CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        return new ConfigValidator(path -> true, env, false, Path.of("/var/tmp"))
            .validate(loaded, credentials);
    }

    @Nested
    class Loading {

        @Test
        void allThreeSectionsAreParsed() {
            ProfileConfig config = load("""
                profile:
                  name: acme
                  description: ACME integration platform
                terminology:
                  group: { singular: Umgebung, plural: Umgebungen }
                  node: { singular: Knoten, plural: Knoten }
                credentials:
                  envPrefix: INUBIT_ACME_PROD
                """ + GROUPS).config();

            assertThat(config.profile().name()).isEqualTo("acme");
            assertThat(config.profile().description()).contains("ACME integration platform");
            assertThat(config.terminology().groupSingular()).isEqualTo("Umgebung");
            assertThat(config.terminology().groupPlural()).isEqualTo("Umgebungen");
            assertThat(config.terminology().nodeSingular()).isEqualTo("Knoten");
            assertThat(config.terminology().nodePlural()).isEqualTo("Knoten");
            assertThat(config.credentials().envPrefix()).contains("INUBIT_ACME_PROD");
        }

        @Test
        void theProfileInfoCarriesTheEffectiveValues() {
            ProfileInfo info = load("""
                profile:
                  name: acme
                  description: ACME integration platform
                terminology:
                  group: { singular: Stage, plural: Stages }
                  node: { singular: Server, plural: Servers }
                credentials:
                  envPrefix: INUBIT
                """ + GROUPS).config().profileInfo();

            assertThat(info.name()).isEqualTo("acme");
            assertThat(info.description()).contains("ACME integration platform");
            assertThat(info.terminology())
                .isEqualTo(new Terminology("Stage", "Stages", "Server", "Servers"));
            assertThat(info.credentialPrefix()).isEqualTo("INUBIT");
        }

        @Test
        void terminologyAndCredentialsAreOptionalWithTheDocumentedDefaults() {
            ProfileInfo info = load("profile:\n  name: acme\n" + GROUPS).config().profileInfo();

            assertThat(info.description()).isEmpty();
            assertThat(info.terminology()).isEqualTo(Terminology.DEFAULT);
            assertThat(Terminology.DEFAULT)
                .isEqualTo(new Terminology("group", "groups", "node", "nodes"));
            assertThat(info.credentialPrefix()).isEqualTo("INUBIT_ACME");
        }

        @Test
        void theDefaultPrefixNormalizesTheProfileName() {
            assertThat(ProfileInfo.defaultCredentialPrefix("acme")).isEqualTo("INUBIT_ACME");
            assertThat(ProfileInfo.defaultCredentialPrefix("acme-2")).isEqualTo("INUBIT_ACME_2");
            assertThat(ProfileInfo.defaultCredentialPrefix("9lives")).isEqualTo("INUBIT_9LIVES");
        }

        @Test
        void aTopLevelCredentialsSectionIsNotTreatedAsACredentialKey() {
            LoadedConfig loaded = load("profile:\n  name: acme\ncredentials:\n"
                + "  envPrefix: INUBIT\n" + GROUPS);

            assertThat(loaded.credentialKeys()).isEmpty();
            assertThat(loaded.config().credentials().envPrefix()).contains("INUBIT");
        }

        @ParameterizedTest
        @ValueSource(strings = {"credentials:\n", "credentials: null\n",
            "credentials: {}\n"})
        void anEmptyOrNullTopLevelCredentialsSectionIsTreatedAsAbsent(String section) {
            LoadedConfig loaded = load("profile:\n  name: acme\n" + section + GROUPS);

            assertThat(loaded.credentialKeys()).as("002 review G3").isEmpty();
            assertThat(loaded.config().credentials().envPrefix()).isEmpty();
            assertThat(loaded.config().profileInfo().credentialPrefix())
                .isEqualTo("INUBIT_ACME");
        }

        @ParameterizedTest
        @ValueSource(strings = {"credentials: secret-value\n", "credentials: \"\"\n",
            "credentials: [a, b]\n"})
        void aScalarOrListTopLevelCredentialsValueIsStillACredentialKey(String section) {
            LoadedConfig loaded = load("profile:\n  name: acme\n" + section + GROUPS);

            assertThat(loaded.credentialKeys()).containsExactly("credentials");
            assertThat(loaded.toString()).doesNotContain("secret-value");
        }

        @Test
        void aPasswordInsideTheCredentialsSectionIsStillRejectedAndNeverKept() {
            LoadedConfig loaded = load("profile:\n  name: acme\ncredentials:\n"
                + "  envPrefix: INUBIT\n  password: do-not-put-me-here\n" + GROUPS);

            assertThat(loaded.credentialKeys()).containsExactly("credentials.password");
            assertThat(loaded.toString()).doesNotContain("do-not-put-me-here");
        }

        @Test
        void credentialsBelowTheTopLevelAreStillCredentialKeys() {
            LoadedConfig loaded = load("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    credentials:
                      envPrefix: X
                    nodes:
                      - name: node1
                        baseUrl: https://node1.dev.example.test:8443
                """);

            assertThat(loaded.credentialKeys()).containsExactly("groups[0].credentials");
        }
    }

    @Nested
    class Validation {

        @Test
        void aValidProfileHasNoFindings() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                  description: "ACME – INUBIT integration platform"
                terminology:
                  group: { singular: Umgebung, plural: Umgebungen }
                  node: { singular: Knoten, plural: Knoten-Liste }
                credentials:
                  envPrefix: INUBIT
                """ + GROUPS);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).isEmpty();
        }

        @Test
        void aMissingProfileIsRefusedWhenLoadingWithTheMigrationGuide() {
            // 002-T034 (research D-12): no profile key is a file of feature 001 or one not fully
            // migrated; Format001DetectorTest covers the message
            assertThatThrownBy(() -> load(GROUPS)).isInstanceOf(ConfigException.class)
                .hasMessageContaining("profile (missing)")
                .hasMessageContaining("docs/migration-001-to-002.md");
        }

        @Test
        void anEmptyProfileIsAnError() {
            assertThat(validate("profile:\n" + GROUPS).errors()).singleElement().asString()
                .contains("profile.name", "required");
        }

        @Test
        void aProfileWithoutNameIsAnError() {
            assertThat(validate("profile:\n  description: x\n" + GROUPS).errors())
                .singleElement().asString().contains("profile.name", "required");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Acme", "-acme", "acme_1", "acme.prod", "a b",
            "abcdefghijklmnopqrstuvwxyz0123456"})
        void anInvalidProfileNameIsAnError(String name) {
            assertThat(validate("profile:\n  name: \"" + name + "\"\n" + GROUPS).errors())
                .singleElement().asString()
                .contains("profile.name", ProfileInfo.NAME_PATTERN.pattern());
        }

        @ParameterizedTest
        @ValueSource(strings = {"a", "acme", "acme-2", "9lives",
            "abcdefghijklmnopqrstuvwxyz012345"})
        void validProfileNamesAreAccepted(String name) {
            assertThat(validate("profile:\n  name: " + name + "\n" + GROUPS).errors()).isEmpty();
        }

        @Test
        void aDescriptionOfAtMost200CharsIsAccepted() {
            String yaml = "profile:\n  name: acme\n  description: " + "d".repeat(200) + "\n";

            assertThat(validate(yaml + GROUPS).errors()).isEmpty();
        }

        @Test
        void aDescriptionLongerThan200CharsIsAnError() {
            String yaml = "profile:\n  name: acme\n  description: " + "d".repeat(201) + "\n";

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("profile.description", "200");
        }

        @Test
        void aMultiLineDescriptionIsAnError() {
            String yaml = "profile:\n  name: acme\n  description: \"first\\nsecond\"\n";

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("profile.description", "single line");
        }

        @ParameterizedTest
        @ValueSource(strings = {"tab\\there", "bell\\u0007", "nul\\u0000x", "del\\u007f",
            "c1\\u0085", "esc\\u001b[31m"})
        void aDescriptionWithAControlCharacterIsAnError(String escaped) {
            String yaml = "profile:\n  name: acme\n  description: \"" + escaped + "\"\n";

            assertThat(validate(yaml + GROUPS).errors()).as("002 review G4").singleElement()
                .asString().contains("profile.description", "control characters");
        }

        @Test
        void theDescriptionLengthIsCountedInCodePoints() {
            String emoji = "\uD83D\uDE80"; // one code point, two chars
            String yaml200 = "profile:\n  name: acme\n  description: \"" + emoji.repeat(200)
                + "\"\n";
            String yaml201 = "profile:\n  name: acme\n  description: \"" + emoji.repeat(201)
                + "\"\n";

            assertThat(validate(yaml200 + GROUPS).errors()).as("002 review G4").isEmpty();
            assertThat(validate(yaml201 + GROUPS).errors()).singleElement().asString()
                .contains("profile.description", "200");
        }

        @Test
        void theProfileInfoRejectsControlCharactersAndCountsCodePoints() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ProfileInfo("acme",
                java.util.Optional.of("a\tb"), Terminology.DEFAULT, "INUBIT_ACME"));
            assertThat(new ProfileInfo("acme", java.util.Optional.of("\uD83D\uDE80".repeat(200)),
                Terminology.DEFAULT, "INUBIT_ACME").description()).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"group: {}", "group: { }", "node: {}"})
        void anExplicitlyEmptyTerminologyLevelIsAnError(String level) {
            String yaml = "profile:\n  name: acme\nterminology:\n  " + level + "\n";
            String key = "terminology." + level.substring(0, level.indexOf(':'));

            assertThat(validate(yaml + GROUPS).errors()).as("002 review G5")
                .containsExactly(
                    key + ".singular is missing: a given terminology level needs both singular"
                        + " and plural",
                    key + ".plural is missing: a given terminology level needs both singular"
                        + " and plural");
        }

        @Test
        void aNullTerminologyLevelTakesTheDefaults() {
            String yaml = "profile:\n  name: acme\nterminology:\n  group:\n";

            assertThat(validate(yaml + GROUPS).errors()).isEmpty();
            assertThat(load(yaml + GROUPS).config().profileInfo().terminology())
                .isEqualTo(Terminology.DEFAULT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1st", "_Stage", "Stage!", "Stage/Server", " Stage",
            "Abcdefghijklmnopqrstuvwxyz0123456"})
        void anInvalidTerminologyValueIsAnErrorNamingTheField(String value) {
            String yaml = "profile:\n  name: acme\nterminology:\n  group: { singular: \""
                + value + "\", plural: Stages }\n";

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("terminology.group.singular", Terminology.TERM_PATTERN.pattern());
        }

        @ParameterizedTest
        @ValueSource(strings = {"Stage", "Umgebung", "Ébène", "Stage 2", "Test-Stage", "Node_A",
            "Abcdefghijklmnopqrstuvwxyz012345"})
        void validTerminologyValuesAreAccepted(String value) {
            String yaml = "profile:\n  name: acme\nterminology:\n  group: { singular: \""
                + value + "\", plural: Stages }\n";

            assertThat(validate(yaml + GROUPS).errors()).isEmpty();
        }

        @Test
        void aLevelBlockNeedsBothSingularAndPlural() {
            String yaml = "profile:\n  name: acme\nterminology:\n  node: { singular: Knoten }\n";

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("terminology.node.plural", "missing");
        }

        @Test
        void groupAndNodeSingularsMustDifferCaseInsensitively() {
            String yaml = """
                profile:
                  name: acme
                terminology:
                  group: { singular: System, plural: Groups }
                  node: { singular: system, plural: Nodes }
                """;

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("terminology.group.singular", "terminology.node.singular", "differ");
        }

        @Test
        void groupAndNodePluralsMustDifferCaseInsensitively() {
            String yaml = """
                profile:
                  name: acme
                terminology:
                  group: { singular: Group, plural: Systems }
                  node: { singular: Node, plural: SYSTEMS }
                """;

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("terminology.group.plural", "terminology.node.plural", "differ");
        }

        @Test
        void aPartlyGivenTerminologyIsComparedWithTheDefaultsOfTheOtherLevel() {
            String yaml = "profile:\n  name: acme\nterminology:\n"
                + "  group: { singular: Node, plural: Nodes }\n";

            assertThat(validate(yaml + GROUPS).errors()).hasSize(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"inubit", "1INUBIT", "_INUBIT", "INUBIT-ACME", "INUBIT ACME",
            "A0123456789012345678901234567890123456789012345678901234567890123"})
        void anInvalidEnvPrefixIsAnError(String prefix) {
            String yaml = "profile:\n  name: acme\ncredentials:\n  envPrefix: \"" + prefix
                + "\"\n";

            assertThat(validate(yaml + GROUPS).errors()).singleElement().asString()
                .contains("credentials.envPrefix", ProfileInfo.PREFIX_PATTERN.pattern());
        }

        @ParameterizedTest
        @ValueSource(strings = {"I", "INUBIT", "INUBIT_ACME_2",
            "A012345678901234567890123456789012345678901234567890123456789012"})
        void validEnvPrefixesAreAccepted(String prefix) {
            String yaml = "profile:\n  name: acme\ncredentials:\n  envPrefix: " + prefix + "\n";

            assertThat(validate(yaml + GROUPS).errors()).isEmpty();
        }

        @Test
        void allProfileErrorsAreReportedTogether() {
            String yaml = """
                profile:
                  name: ACME
                  description: "two\\nlines"
                terminology:
                  group: { singular: "1", plural: Groups }
                  node: { singular: Node, plural: groups }
                credentials:
                  envPrefix: lower
                """;

            assertThat(validate(yaml + GROUPS).errors()).hasSize(5)
                .anySatisfy(error -> assertThat(error).contains("profile.name"))
                .anySatisfy(error -> assertThat(error).contains("profile.description"))
                .anySatisfy(error -> assertThat(error).contains("terminology.group.singular"))
                .anySatisfy(error -> assertThat(error).contains("terminology.node.plural"))
                .anySatisfy(error -> assertThat(error).contains("credentials.envPrefix"));
        }

        @Test
        void profileErrorsAreReportedTogetherWithOtherErrors() {
            String yaml = """
                profile:
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                """;

            assertThat(validate(yaml).errors())
                .anySatisfy(error -> assertThat(error).contains("profile.name"))
                .anySatisfy(error -> assertThat(error).contains("baseUrl is missing"));
        }
    }

    @Test
    void theProfileInfoRequiresAValidatedProfile() {
        ProfileConfig config = load("profile:\n" + GROUPS).config();

        assertThatIllegalArgumentException().isThrownBy(config::profileInfo)
            .withMessageContaining("profile.name");
    }
}
