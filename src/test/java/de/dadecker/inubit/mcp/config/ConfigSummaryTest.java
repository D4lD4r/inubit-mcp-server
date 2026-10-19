package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        return render(yaml, workspaces, Map.of());
    }

    private String render(String yaml, Map<NodeId, ClientVersion> clients) {
        return render(yaml, NO_DISK, clients);
    }

    private String render(String yaml, WorkspaceDirectory.Preparer workspaces,
        Map<NodeId, ClientVersion> clients) {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, false).parse(yaml, SOURCE);
        CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        ValidationReport report =
            new ConfigValidator(exists, env, false, Path.of(System.getProperty("java.io.tmpdir")),
                source -> List.of(), workspaces).validate(loaded, credentials);
        return new ConfigSummary(exists, false).render(loaded, credentials, report, clients);
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
            + " SERVER), repository: read-only, e2e: CONFIRM (https://inubit-test.example.test:8443/soap), cli:");
        assertThat(summary).contains("test/inubit02: read-only, development: on (confirmation"
            + " CLIENT), repository: read-only, e2e: FREE (https://inubit-test.example.test:8443/soap), cli:");
        assertThat(summary).contains("prod/inubit01: read-only, development: off, e2e:"
            + " FORBIDDEN, cli:");
        // research D-26: there are no owner kinds any more
        assertThat(summary).doesNotContain("Owner kinds");
        assertThat(render(CONFIG)).as("a profile without development settings prints as"
            + " before").doesNotContain("Owner kinds", "development:", "e2e:");
    }

    // --- feature 008 T016: the repository switch (contracts/configuration-delta.md) ------------

    @Test
    void developmentNodesShowTheEffectiveRepositorySwitch() {
        String summary = render("""
            profile:
              name: acme
            defaults:
              development:
                repository: WRITE
            groups:
              - name: dev
                development:
                  enabled: true
                nodes:
                  - name: node1
                    baseUrl: https://inubit-dev-01.example.test:8443
                  - name: node2
                    baseUrl: https://inubit-dev-02.example.test:8443
                    development:
                      repository: READ_ONLY
              - name: test
                nodes:
                  - name: node1
                    baseUrl: https://inubit-test-01.example.test:8443
            """);

        assertThat(summary).contains("dev/node1: read-only, development: on (confirmation"
            + " SERVER), repository: write, e2e: FORBIDDEN, cli:");
        assertThat(summary).contains("dev/node2: read-only, development: on (confirmation"
            + " SERVER), repository: read-only, e2e: FORBIDDEN, cli:");
        assertThat(summary.lines().filter(line -> line.contains("Node test/node1")).findFirst())
            .as("the inherited WRITE has no effect and is not shown off the development stage")
            .get().asString().contains("development: off, e2e: FORBIDDEN")
            .doesNotContain("repository:");
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
            + "cli: available, inubit: AUTO, username ← INUBIT_ACME_TEST_USERNAME,"
            + " password ← INUBIT_ACME_TEST_PASSWORD");
        assertThat(summary).contains("test/inubit02: read-only, cli: available, inubit: AUTO, "
            + "username ← INUBIT_ACME_TEST_USERNAME,"
            + " password ← INUBIT_ACME_TEST_INUBIT02_PASSWORD");
        assertThat(summary).contains("prod/inubit01: read-only (production, no productionOptIn), "
            + "cli: unavailable, inubit: AUTO, username ← INUBIT_ACME_PROD_USERNAME,"
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
            + "cli: unavailable, inubit: AUTO, username ← INUBIT_ACME_PROD_USERNAME,"
            + " password ← (missing)");
        assertThat(summary).contains("Errors:", "INUBIT_ACME_PROD_INUBIT01_PASSWORD");
        assertThat(summary).contains("Warnings:", "dev/inubit01: versionLine V9_X: write tools are"
            + " refused on this node (INUBIT 9.x is not supported for writes yet); read tools run"
            + " unverified");
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

    @Test
    void aChainFromAnUnknownGroupIsNotDrawn() {
        // stage 1 review #6: only a chain without chain errors is drawn
        String summary = render(CHAIN.replace("deploy: { from: int }", "deploy: { from: test }"));

        assertThat(summary).contains("Chains: not shown, see the errors")
            .doesNotContain("test → qa");
    }

    // --- feature 009 T022: the version setting and the client (FR-011a, research D-13) ---------

    private static final String PINS = """
        profile:
          name: acme
        groups:
          - name: test
            cli:
              home: /opt/client
            nodes:
              - name: node1
                baseUrl: https://inubit-dev-01.example.test:8443
                versionLine: "8.1.17"
              - name: node2
                baseUrl: https://inubit-dev-02.example.test:8443
                versionLine: "8.1.17"
              - name: node3
                baseUrl: https://inubit-dev-03.example.test:8443
                versionLine: V8_1
              - name: node4
                baseUrl: https://inubit-dev-04.example.test:8443
          - name: prod
            production: true
            versionLine: "8.1.17"
            nodes:
              - name: node1
                baseUrl: https://inubit-prod-01.example.test:8443
        """;

    private static ClientVersion client(String version, String from, String to) {
        return new ClientVersion(InubitVersion.parse(version).orElseThrow(),
            Optional.ofNullable(from).flatMap(InubitVersion::parse),
            Optional.ofNullable(to).flatMap(InubitVersion::parse));
    }

    @Test
    void eachNodeShowsItsVersionSettingAndItsClientAgainstAConcretePin() {
        String summary = render(PINS, Map.of(
            NodeId.parse("test/node1"), client("8.1.17", "4.0.1", "8.1.17"),
            NodeId.parse("test/node2"), client("8.1.18", "4.0.1", "8.1.18"),
            NodeId.parse("test/node3"), client("8.1.17", null, null)));

        assertThat(summary).contains("test/node1: read-only, cli: available, inubit: 8.1.17,"
            + " client: 8.1.17 (4.0.1–8.1.17), client vs pin: match, username ←");
        assertThat(summary).contains("test/node2: read-only, cli: available, inubit: 8.1.17,"
            + " client: 8.1.18 (4.0.1–8.1.18), client vs pin: mismatch, username ←");
        assertThat(summary).as("without a concrete pin there is nothing to compare with (FR-011a);"
            + " a client without declared range shows its version only")
            .contains("test/node3: read-only, cli: available, inubit: V8_1, client: 8.1.17,"
                + " server version: determined at runtime, username ←");
        assertThat(summary).as("no client result (failed probe, reported as a warning)")
            .contains("test/node4: read-only, cli: available, inubit: AUTO, username ←");
        assertThat(summary).as("no StartCLI installation: no client")
            .contains("prod/node1: read-only, cli: unavailable, inubit: 8.1.17, username ←");
        assertThat(summary).contains("Result: OK");
    }

    @Test
    void aClientWithoutPatchIsNotComparableWithThePin() {
        String summary = render(PINS, Map.of(NodeId.parse("test/node1"),
            client("8.1", "4.0.1", "8.1.17")));

        assertThat(summary).contains("inubit: 8.1.17, client: 8.1 (4.0.1–8.1.17), client vs pin:"
            + " not comparable, username ←");
    }
}
