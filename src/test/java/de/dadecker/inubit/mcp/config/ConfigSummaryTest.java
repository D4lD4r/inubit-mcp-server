package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.testing.FakeCredentialStore;
import de.dadecker.inubit.mcp.testing.FakeParameterStore;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConfigSummaryTest {

    /** The fictitious home is never touched: the workspace is not created (feature 003). */
    private static final WorkspaceDirectory.Preparer NO_DISK =
        workspace -> new WorkspaceDirectory.Usable(false);

    private static final Path HOME = Path.of("/home/test");
    private static final Path SOURCE = HOME.resolve(".config/inubit-mcp/config.yaml");

    /** Feature 011: no credential comes from the environment; the summary names the entries. */
    private final Map<String, String> env = new HashMap<>(Map.of("JAVA_HOME", "/opt/jdk"));
    /**
     * The credential store (feature 011, T017): group entries for the prefixes and groups of these
     * tests, so that every credential store node is complete.
     */
    private final Map<String, String> storeEntries = groupEntries();
    /** A scripted credential store instead of {@link #storeEntries}, if set. */
    private FakeCredentialStore credentialStore;

    private static Map<String, String> groupEntries() {
        Map<String, String> entries = new HashMap<>();
        for (String prefix : List.of("INUBIT_ACME", "INUBIT_TEAM", "INUBIT")) {
            for (String group : List.of("TEST", "PROD", "QA", "DEV")) {
                entries.put(prefix + "_" + group + "_USERNAME", "store-user");
                entries.put(prefix + "_" + group + "_PASSWORD", "store-Pw-1");
            }
        }
        return entries;
    }
    private final Predicate<Path> exists = Set.of(Path.of("/opt/client/bin/startcli.sh"))::contains;
    /** The Parameter Store of the AWS nodes (feature 010); unscripted names are not found. */
    private final FakeParameterStore store = new FakeParameterStore();
    /** The bound of each store call of {@link ParameterCheck}. */
    private Duration storeTimeout = ParameterCheck.TIMEOUT;

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
        ValidationReport report =
            new ConfigValidator(exists, env, false, Path.of(System.getProperty("java.io.tmpdir")),
                source -> List.of(), workspaces).validate(loaded);
        ParameterCheck.Result parameters =
            ParameterCheck.run(loaded.config().secretPlans().values(), store, storeTimeout);
        // feature 011 (T017): the credential store holds the entries named like the variables
        EntryCheck.Result entries = EntryCheck.run(loaded.config().resolvableNodes(),
            credentialStore != null ? credentialStore : TestSecrets.store(storeEntries),
            Duration.ofSeconds(5));
        return new ConfigSummary(exists, false).render(loaded,
            entries.addTo(parameters.addTo(report)), clients, parameters, entries);
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
        // feature 011 (T017): the entry found and its outcome
        assertThat(summary).contains("test/inubit01: write enabled (confirmation SERVER), "
            + "cli: available, inubit: AUTO, secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_TEST_USERNAME ok,"
            + " password ← INUBIT_ACME_TEST_PASSWORD ok\n");
        assertThat(summary).contains("test/inubit02: read-only, cli: available, inubit: AUTO, "
            + "secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_TEST_USERNAME ok,"
            + " password ← INUBIT_ACME_TEST_PASSWORD ok\n");
        assertThat(summary).contains("prod/inubit01: read-only (production, no productionOptIn), "
            + "cli: unavailable, inubit: AUTO, secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_PROD_USERNAME ok,"
            + " password ← INUBIT_ACME_PROD_PASSWORD ok\n");
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
        String explicit = render(CONFIG + "credentials:\n  prefix: INUBIT\n"
            + "auditDirectory: /var/audit/acme\n");

        assertThat(summary).contains("Credential store entries: service inubit-mcp, account"
            + " INUBIT_ACME_<GROUP>[_<NODE>]_<KIND> (macOS Keychain)\n", "Audit directory: "
            + HOME.resolve(".inubit-mcp/acme/audit") + "\n");
        assertThat(explicit).contains("Credential store entries: service inubit-mcp, account"
            + " INUBIT_<GROUP>[_<NODE>]_<KIND> (macOS Keychain)\n",
            "Audit directory: " + Path.of("/var/audit/acme") + "\n");
    }

    @Test
    void neverPrintsValues() {
        String summary = render(CONFIG);

        assertThat(summary).doesNotContain("jdoe", "stage-secret", "server-secret", "prod-secret",
            "example.test");
    }

    @Test
    void reportsErrorsAndWarnings() {
        String summary = render(CONFIG + """
          - name: dev
            versionLine: V9_X
            nodes:
              - name: inubit01
                baseUrl: http://dev.example.test
        """);

        assertThat(summary).contains("Errors:", "dev/inubit01: baseUrl uses http://");
        assertThat(summary).contains("Warnings:", "dev/inubit01: versionLine V9_X: write tools are"
            + " refused on this node (INUBIT 9.x is not supported for writes yet); read tools run"
            + " unverified");
        assertThat(summary).contains("Result: FAILED (");
    }

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
            + " client: 8.1.17 (4.0.1–8.1.17), client vs pin: match,"
            + " secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).contains("test/node2: read-only, cli: available, inubit: 8.1.17,"
            + " client: 8.1.18 (4.0.1–8.1.18), client vs pin: mismatch,"
            + " secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).as("without a concrete pin there is nothing to compare with (FR-011a);"
            + " a client without declared range shows its version only")
            .contains("test/node3: read-only, cli: available, inubit: V8_1, client: 8.1.17,"
                + " server version: determined at runtime,"
                + " secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).as("no client result (failed probe, reported as a warning)")
            .contains("test/node4: read-only, cli: available, inubit: AUTO,"
                + " secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).as("no StartCLI installation: no client")
            .contains("prod/node1: read-only, cli: unavailable, inubit: 8.1.17,"
                + " secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).contains("Result: OK");
    }

    @Test
    void aClientWithoutPatchIsNotComparableWithThePin() {
        String summary = render(PINS, Map.of(NodeId.parse("test/node1"),
            client("8.1", "4.0.1", "8.1.17")));

        assertThat(summary).contains("inubit: 8.1.17, client: 8.1 (4.0.1–8.1.17), client vs pin:"
            + " not comparable, secrets: credentialStore (macOS Keychain), username ←");
    }

    @Test
    void aPinOutsideTheClientRangeIsNamed() {
        // FR-011a: the pin is also checked against the declared range; at runtime FR-011
        // refuses StartCLI-backed tools on such a node
        String summary = render(PINS.replaceFirst("8\\.1\\.17", "9.0.7"), Map.of(
            NodeId.parse("test/node1"), client("8.1.17", "4.0.1", "8.1.17"),
            NodeId.parse("test/node2"), client("8.1.18", "4.0.1", "8.1.18")));

        assertThat(summary).contains("test/node1: read-only, cli: available, inubit: 9.0.7,"
            + " client: 8.1.17 (4.0.1–8.1.17), client vs pin: mismatch, pin outside client"
            + " range, secrets: credentialStore (macOS Keychain), username ←");
        assertThat(summary).as("a patch mismatch inside the range")
            .contains("client vs pin: mismatch,"
                + " secrets: credentialStore (macOS Keychain), username ←");
    }

    // --- feature 010 T008: the secret source per node (contracts/configuration-delta.md) -------

    /** test: environment variables under its own prefix; prod: AWS Parameter Store. */
    private static final String SOURCES = """
        profile:
          name: acme
        groups:
          - name: test
            credentials:
              source:
                type: credentialStore
                prefix: INUBIT_TEAM
            nodes:
              - name: inubit01
                baseUrl: https://inubit-test-01.example.test:8443
          - name: prod
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
              parameters:
                username: /inubit/prod/username
                password: /inubit/prod/password
            nodes:
              - name: inubit01
                baseUrl: https://inubit-prod-01.example.test:8443
        """;

    @Test
    void everyNodeLineShowsItsSourceAlsoWithoutASourceSetting() {
        // feature 011 (T017, FR-011): the default source is shown like an explicit one
        String summary = render(CONFIG);
        String explicit = render(CONFIG + "credentials:\n  source:\n    type: credentialStore\n");

        assertThat(summary).contains("test/inubit01: write enabled (confirmation SERVER), "
            + "cli: available, inubit: AUTO, secrets: credentialStore (macOS Keychain),"
            + " username ←");
        assertThat(explicit).isEqualTo(summary);
    }

    @Test
    void withSourceEveryNodeLineShowsItsSource() {
        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-secret");

        String summary = render(SOURCES);

        assertThat(summary).as("T015 carry-over: no node uses the profile prefix")
            .doesNotContain("Credential store entries:");
        assertThat(summary).contains("test/inubit01: read-only, cli: unavailable, inubit: AUTO,"
            + " secrets: credentialStore (macOS Keychain), username ← INUBIT_TEAM_TEST_USERNAME"
            + " ok, password ← INUBIT_TEAM_TEST_PASSWORD ok\n");
        assertThat(summary).contains("prod/inubit01: read-only, cli: unavailable, inubit: AUTO,"
            + " secrets: awsParameterStore (eu-central-1), username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/password ok\n");
        assertThat(summary).doesNotContain("(missing)", "jdoe", "team-secret", "ts-secret",
            "prod-user", "prod-secret");
    }

    @Test
    void theCredentialVariablesLineNeedsANodeWithEnvironmentVariables() {
        String allAws = """
            profile:
              name: acme
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
            groups:
              - name: test
                credentials:
                  parameters:
                    username: /inubit/test/username
                    password: /inubit/test/password
                nodes:
                  - name: inubit01
                    baseUrl: https://inubit-test-01.example.test:8443
            """;

        store.put(EU_CENTRAL, "/inubit/test/username", "test-user")
            .put(EU_CENTRAL, "/inubit/test/password", "test-Secret-1");

        assertThat(render(allAws)).doesNotContain("Credential store entries:", "(missing)")
            .contains("test/inubit01: read-only, cli: unavailable, inubit: AUTO,"
                + " secrets: awsParameterStore (eu-central-1),"
                + " username ← /inubit/test/username ok, password ← /inubit/test/password ok\n");
    }

    @Test
    void theCredentialVariablesLineNeedsANodeUnderTheProfilePrefix() {
        // T008 review r1 carry-over: shown as soon as one credential store node uses the prefix
        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-secret");

        String summary = render(SOURCES + """
              - name: qa
                nodes:
                  - name: inubit01
                    baseUrl: https://inubit-qa-01.example.test:8443
            """);

        assertThat(summary).contains("Credential store entries: service inubit-mcp, account"
            + " INUBIT_ACME_<GROUP>[_<NODE>]_<KIND> (macOS Keychain)\n", "qa/inubit01: read-only,"
            + " cli: unavailable, inubit: AUTO, secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_QA_USERNAME ok");
    }

    // --- feature 010 T015: AWS parameters read and checked (FR-009, research R-12) -------------

    private static final StoreLocation EU_CENTRAL =
        new StoreLocation("eu-central-1", Optional.empty());
    private static final StoreLocation PROD_ACCOUNT =
        new StoreLocation("eu-west-1", Optional.of("prod-account"));

    /** Two AWS nodes; inubit02 adds its own password, a trust store password and e2e names. */
    private static final String AWS = """
        profile:
          name: acme
        groups:
          - name: prod
            credentials:
              source:
                type: awsParameterStore
                region: eu-central-1
              parameters:
                username: /inubit/prod/username
                password: /inubit/prod/password
            nodes:
              - name: inubit01
                baseUrl: https://inubit-prod-01.example.test:8443
              - name: inubit02
                baseUrl: https://inubit-prod-02.example.test:8443
                credentials:
                  parameters:
                    password: /inubit/prod/inubit02/password
                    trustStorePassword: /inubit/prod/inubit02/ts-password
                    e2eUsername: /inubit/prod/inubit02/e2e-user
                    e2ePassword: /inubit/prod/inubit02/e2e-password
        """;

    private static final String LINE1 = "prod/inubit01: read-only, cli: unavailable, inubit: AUTO,"
        + " secrets: awsParameterStore (eu-central-1), ";
    private static final String LINE2 = "prod/inubit02: read-only, cli: unavailable, inubit: AUTO,"
        + " secrets: awsParameterStore (eu-central-1), ";

    /** Scripts every name of {@link #AWS} with a valid {@code SecureString}. */
    private void storeAll() {
        store.put(EU_CENTRAL, "/inubit/prod/username", "prod-user")
            .put(EU_CENTRAL, "/inubit/prod/password", "prod-Secret-1")
            .put(EU_CENTRAL, "/inubit/prod/inubit02/password", "node2-Secret-2")
            .put(EU_CENTRAL, "/inubit/prod/inubit02/ts-password", "ts-Secret-3")
            .put(EU_CENTRAL, "/inubit/prod/inubit02/e2e-user", "e2e-Login-8")
            .put(EU_CENTRAL, "/inubit/prod/inubit02/e2e-password", "e2e-Secret-4");
    }

    @Test
    void everyConfiguredParameterIsReadAndShownWithOkButNeverItsValue() {
        storeAll();

        String summary = render(AWS);

        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/password ok\n");
        assertThat(summary).contains(LINE2 + "username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/inubit02/password ok,"
            + " trustStorePassword ← /inubit/prod/inubit02/ts-password ok,"
            + " e2eUsername ← /inubit/prod/inubit02/e2e-user ok,"
            + " e2ePassword ← /inubit/prod/inubit02/e2e-password ok\n");
        assertThat(summary).contains("Result: OK").doesNotContain("prod-user", "prod-Secret-1",
            "node2-Secret-2", "ts-Secret-3", "e2e-Login-8", "e2e-Secret-4");
        assertThat(store.requests()).singleElement().satisfies(request -> {
            assertThat(request.location()).isEqualTo(EU_CENTRAL);
            assertThat(request.names()).as("each name once").containsExactlyInAnyOrder(
                "/inubit/prod/username", "/inubit/prod/password",
                "/inubit/prod/inubit02/password", "/inubit/prod/inubit02/ts-password",
                "/inubit/prod/inubit02/e2e-user", "/inubit/prod/inubit02/e2e-password");
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void theRegionAndTheAwsProfileAreShown() {
        store.put(PROD_ACCOUNT, "/inubit/prod/username", "prod-user")
            .put(PROD_ACCOUNT, "/inubit/prod/password", "prod-Secret-1");

        String summary = render(SOURCES.replace("region: eu-central-1",
            "region: eu-west-1\n        awsProfile: prod-account"));

        assertThat(summary).contains("prod/inubit01: read-only, cli: unavailable, inubit: AUTO,"
            + " secrets: awsParameterStore (eu-west-1, profile prod-account),"
            + " username ← /inubit/prod/username ok, password ← /inubit/prod/password ok\n");
    }

    @Test
    void aMissingOrDeniedParameterIsAnErrorNamingNodeNameAndKind() {
        storeAll();
        store.fail(EU_CENTRAL, "/inubit/prod/password", FailureKind.ACCESS_DENIED,
                "User: arn:aws:sts::123456789012:assumed-role/x is not authorized")
            .fail(EU_CENTRAL, "/inubit/prod/inubit02/e2e-password", FailureKind.NOT_FOUND,
                "listed in InvalidParameters");

        String summary = render(AWS);

        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/password access denied\n");
        assertThat(summary).contains("e2ePassword ← /inubit/prod/inubit02/e2e-password not"
            + " found\n");
        assertThat(summary).contains("Errors:",
            "  - prod/inubit01: Parameter Store /inubit/prod/password (eu-central-1) for"
                + " credentials.parameters.password: access denied\n",
            "  - prod/inubit02: Parameter Store /inubit/prod/inubit02/e2e-password (eu-central-1)"
                + " for credentials.parameters.e2ePassword: not found\n",
            "Result: FAILED (2 error(s))");
        assertThat(summary).as("the raw store message stays out").doesNotContain("123456789012",
            "InvalidParameters");
    }

    @Test
    void valuesAreCheckedAsAtRunTimeAndNeverPrinted() {
        storeAll();
        store.put(EU_CENTRAL, "/inubit/prod/username", "svc:Name-7")
            .put(EU_CENTRAL, "/inubit/prod/password", "x7q")
            .put(EU_CENTRAL, "/inubit/prod/inubit02/password", "list-Secret-5,b",
                ParameterType.STRING_LIST)
            .put(EU_CENTRAL, "/inubit/prod/inubit02/ts-password", "");

        String summary = render(AWS);

        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username invalid value,"
            + " password ← /inubit/prod/password invalid value\n");
        assertThat(summary).contains(LINE2 + "username ← /inubit/prod/username invalid value,"
            + " password ← /inubit/prod/inubit02/password invalid value,"
            + " trustStorePassword ← /inubit/prod/inubit02/ts-password not found,");
        assertThat(summary).contains(
            "prod/inubit01: Parameter Store /inubit/prod/username (eu-central-1) for"
                + " credentials.parameters.username: invalid value (the username "
                + CredentialRules.SAFE_USERNAME_RULE + ")",
            "prod/inubit01: Parameter Store /inubit/prod/password (eu-central-1) for"
                + " credentials.parameters.password: invalid value (the password "
                + CredentialRules.SHORT_PASSWORD_RULE + ")",
            "prod/inubit02: Parameter Store /inubit/prod/inubit02/password (eu-central-1) for"
                + " credentials.parameters.password: invalid value (a StringList is not"
                + " accepted; store it as SecureString)",
            "prod/inubit02: Parameter Store /inubit/prod/inubit02/ts-password (eu-central-1)"
                + " for credentials.parameters.trustStorePassword: not found (the value is"
                + " empty)",
            "Result: FAILED (5 error(s))");
        assertThat(summary).doesNotContain("svc:Name-7", "Name-7", "x7q", "list-Secret-5");
    }

    @Test
    void aStringParameterIsAWarningOnly() {
        storeAll();
        store.put(EU_CENTRAL, "/inubit/prod/password", "plain-Secret-6", ParameterType.STRING);

        String summary = render(AWS);

        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username ok,"
            + " password ← /inubit/prod/password not encrypted\n");
        assertThat(summary).contains("Warnings:", "  - prod/inubit01: Parameter Store"
            + " /inubit/prod/password (eu-central-1) for credentials.parameters.password is not"
            + " encrypted (type String); store it as SecureString\n", "Result: OK");
        assertThat(summary).doesNotContain("plain-Secret-6");
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"NO_CREDENTIALS", "UNREACHABLE"})
    void withoutAwsAccessTheNamesAreListedAndResolvabilityIsNotChecked(FailureKind kind) {
        store.failCall(EU_CENTRAL, kind, "arn:aws:sts::123456789012:assumed-role/expired");

        String summary = render(AWS);

        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username not checked,"
            + " password ← /inubit/prod/password not checked\n");
        assertThat(summary).contains("e2ePassword ← /inubit/prod/inubit02/e2e-password not"
            + " checked\n");
        assertThat(summary).contains("Warnings:", "  - AWS Parameter Store (eu-central-1) for"
            + " prod/inubit01, prod/inubit02: resolvability not checked: ", "Result: OK");
        assertThat(summary).doesNotContain("Errors:", "123456789012");
        assertThat(store.calls()).as("no further batch after a failed call").isEqualTo(1);
    }

    @Test
    void aStalledStoreCallIsBoundedByTheTimeoutAndReportedAsUnreachable() {
        // T015 review r1: a stalled credential provider must not stretch --check-config
        storeAll();
        store.delay(EU_CENTRAL, Duration.ofSeconds(30));
        storeTimeout = Duration.ofMillis(300);
        long start = System.nanoTime();

        String summary = render(AWS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(summary).contains(LINE1 + "username ← /inubit/prod/username not checked,"
            + " password ← /inubit/prod/password not checked\n");
        assertThat(summary).contains("Warnings:", "  - AWS Parameter Store (eu-central-1) for"
            + " prod/inubit01, prod/inubit02: resolvability not checked: the store could not be"
            + " reached or did not answer within ", "Result: OK");
        assertThat(summary).doesNotContain("Errors:");
    }

    @Test
    void anUnknownAwsProfileIsAnError() {
        store.failCall(PROD_ACCOUNT, FailureKind.UNKNOWN_PROFILE,
            "AWS profile 'prod-account' not found in the AWS config or credentials file");

        String summary = render(SOURCES.replace("region: eu-central-1",
            "region: eu-west-1\n        awsProfile: prod-account"));

        assertThat(summary).contains("secrets: awsParameterStore (eu-west-1, profile"
            + " prod-account), username ← /inubit/prod/username not checked, password ←"
            + " /inubit/prod/password not checked\n");
        assertThat(summary).contains("Errors:", "  - AWS Parameter Store (eu-west-1, profile"
            + " prod-account) for prod/inubit01: the AWS profile prod-account is not defined in"
            + " the local AWS configuration files", "Result: FAILED");
    }

    @Test
    void theNamesAreReadPerStoreLocationInBatchesOfAtMostTen() {
        // prod: one shared username and eleven node passwords = 12 names at eu-central-1;
        // audit: two names at eu-west-1 with the AWS profile prod-account
        StringBuilder yaml = new StringBuilder("""
            profile:
              name: acme
            groups:
              - name: prod
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-central-1
                  parameters:
                    username: /inubit/prod/username
                nodes:
            """);
        List<String> prodNames = new ArrayList<>(List.of("/inubit/prod/username"));
        for (int i = 1; i <= 11; i++) {
            String name = "/inubit/prod/node" + i + "/password";
            prodNames.add(name);
            yaml.append("      - name: node").append(i).append('\n')
                .append("        baseUrl: https://node").append(i).append(".example.test\n")
                .append("        credentials:\n          parameters:\n")
                .append("            password: ").append(name).append('\n');
        }
        yaml.append("""
              - name: audit
                credentials:
                  source:
                    type: awsParameterStore
                    region: eu-west-1
                    awsProfile: prod-account
                  parameters:
                    username: /inubit/audit/username
                    password: /inubit/audit/password
                nodes:
                  - name: node1
                    baseUrl: https://audit.example.test
            """);
        prodNames.forEach(name -> store.put(EU_CENTRAL, name, "value-Of-" + name.length()));

        render(yaml.toString());

        List<FakeParameterStore.Request> prod = store.requests().stream()
            .filter(request -> request.location().equals(EU_CENTRAL)).toList();
        assertThat(prod).extracting(request -> request.names().size()).containsExactly(10, 2);
        assertThat(prod.stream().flatMap(request -> request.names().stream()))
            .containsExactlyInAnyOrderElementsOf(prodNames);
        assertThat(store.requests()).filteredOn(request -> request.location().equals(PROD_ACCOUNT))
            .singleElement().satisfies(request -> assertThat(request.names())
                .containsExactlyInAnyOrder("/inubit/audit/username", "/inubit/audit/password"));
        assertThat(store.requests()).extracting(FakeParameterStore.Request::timeout)
            .containsOnly(Duration.ofSeconds(10));
    }

    @Test
    void aProfileWithoutAwsNodesReadsNothing() {
        render(SOURCES.replace("type: awsParameterStore\n        region: eu-central-1",
            "type: credentialStore"));

        assertThat(store.calls()).isZero();
    }

    // --- review T016-T017 r1: the outcomes of the entry check in the node line ---

    private static final String TWO_NODES = """
        profile:
          name: acme
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://inubit-dev-1.example.test:8443
                tls:
                  trustStore: /home/test/.config/inubit-mcp/dev.p12
              - name: node2
                baseUrl: https://inubit-dev-2.example.test:8443
        """;

    @Test
    void theNodeLineShowsNotFoundInvalidAndPresentOptionalKinds() {
        credentialStore = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", "store-user")
            .put("INUBIT_ACME_DEV_NODE1_PASSWORD", "abc")
            .put("INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD", "ts-Pw-1");

        String summary = render(TWO_NODES);

        assertThat(summary).contains("dev/node1: read-only, cli: unavailable, inubit: AUTO,"
            + " secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_DEV_USERNAME ok,"
            + " password ← INUBIT_ACME_DEV_NODE1_PASSWORD invalid value,"
            + " trustStorePassword ← INUBIT_ACME_DEV_NODE1_TRUSTSTORE_PASSWORD ok\n");
        assertThat(summary).contains("dev/node2: read-only, cli: unavailable, inubit: AUTO,"
            + " secrets: credentialStore (macOS Keychain),"
            + " username ← INUBIT_ACME_DEV_USERNAME ok,"
            + " password ← INUBIT_ACME_DEV_NODE2_PASSWORD | INUBIT_ACME_DEV_PASSWORD not found\n");
        assertThat(summary).doesNotContain("abc", "ts-Pw-1", "store-user");
    }

    @Test
    void theNodeLineShowsNotCheckedOnceTheStoreStopsAnswering() {
        credentialStore = new FakeCredentialStore()
            .put("INUBIT_ACME_DEV_USERNAME", "store-user")
            .put("INUBIT_ACME_DEV_PASSWORD", "store-Pw-1")
            .fail("INUBIT_ACME_DEV_NODE2_USERNAME", FailureKind.TIMEOUT, "no answer");

        String summary = render(TWO_NODES);

        assertThat(summary).contains(" username ← INUBIT_ACME_DEV_NODE2_USERNAME |"
            + " INUBIT_ACME_DEV_USERNAME not checked, password ← INUBIT_ACME_DEV_NODE2_PASSWORD |"
            + " INUBIT_ACME_DEV_PASSWORD not checked\n");
        // a warning, no error about the entries (the missing trust store file is another error)
        assertThat(summary).contains("macOS Keychain for dev/node2: resolvability not checked")
            .doesNotContain("credential store entry");
    }

    @Test
    void aSystemWithoutAStoreSaysSo() {
        credentialStore = new FakeCredentialStore(StoreKind.NONE)
            .fail("INUBIT_ACME_DEV_NODE1_USERNAME", FailureKind.STORE_NOT_AVAILABLE, "none");

        String summary = render(TWO_NODES);

        assertThat(summary).contains("secrets: credentialStore (none on this system)",
            "Credential store entries: service inubit-mcp, account INUBIT_ACME_<GROUP>[_<NODE>]"
                + "_<KIND> (none on this system)",
            "No supported credential store on this system for dev/node1, dev/node2");
    }
}
