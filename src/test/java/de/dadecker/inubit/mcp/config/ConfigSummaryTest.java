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
        return render(yaml, NO_DISK);
    }

    private String render(String yaml, WorkspaceDirectory.Preparer workspaces) {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, false).parse(yaml, SOURCE);
        CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        ValidationReport report =
            new ConfigValidator(exists, env, false, Path.of(System.getProperty("java.io.tmpdir")),
                source -> List.of(), workspaces).validate(loaded, credentials);
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

    // --- feature 004 T024: development and e2e (FR-005, US6 AS 4) ------------------------------

    @Test
    void eachNodeShowsItsDevelopmentConfirmationAndE2ePolicy() {
        String summary = render("""
            profile:
              name: acme
            groups:
              - name: test
                development:
                  enabled: true
                e2eTests: CONFIRM
                e2e:
                  soap:
                    baseUrl: https://inubit-test.example.test:8443/soap
                nodes:
                  - name: inubit01
                    baseUrl: https://inubit-test-01.example.test:8443
                  - name: inubit02
                    baseUrl: https://inubit-test-02.example.test:8443
                    development:
                      confirmation: CLIENT
                    e2eTests: FREE
              - name: prod
                production: true
                nodes:
                  - name: inubit01
                    baseUrl: https://inubit-prod-01.example.test:8443
            """);

        assertThat(summary).contains("test/inubit01: read-only, development: on (confirmation"
            + " SERVER), e2e: CONFIRM (https://inubit-test.example.test:8443/soap), cli:");
        assertThat(summary).contains("test/inubit02: read-only, development: on (confirmation"
            + " CLIENT), e2e: FREE (https://inubit-test.example.test:8443/soap), cli:");
        assertThat(summary).contains("prod/inubit01: read-only, development: off, e2e:"
            + " FORBIDDEN, cli:");
        // research D-26: there are no owner kinds any more
        assertThat(summary).doesNotContain("Owner kinds");
        assertThat(render(CONFIG)).as("a profile without development settings prints as"
            + " before").doesNotContain("Owner kinds", "development:", "e2e:");
    }

    // --- T034: the workspace line (FR-004) -------------------------------------------------------

    @Test
    void theWorkspaceLineSaysOkCreatedOrTheError() {
        Path workspace = HOME.resolve(".inubit-mcp/acme/workspace");

        assertThat(render(CONFIG)).contains("Workspace: " + workspace + " (ok)\n");
        assertThat(render(CONFIG, any -> new WorkspaceDirectory.Usable(true)))
            .contains("Workspace: " + workspace + " (created)\n");
        assertThat(render(CONFIG, any -> new WorkspaceDirectory.Unusable("The workspace "
            + any + " is not writable"))).contains("Workspace: " + workspace + " (The workspace "
                + workspace + " is not writable)\n");
        assertThat(render(CONFIG.replace("profile:\n  name: acme\n",
            "profile:\n  name: acme\nworkspace: relative/dir\n")))
            .containsPattern("Workspace: .*relative/dir \\(not usable: see the errors\\)\n");
    }

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

    // --- feature 005 T013: the stage chain (research D-2) -------------------------------------

    private static final String CHAIN = """
        profile:
          name: acme
        defaults:
          cliHome: /opt/client
        groups:
          - name: dev
            nodes: [ { name: node1, baseUrl: https://inubit-dev.example.test:8443 } ]
          - name: int
            deploy:
              from: dev
              exclude:
                - diagramGroup: GRP-SYS
                - name: "CFG_*"
                - repositoryPath: "/Root/*/stage/**"
            nodes:
              - { name: node1, baseUrl: https://inubit-int-1.example.test:8443 }
              - { name: node2, baseUrl: https://inubit-int-2.example.test:8443 }
          - name: qa
            deploy: { from: int }
            nodes: [ { name: node1, baseUrl: https://inubit-qa.example.test:8443 } ]
          - name: prod
            production: true
            deploy: { from: qa, mode: PACKAGE_ONLY }
            nodes: [ { name: node1, baseUrl: https://inubit-prod.example.test:8443 } ]
        """;

    @Test
    void showsTheChainsTheExclusionsAndTheDeployLineOfEachNode() {
        String summary = render(CHAIN);

        assertThat(summary).contains("Chains:\n  dev → int → qa → prod (package only)\n");
        assertThat(summary).contains("  int excludes: diagramGroup GRP-SYS, name CFG_*,"
            + " repositoryPath /Root/*/stage/**\n");
        assertThat(summary).doesNotContain("qa excludes", "prod excludes");
        assertThat(summary.lines().filter(line -> line.contains("Node int/node")))
            .hasSize(2).allMatch(line -> line.contains(", deploy: from dev (EXECUTE)"));
        assertThat(summary.lines().filter(line -> line.contains("Node prod/node1")).findFirst())
            .get().asString().contains(", deploy: from qa (PACKAGE_ONLY)");
        assertThat(summary.lines().filter(line -> line.contains("Node dev/node1")).findFirst())
            .get().asString().doesNotContain("deploy:");
    }

    @Test
    void severalChainsAreSeveralLines() {
        String summary = render(CHAIN.replace("deploy: { from: int }", "deploy: { from: dev }"));

        assertThat(summary)
            .contains("Chains:\n  dev → int\n  dev → qa → prod (package only)\n");
    }

    @Test
    void aProfileWithoutDeployPrintsAsBefore() {
        assertThat(render(CONFIG)).doesNotContain("Chains:", "deploy:", "excludes");
    }

    @Test
    void anInvalidChainIsNotDrawnButReported() {
        String summary = render(CHAIN.replace("    nodes: [ { name: node1, baseUrl:"
            + " https://inubit-dev.example.test:8443 } ]", "    deploy: { from: qa }\n"
            + "    nodes: [ { name: node1, baseUrl: https://inubit-dev.example.test:8443 } ]"));

        assertThat(summary).contains("Chains: not shown, see the errors")
            .contains("The stage chain has a cycle");
    }
}

