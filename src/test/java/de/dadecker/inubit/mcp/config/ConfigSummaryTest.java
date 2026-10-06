package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ConfigSummaryTest {

    /** The fictitious home is never touched: the workspace is not created (feature 003). */
    private static final WorkspaceDirectory.Preparer NO_DISK =
        workspace -> new WorkspaceDirectory.Usable(false);

    private static final Path HOME = Path.of("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/config.yaml");

    private final Map<String, String> env = new HashMap<>(Map.of(
        "INUBIT_ACME_TEST_USERNAME", "jdoe",
        "INUBIT_ACME_TEST_PASSWORD", "stage-secret",
        "INUBIT_ACME_TEST_INUBIT02_PASSWORD", "server-secret",
        "INUBIT_ACME_PROD_USERNAME", "jdoe",
        "INUBIT_ACME_PROD_PASSWORD", "prod-secret",
        "JAVA_HOME", "/opt/jdk"));
    private final Predicate<Path> exists = Set.of(Path.of("/opt/client/bin/startcli.sh"))::contains;

    private String render(String yaml) {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, false).parse(yaml, SOURCE);
        CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        ValidationReport report =
            new ConfigValidator(exists, env, false, Path.of(System.getProperty("java.io.tmpdir")),
                source -> List.of(), NO_DISK).validate(loaded, credentials);
        return new ConfigSummary(exists, false).render(loaded, credentials, report);
    }

    private static final String CONFIG = """
        profile:
          name: acme
        groups:
          - name: test
            write:
              enabled: true
            cli:
              home: /opt/client
            nodes:
              - name: inubit01
                baseUrl: https://inubit-test-01.example.test:8443
              - name: inubit02
                baseUrl: https://inubit-test-02.example.test:8443
                write:
                  enabled: false
          - name: prod
            production: true
            write:
              enabled: true
            nodes:
              - name: inubit01
                baseUrl: https://inubit-prod-01.example.test:8443
        """;

    @Test
    void listsEveryServerWithWriteFlagCliAvailabilityAndVariableNames() {
        String summary = render(CONFIG);

        assertThat(summary).contains("Configuration: " + SOURCE);
        assertThat(summary).contains("test/inubit01: write enabled (confirmation SERVER), "
            + "cli: available, username ← INUBIT_ACME_TEST_USERNAME,"
            + " password ← INUBIT_ACME_TEST_PASSWORD");
        assertThat(summary).contains("test/inubit02: read-only, cli: available, "
            + "username ← INUBIT_ACME_TEST_USERNAME,"
            + " password ← INUBIT_ACME_TEST_INUBIT02_PASSWORD");
        assertThat(summary).contains("prod/inubit01: read-only (production, no productionOptIn), "
            + "cli: unavailable, username ← INUBIT_ACME_PROD_USERNAME,"
            + " password ← INUBIT_ACME_PROD_PASSWORD");
        assertThat(summary).contains("Result: OK");
    }

    @Test
    void showsTheProfileTheTerminologyAndTheNodesUnderTheirGroups() {
        String summary = render(CONFIG.replace("  name: acme\n",
            "  name: acme\n  description: ACME test\n"));

        assertThat(summary).as("002-T018, contracts/configuration.md → --check-config")
            .contains("Profile: acme (ACME test)\n", "Terminology: group/groups, node/nodes\n",
                "Group test:\n  Node test/inubit01: write enabled",
                "\n  Node test/inubit02: read-only",
                "Group prod (production):\n  Node prod/inubit01: read-only");
    }

    @Test
    void showsTheCredentialPrefixAndTheAuditDirectoryOfTheProfile() {
        // 002 US2: what keeps this profile apart from others running side by side
        String summary = render(CONFIG);
        String explicit = render(CONFIG + "credentials:\n  envPrefix: INUBIT\n"
            + "auditDirectory: /var/audit/acme\n");

        assertThat(summary).contains("Credential variables: INUBIT_ACME_<GROUP>[_<NODE>]_USERNAME"
            + " / _PASSWORD\n", "Audit directory: " + HOME.resolve(".inubit-mcp/acme/audit")
            + "\n");
        assertThat(explicit).contains("Credential variables: INUBIT_<GROUP>[_<NODE>]_USERNAME",
            "Audit directory: " + Path.of("/var/audit/acme") + "\n");
    }

    @Test
    void neverPrintsValues() {
        String summary = render(CONFIG);

        assertThat(summary).doesNotContain("jdoe", "stage-secret", "server-secret", "prod-secret",
            "example.test");
    }

    @Test
    void reportsMissingVariablesErrorsAndWarnings() {
        env.remove("INUBIT_ACME_PROD_PASSWORD");

        String summary = render(CONFIG + """
          - name: dev
            versionLine: V9_X
            nodes:
              - name: inubit01
                baseUrl: https://dev.example.test
        """);

        assertThat(summary).contains("prod/inubit01: read-only (production, no productionOptIn), "
            + "cli: unavailable, username ← INUBIT_ACME_PROD_USERNAME, password ← (missing)");
        assertThat(summary).contains("Errors:", "INUBIT_ACME_PROD_INUBIT01_PASSWORD");
        assertThat(summary).contains("Warnings:", "unsupported in this version");
        assertThat(summary).contains("Result: FAILED (");
    }

    @Test
    void showsTheTrustStorePasswordSourceWhenUsed() {
        env.put("INUBIT_ACME_PROD_TRUSTSTORE_PASSWORD", "ts-secret");

        String summary = render(CONFIG);

        assertThat(summary).contains("trustStorePassword ← INUBIT_ACME_PROD_TRUSTSTORE_PASSWORD");
        assertThat(summary).doesNotContain("ts-secret");
    }
}
