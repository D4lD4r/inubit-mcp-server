package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 002-T033 (FR-018, SC-005, research D-12): a configuration in the format of feature 001
 * (top-level {@code stages}, nested {@code servers}, no {@code profile}) is refused before
 * binding, with a message that names every obsolete key and its replacement, the credential
 * prefix hint, the old audit directory and the migration guide, and never a configured value.
 * The exit code 1 of the server and of {@code --check-config} is covered by {@code MainTest}.
 */
class Format001DetectorTest {

    private static final Path HOME = Path.of("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/config.yaml");

    /**
     * The format of feature 001, with distinctive values that must never be echoed: stage
     * {@code alpha-stage}, server {@code srv-zeta}, host {@code hidden-host}, owner
     * {@code OWNERXYZ}.
     */
    private static final String FORMAT_001 = """
        logLevel: INFO
        auditDirectory: ~/.inubit-mcp/audit
        defaults:
          inventory:
            owner: OWNERXYZ
        stages:
          - name: alpha-stage
            write:
              enabled: true
            servers:
              - name: srv-zeta
                baseUrl: https://hidden-host.example.test:8443
          - name: beta-stage
            production: true
            servers:
              - name: srv-zeta
                baseUrl: https://hidden-host-2.example.test:8443
        """;

    private static final String PROFILE = """
        profile:
          name: acme
        """;

    private static String refusal(String yaml) {
        ConfigLoader loader = new ConfigLoader(Map.of(), HOME, false);
        Throwable thrown = catchThrowable(() -> loader.parse(yaml, SOURCE));
        assertThat(thrown).isInstanceOf(ConfigException.class);
        return thrown.getMessage();
    }

    @Test
    void aFileOfFeature001IsRefusedWithEveryObsoleteKeyAndItsReplacement() {
        String message = refusal(FORMAT_001);

        assertThat(message)
            .doesNotContain("Unknown key")
            .contains(SOURCE.toString())
            .contains("stages", "groups")
            .contains("servers", "stages[0].servers", "stages[1].servers", "nodes")
            .contains("profile", "missing");
    }

    @Test
    void theMessageGivesThePrefixHintTheOldAuditDirectoryAndTheGuide() {
        String message = refusal(FORMAT_001);

        assertThat(message)
            .contains("credentials.envPrefix: INUBIT")
            .contains("INUBIT_<STAGE>[_<SERVER>]_")
            .contains("~/.inubit-mcp/audit")
            .contains("~/.inubit-mcp/<profile.name>/audit")
            .contains("docs/migration-001-to-002.md");
    }

    @Test
    void theMessageNamesKeysAndPositionsButNoConfiguredValue() {
        String message = refusal(FORMAT_001);

        assertThat(message).doesNotContain("alpha-stage", "beta-stage", "srv-zeta", "hidden-host",
            "OWNERXYZ", "example.test");
    }

    @Test
    void theRefusalComesBeforeAnyOtherProblemOfTheFile() {
        // an unknown key and a credential key would otherwise be reported first or instead
        String message = refusal(FORMAT_001 + "frobnicate: true\n");

        assertThat(message).doesNotContain("frobnicate").contains("stages", "servers",
            "docs/migration-001-to-002.md");
    }

    @Test
    void topLevelStagesAloneIsRefusedAndOnlyThatKeyIsNamed() {
        String message = refusal(PROFILE + """
            stages:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            """);

        assertThat(message).contains("stages (top level): rename to groups",
                "docs/migration-001-to-002.md")
            .doesNotContain("servers").doesNotContain("missing").doesNotContain("move its entries");
    }

    @Test
    void nestedServersAloneIsRefusedWithItsPosition() {
        String message = refusal(PROFILE + """
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
              - name: qa
                servers:
                  - name: node1
                    baseUrl: https://node1.qa.example.test:8443
            """);

        assertThat(message).contains("groups[1].servers", "nodes", "docs/migration-001-to-002.md")
            .doesNotContain("groups[0]").doesNotContain("stages").doesNotContain("missing");
    }

    @Test
    void aMissingProfileAloneIsRefusedWithTheProfileHint() {
        String message = refusal("""
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            """);

        assertThat(message).contains("profile", "missing", "profile: {name: <name>}",
                "credentials.envPrefix: INUBIT", "docs/migration-001-to-002.md")
            .doesNotContain("stages").doesNotContain("servers");
    }

    @Test
    void mixedOldAndNewKeysAreRefusedWithEachOldKeyNamed() {
        String message = refusal(PROFILE + """
            groups:
              - name: dev
                servers:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            stages:
              - name: qa
                nodes:
                  - name: node1
                    baseUrl: https://node1.qa.example.test:8443
              - name: prod
                servers:
                  - name: node1
                    baseUrl: https://node1.prod.example.test:8443
            """);

        assertThat(message)
            .contains("stages (top level): move its entries into groups")
            .doesNotContain("rename to groups")
            .contains("groups[0].servers", "stages[1].servers")
            .doesNotContain("stages[0].servers")
            .doesNotContain("missing");
    }

    @Test
    void serversInsideAnIgnoredTopLevelXKeyIsNoOldKey() {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, false).parse(PROFILE + """
            x-old:
              servers: [one, two]
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            """, SOURCE);

        assertThat(loaded.config().profile().name()).isEqualTo("acme");
    }

    @Test
    void aFileOfFormatV2IsNotAffected() {
        assertThatThrownBy(() -> new ConfigLoader(Map.of(), HOME, false).parse(PROFILE + """
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.dev.example.test:8443
            frobnicate: true
            """, SOURCE))
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("Unknown key 'frobnicate'")
            .hasMessageNotContaining("migration");
    }
}
