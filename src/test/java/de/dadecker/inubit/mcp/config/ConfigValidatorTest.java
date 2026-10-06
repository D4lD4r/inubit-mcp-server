package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One test per row of "Startup validation outcomes" in contracts/configuration.md, plus the
 * additional checks of data-model.md.
 */
class ConfigValidatorTest {

    private static final Path HOME = Path.of("/home/test");
    private static final String PIN =
        "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89";
    private static final String CLI_HOME = "/opt/client";

    private final Map<String, String> env = new HashMap<>();
    /** Stage-wide credential variables that a test wants to be unset. */
    private final Set<String> unset = new HashSet<>();
    private final Set<Path> existing = new HashSet<>();
    /** Workspaces the validator asked to prepare; no directory is created by these tests. */
    private final List<Path> prepared = new ArrayList<>();
    private WorkspaceDirectory.Result workspaceResult = new WorkspaceDirectory.Usable(false);
    private boolean windows;
    private Path tempDirectory = Path.of("/var/tmp/inubit-test");

    @BeforeEach
    void setUp() {
        env.put("JAVA_HOME", "/opt/jdk");
        existing.add(Path.of(CLI_HOME, "bin", "startcli.sh"));
        existing.add(Path.of(CLI_HOME, "bin", "startcli.bat"));
        existing.add(Path.of("/opt/truststore.p12"));
        existing.add(Path.of("/opt/jdk17"));
    }

    private ValidationReport validate(String yaml) {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, windows)
            .parse(yaml, HOME.resolve("config.yaml"));
        // stage-wide credentials for every configured stage, unless a test unsets them
        for (GroupConfig stage : loaded.config().groups()) {
            String prefix = loaded.config().credentialPrefix() + "_"
                + stage.name().toUpperCase().replace('-', '_');
            for (String kind : new String[] {"_USERNAME", "_PASSWORD"}) {
                if (!unset.contains(prefix + kind)) {
                    env.putIfAbsent(prefix + kind, "secret-value" + kind);
                }
            }
        }
        CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
            loaded.config().credentialPrefix())
            .resolve(loaded.config().nodeIds());
        return new ConfigValidator(existing::contains, env, windows, tempDirectory,
            source -> List.of(), workspace -> {
                prepared.add(workspace);
                return workspaceResult;
            }).validate(loaded, credentials);
    }

    private static String server(String settings) {
        return """
            profile:
              name: acme
            groups:
              - name: dev
                inventory:
                  owner: INTEGRATION
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
            """ + settings.indent(8);
    }

    @Test
    void aTemporaryDirectoryStartCliCannotTakeIsAWarningWhenTheCliIsConfigured() {
        // review I9: exports are written below java.io.tmpdir, whose path goes into --execCommand
        tempDirectory = Path.of("/var/tmp/odd~dir");
        String cli = """
            defaults:
              cliHome: /opt/client
              cliJavaHome: /opt/jdk17
            """;

        ValidationReport withCli = validate(cli + server(""));
        ValidationReport withoutCli = validate(server(""));

        assertThat(withCli.errors()).isEmpty();
        assertThat(withCli.warnings()).singleElement().asString()
            .contains("java.io.tmpdir", "/var/tmp/odd~dir", "CLI_UNAVAILABLE");
        assertThat(withoutCli.warnings()).isEmpty();
    }

    @Test
    void theTemporaryDirectoryMustLeaveRoomForTheLongestProfileScopedExportPath() {
        // 002 research D-8: inubit-mcp-export-<profile>-<pid>-<random>/<file> below
        // java.io.tmpdir must still fit into one StartCLI path value (1024 characters)
        String cli = """
            defaults:
              cliHome: /opt/client
              cliJavaHome: /opt/jdk17
            """;
        tempDirectory = Path.of("/" + "t".repeat(949));

        assertThat(validate(cli + server("")).warnings()).singleElement().asString()
            .contains("java.io.tmpdir", "CLI_UNAVAILABLE");
    }

    @Test
    void validConfigurationHasNoFindings() {
        ValidationReport report = validate("""
            profile:
              name: acme
            defaults:
              cliHome: /opt/client
              cliJavaHome: /opt/jdk17
              inventory:
                owner: INTEGRATION
            groups:
              - name: dev
                tls:
                  trustStore: /opt/truststore.p12
                nodes:
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
              - name: prod
                production: true
                nodes:
                  - name: node1
                    baseUrl: https://node1.example.test:8443
                  - name: node2
                    baseUrl: https://node2.example.test:8443
            """);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.hasErrors()).isFalse();
    }

    @Nested
    class Errors {

        @Test
        void credentialKeysInTheYaml() {
            ValidationReport report = validate(server("""
                username: jdoe
                password: in-the-file
                """) + "credentials:\n  password: top-level-secret\n");

            assertThat(report.errors()).hasSize(3);
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("groups[0].nodes[0].password", "environment variables"));
            // 002: the top-level credentials section is allowed, a password inside it is not
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("credentials.password"));
            assertThat(String.join("\n", report.errors()))
                .doesNotContain("in-the-file", "jdoe", "top-level-secret");
        }

        @Test
        void credentialKeysNameTheVariablesUnderTheEffectivePrefix() {
            // 002 research D-6: the hint names the variables this profile reads
            ValidationReport defaultPrefix = validate(server("password: in-the-file\n"));
            ValidationReport explicitPrefix = validate(server("password: in-the-file\n")
                + "credentials:\n  envPrefix: INUBIT_TEAM\n");

            assertThat(defaultPrefix.errors()).singleElement().asString()
                .contains("INUBIT_ACME_<GROUP>[_<NODE>]_USERNAME");
            assertThat(explicitPrefix.errors()).anySatisfy(e -> assertThat(e)
                .contains("INUBIT_TEAM_<GROUP>[_<NODE>]_USERNAME"));
        }

        @Test
        void missingUsernameOrPasswordVariable() {
            unset.add("INUBIT_ACME_DEV_PASSWORD");

            ValidationReport report = validate(server(""));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "INUBIT_ACME_DEV_NODE1_PASSWORD",
                    "INUBIT_ACME_DEV_PASSWORD")
                .doesNotContain("secret-value"));
        }

        @Test
        void collidingDerivedVariableNames() {
            env.put("INUBIT_ACME_A_B_C_USERNAME", "u");
            env.put("INUBIT_ACME_A_B_C_PASSWORD", "colliding-pw");

            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: a-b
                    nodes:
                      - name: c
                        baseUrl: https://one.example.test
                  - name: a
                    nodes:
                      - name: b-c
                        baseUrl: https://two.example.test
                """);

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("a-b/c", "a/b-c", "INUBIT_ACME_A_B_C_USERNAME"));
        }

        @Test
        void duplicateStageName() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                  - name: dev
                    nodes:
                      - name: other
                        baseUrl: https://two.example.test
                """);

            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("Duplicate group name 'dev'"));
        }

        @Test
        void duplicateServerNameWithinAStage() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                      - name: node1
                        baseUrl: https://two.example.test
                """);

            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("Duplicate node name 'node1'", "dev"));
        }

        @Test
        void sameServerNameInDifferentStagesIsAllowed() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                  - name: qa
                    nodes:
                      - name: node1
                        baseUrl: https://two.example.test
                """);

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void invalidStageOrServerName() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: DEV
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                  - name: qa
                    nodes:
                      - name: "inubit_1"
                        baseUrl: https://two.example.test
                      - name: "%s"
                        baseUrl: https://three.example.test
                  - nodes:
                      - name: x
                        baseUrl: https://four.example.test
                """.formatted("a".repeat(33)));

            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("Invalid group name 'DEV'", "^[a-z0-9][a-z0-9-]{0,31}$"));
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("Invalid node name 'inubit_1'"));
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("Invalid node name '" + "a".repeat(33)));
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("groups[2]", "name is missing"));
        }

        @Test
        void clientConfirmationOnAProductionStage() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: prod
                    production: true
                    write:
                      confirmation: CLIENT
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                      - name: node2
                        baseUrl: https://two.example.test
                        write:
                          confirmation: SERVER
                """);

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("prod/node1", "CLIENT", "production"));
        }

        @Test
        void clientConfirmationOnAServerOfAProductionStage() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: prod
                    production: true
                    nodes:
                      - name: node1
                        baseUrl: https://one.example.test
                        write:
                          confirmation: CLIENT
                """);

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("prod/node1", "CLIENT"));
        }

        @Test
        void aConfirmationTtlLongerThanOneHourIsAnError() {
            // Phase 6 review W5: a confirmation must refer to a recent preview
            ValidationReport report = validate(server("confirmationTtl: PT61M\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "confirmationTtl", "PT1H"));
        }

        @Test
        void aConfirmationTtlOfOneHourIsAllowed() {
            assertThat(validate(server("confirmationTtl: PT1H\n")).errors()).isEmpty();
        }

        @Test
        void clientConfirmationOutsideProductionIsAllowed() {
            assertThat(validate(server("write:\n  confirmation: CLIENT\n")).errors()).isEmpty();
        }

        @Test
        void httpWithoutAllowInsecureHttp() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: http://inubit.example.test:8080
                """);

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "http://", "allowInsecureHttp"));
        }

        @Test
        void unsupportedSchemeOrMissingBaseUrl() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: ftp
                        baseUrl: ftp://inubit.example.test
                      - name: nourl
                """);

            assertThat(report.errors())
                .anySatisfy(e -> assertThat(e).contains("dev/ftp", "https://"));
            assertThat(report.errors()).anySatisfy(e -> assertThat(e)
                .contains("dev/nourl", "baseUrl is missing"));
        }

        @Test
        void disabledHostnameVerificationWithoutPin() {
            ValidationReport report =
                validate(server("tls:\n  disableHostnameVerification: true\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "disableHostnameVerification", "pinnedCertificateSha256"));
        }

        @Test
        void malformedPin() {
            ValidationReport report =
                validate(server("tls:\n  pinnedCertificateSha256: 'AB:CD'\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "pinnedCertificateSha256"));
        }

        @Test
        void pinWithoutColonsIsAccepted() {
            ValidationReport report = validate(server(
                "tls:\n  pinnedCertificateSha256: '" + PIN.replace(":", "").toLowerCase() + "'\n"));

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void trustStorePasswordForAServerWithCli() {
            env.put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-secret");

            ValidationReport report = validate(server("""
                tls:
                  trustStore: /opt/truststore.p12
                cli:
                  home: /opt/client
                  javaHome: /opt/jdk17
                """));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "CLI")
                .doesNotContain("ts-secret"));
        }

        @Test
        void trustStorePasswordWithoutCliIsAllowed() {
            env.put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-secret");

            ValidationReport report = validate(server("tls:\n  trustStore: /opt/truststore.p12\n"));

            assertThat(report.errors()).isEmpty();
        }

        @Test
        void trustStoreFileNotFound() {
            ValidationReport report = validate(server("tls:\n  trustStore: /missing.p12\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "/missing.p12"));
        }

        @Test
        void noStagesOrStageWithoutServers() {
            assertThat(validate("profile:\n  name: acme\nlogLevel: INFO\n").errors())
                .anySatisfy(e -> assertThat(e).contains("at least one group"));
            assertThat(validate("profile:\n  name: acme\ngroups:\n  - name: dev\n").errors())
                .anySatisfy(e -> assertThat(e).contains("dev", "at least one node"));
        }

        @Test
        void nonPositiveLimitsAndDurations() {
            ValidationReport report = validate(server("timeout: PT0S\n")
                + "resultLimits:\n  maxItems: 0\n  maxChars: -1\n");

            assertThat(report.errors()).anySatisfy(e -> assertThat(e).contains("maxItems"));
            assertThat(report.errors()).anySatisfy(e -> assertThat(e).contains("maxChars"));
            assertThat(report.errors())
                .anySatisfy(e -> assertThat(e).contains("dev/node1", "timeout"));
        }

        @Test
        void inventoryOwnerMustBeSafeForTheCli() {
            ValidationReport report = validate(server("inventory:\n  owner: \"OWNERS'; rm\"\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "inventory.owner"));
        }

        @Test
        void inventoryOwnerMustNotStartWithADash() {
            ValidationReport report = validate(server("inventory:\n  owner: \"-OWNERS\"\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "inventory.owner"));
        }

        @Test
        void cliUrlWithHttpWithoutAllowInsecureHttp() {
            ValidationReport report = validate(server("cli:\n  url: http://cli.example.test/x\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "cli.url", "allowInsecureHttp"));
        }

        @Test
        void cliUrlWithUnsupportedScheme() {
            ValidationReport report = validate(server("cli:\n  url: ftp://cli.example.test/x\n"));

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "cli.url", "https://"));
        }

        @Test
        void userInfoInUrlsIsAnErrorWithoutTheValue() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://jdoe:hunter2@inubit.example.test
                        cli:
                          url: https://jdoe:hunter2@cli.example.test/x
                """);

            assertThat(report.errors())
                .anySatisfy(e -> assertThat(e).contains("dev/node1", "baseUrl", "user info"))
                .anySatisfy(e -> assertThat(e).contains("dev/node1", "cli.url", "user info"))
                .allSatisfy(e -> assertThat(e).doesNotContain("hunter2", "jdoe"));
        }

        @Test
        void queryInTheBaseUrlIsAnError() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://inubit.example.test/?a=b
                """);

            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "query or fragment"));
        }

        @Test
        void maxCharsBelowTenThousand() {
            assertThat(validate(server("") + "resultLimits:\n  maxChars: 9999\n").errors())
                .singleElement().satisfies(e -> assertThat(e).contains("maxChars", "10000"));
            assertThat(validate(server("") + "resultLimits:\n  maxChars: 10000\n").errors())
                .isEmpty();
        }

        @Test
        void allErrorsAreCollectedAndReportedTogether() {
            unset.add("INUBIT_ACME_DEV_USERNAME");

            ValidationReport report = validate("""
                profile:
                  name: acme
                password: nope
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: http://inubit.example.test
                        tls:
                          disableHostnameVerification: true
                  - name: prod
                    production: true
                    nodes:
                      - name: node1
                        baseUrl: https://prod.example.test
                        write:
                          confirmation: CLIENT
                """);

            assertThat(report.errors()).hasSize(5);
            assertThat(report.hasErrors()).isTrue();
        }
    }

    @Nested
    class Warnings {

        private static final String CLI_WITHOUT_OWNER = """
            profile:
              name: acme
            terminology:
              group: { singular: Umgebung, plural: Umgebungen }
              node: { singular: Knoten, plural: Knoten }
            defaults:
              cliHome: /opt/client
              cliJavaHome: /opt/jdk17
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://node1.example.test:8443
                  - name: node2
                    baseUrl: https://node2.example.test:8443
            """;

        @Test
        void noInventoryOwnerAnywhereWhileACliIsConfiguredIsOneWarning() {
            ValidationReport report = validate(CLI_WITHOUT_OWNER);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).as("002-T014, research D-10").singleElement()
                .asString().contains("inventory.owner", "NOT_CONFIGURED", "list_inventory",
                    "get_inventory_item", "Umgebung", "Knoten")
                .doesNotContainPattern("(?i)\\b(stages?|servers?|groups?|nodes?)\\b");
        }

        @Test
        void nodesWithoutOwnerAreListedWhenOtherNodesHaveOne() {
            String yaml = CLI_WITHOUT_OWNER.replace("""
                      - name: node2
                """, """
                      - name: node2
                        inventory:
                          owner: INTEGRATION
                """);

            assertThat(validate(yaml).warnings()).as("002 review U6").singleElement()
                .asString().contains("Knoten without inventory.owner", "dev/node1",
                    "NOT_CONFIGURED")
                .doesNotContain("dev/node2");
        }

        @Test
        void nodesWithoutOwnerAreNotListedWithoutCli() {
            assertThat(validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://node1.example.test:8443
                      - name: node2
                        baseUrl: https://node2.example.test:8443
                        inventory:
                          owner: INTEGRATION
                """).warnings()).isEmpty();
        }

        @Test
        void noInventoryOwnerWithoutCliIsNoWarning() {
            assertThat(validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://inubit.example.test:8443
                """).warnings()).isEmpty();
        }

        @Test
        void httpWithAllowInsecureHttp() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: http://inubit.example.test:8080
                        allowInsecureHttp: true
                """);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "http://"));
        }

        @Test
        void disabledHostnameVerificationWithPin() {
            ValidationReport report = validate(server("""
                tls:
                  disableHostnameVerification: true
                  pinnedCertificateSha256: '%s'
                """.formatted(PIN)));

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "hostname verification"));
        }

        @Test
        void startCliScriptNotFound() {
            ValidationReport report =
                validate(server("cli:\n  home: /opt/other\n  javaHome: /opt/jdk17\n"));

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", Path.of("/opt/other/bin/startcli.sh").toString(),
                    "CLI_UNAVAILABLE"));
        }

        @Test
        void cliToolsAreNotSupportedOnWindows() {
            windows = true;

            ValidationReport report =
                validate(server("cli:\n  home: /opt/client\n  javaHome: /opt/jdk17\n"));

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "not supported on Windows", "CLI_UNAVAILABLE"));
        }

        @Test
        void withoutCliHomeWindowsGivesNoCliWarning() {
            windows = true;

            assertThat(validate(server("timeout: PT5S\n")).warnings()).isEmpty();
        }

        @Test
        void noCliJavaHomeAndNoJavaHome() {
            env.remove("JAVA_HOME");

            ValidationReport report = validate(server("cli:\n  home: /opt/client\n"));

            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "JAVA_HOME", "cliJavaHome"));
        }

        @Test
        void javaHomeOfTheServerIsAnAcceptedFallback() {
            assertThat(validate(server("cli:\n  home: /opt/client\n")).warnings()).isEmpty();
        }

        @Test
        void configuredCliJavaHomeNotFound() {
            ValidationReport report =
                validate(server("cli:\n  home: /opt/client\n  javaHome: /nope\n"));

            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "/nope"));
        }

        @Test
        void versionLine9IsUnsupported() {
            ValidationReport report = validate(server("versionLine: V9_X\n"));

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "V9_X", "unsupported in this version"));
        }

        @Test
        void cliUrlWithHttpAndAllowInsecureHttp() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://inubit.example.test
                        allowInsecureHttp: true
                        cli:
                          url: http://cli.example.test/x
                """);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "cli.url", "http://"));
        }

        @Test
        void stageLevelCliUrlSharedByServers() {
            ValidationReport report = validate("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    cli:
                      url: https://cli.example.test/x
                    nodes:
                      - name: one
                        baseUrl: https://one.example.test
                      - name: two
                        baseUrl: https://two.example.test
                """);

            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("Group 'dev'", "cli.url", "2 nodes"));
        }

        @Test
        void trustStorePasswordWithoutTrustStore() {
            env.put("INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "ts-secret");

            ValidationReport report = validate(server(""));

            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("dev/node1", "INUBIT_ACME_DEV_TRUSTSTORE_PASSWORD", "tls.trustStore")
                .doesNotContain("ts-secret"));
        }

        @Test
        void veryShortPassword() {
            env.put("INUBIT_ACME_DEV_PASSWORD", "abc");

            ValidationReport report = validate(server(""));

            // Phase 3 review m4: an error (was a warning)
            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/node1", "INUBIT_ACME_DEV_PASSWORD", "4 characters")
                .doesNotContain("abc"));
            assertThat(report.warnings()).isEmpty();
        }

        @Test
        void unmatchedWarningConsidersServersWithoutBaseUrl() {
            env.put("INUBIT_ACME_DEV_NOURL_PASSWORD", "server-pw");

            ValidationReport report = validate(server("") + """
                  - name: nourl
                """.indent(4));

            assertThat(report.warnings()).isEmpty();
            assertThat(report.errors()).singleElement().satisfies(e -> assertThat(e)
                .contains("dev/nourl", "baseUrl is missing"));
        }

        @Test
        void passwordVariableMatchingNoServer() {
            env.put("INUBIT_ACME_DEV_INUBTI_PASSWORD", "typo-value");

            ValidationReport report = validate(server(""));

            assertThat(report.warnings()).singleElement().satisfies(w -> assertThat(w)
                .contains("INUBIT_ACME_DEV_INUBTI_PASSWORD").doesNotContain("typo-value"));
        }
    }

    /**
     * 002 T031 (contracts/configuration.md → Startup validation; spec edge cases): best-effort
     * warnings for another profile file in the default configuration directory that would share
     * the audit directory, the credential variables or the profile name with this one.
     */
    @Nested
    class OtherProfileFiles {

        private static final String GROUPS = """
            groups:
              - name: test
                nodes:
                  - name: node1
                    baseUrl: https://node1.test.example.test:8443
            """;

        @TempDir
        Path home;

        private Path configDirectory() {
            return home.resolve(".config/inubit-mcp");
        }

        private Path write(Path file, String yaml) throws IOException {
            Files.createDirectories(file.getParent());
            return Files.writeString(file, yaml);
        }

        private Path profileFile(String fileName, String name, String settings)
            throws IOException {
            return write(configDirectory().resolve(fileName),
                "profile:\n  name: " + name + "\n" + settings + GROUPS);
        }

        private ValidationReport validateFile(Path file) {
            ConfigLoader loader = new ConfigLoader(Map.of(), home, false);
            LoadedConfig loaded = loader.loadFile(file);
            String prefix = loaded.config().credentialPrefix();
            for (NodeId node : loaded.config().nodeIds()) {
                CredentialVariables variables = new CredentialVariables(prefix, node);
                env.put(variables.groupVariable("USERNAME"), "user");
                env.put(variables.groupVariable("PASSWORD"), "secret-value");
            }
            CredentialResolution credentials = new CredentialResolver(env, new SecretScrubber(),
                prefix).resolve(loaded.config().nodeIds());
            return new ConfigValidator(existing::contains, env, false, tempDirectory,
                loader::otherProfiles).validate(loaded, credentials);
        }

        @Test
        void profilesWithTheirOwnDefaultsGiveNoWarning() throws IOException {
            Path acme = profileFile("acme.yaml", "acme", "");
            profileFile("globex.yaml", "globex", "");

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).isEmpty();
        }

        @Test
        void theSameAuditDirectoryIsAWarningNamingTheOtherFile() throws IOException {
            Path acme = profileFile("acme.yaml", "acme", "auditDirectory: ~/shared-audit\n");
            Path globex = profileFile("globex.yaml", "globex",
                "auditDirectory: ~/shared-audit\n");

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().asString()
                .contains(globex.toString(), "'globex'", "audit directory",
                    home.resolve("shared-audit").toString())
                .doesNotContain("profile name", "prefix");
        }

        @Test
        void theSameEffectiveCredentialPrefixIsAWarning() throws IOException {
            // acme's default prefix INUBIT_ACME, configured explicitly by the other profile
            Path acme = profileFile("acme.yaml", "acme", "");
            Path globex = profileFile("globex.yaml", "globex",
                "credentials:\n  envPrefix: INUBIT_ACME\n");

            ValidationReport report = validateFile(acme);

            assertThat(report.warnings()).singleElement().asString()
                .contains(globex.toString(), "'globex'", "credential variable prefix INUBIT_ACME")
                .doesNotContain("profile name", "audit directory");
            // both configure test/node1, so they also derive the same names (review P1)
            assertThat(report.errors()).singleElement().asString()
                .contains(globex.toString(), "INUBIT_ACME_TEST_USERNAME");
        }

        @Test
        void theSameProfileNameIsAWarningThatNamesEverythingTheCopiesShare() throws IOException {
            Path acme = profileFile("acme.yaml", "acme", "");
            Path copy = profileFile("acme-copy.yaml", "acme", "");

            ValidationReport report = validateFile(acme);

            assertThat(report.warnings()).singleElement().asString()
                .contains(copy.toString(), "profile name 'acme'", "unique",
                    "credential variable prefix INUBIT_ACME", "audit directory");
            // re-review N1: a copy of the same profile may start (spec edge case)
            assertThat(report.errors()).isEmpty();
        }

        @Test
        void aCopyWithAnotherPrefixNamesTheSharedVariablesInItsWarning() throws IOException {
            // INUBIT_ACME + group 2-test meets INUBIT_ACME_2 + group test, same profile name
            Path acme = profileWithGroup("acme.yaml", "acme", "2-test");
            write(configDirectory().resolve("acme-copy.yaml"), """
                profile:
                  name: acme
                credentials:
                  envPrefix: INUBIT_ACME_2
                groups:
                  - name: test
                    nodes:
                      - name: node1
                        baseUrl: https://node1.example.test:8443
                """);

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().asString()
                .contains("profile name 'acme'", "INUBIT_ACME_2_TEST_USERNAME");
        }

        @Test
        void twoDifferentProfilesWithTheSameExplicitPrefixAndGroupAreAnError()
            throws IOException {
            // re-review N1 (b): different customers sharing INUBIT_DEV_*
            Path acme = profileFile("acme.yaml", "acme",
                "credentials:\n  envPrefix: INUBIT\n");
            Path globex = profileFile("globex.yaml", "globex",
                "credentials:\n  envPrefix: INUBIT\n");

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).singleElement().asString()
                .contains(globex.toString(), "'globex'", "INUBIT_TEST_USERNAME");
            assertThat(report.warnings()).singleElement().asString()
                .contains("credential variable prefix INUBIT");
        }

        @Test
        void theSamePrefixWithoutACommonVariableNameIsOnlyAWarning() throws IOException {
            // re-review N1 (d)
            Path acme = write(configDirectory().resolve("acme.yaml"), """
                profile:
                  name: acme
                credentials:
                  envPrefix: INUBIT_TEAM
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://node1.example.test:8443
                """);
            profileFile("globex.yaml", "globex", "credentials:\n  envPrefix: INUBIT_TEAM\n");

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).singleElement().asString()
                .contains("credential variable prefix INUBIT_TEAM")
                .doesNotContain("INUBIT_TEAM_DEV", "INUBIT_TEAM_TEST");
        }

        @Test
        void aFileGivenOutsideTheDefaultDirectoryIsComparedWithTheProfilesInIt()
            throws IOException {
            Path elsewhere = write(home.resolve("elsewhere/acme.yaml"),
                "profile:\n  name: acme\n" + GROUPS);
            Path inDirectory = profileFile("acme.yaml", "acme", "");

            ValidationReport report = validateFile(elsewhere);

            assertThat(report.warnings()).singleElement().asString()
                .contains(inDirectory.toString(), "profile name 'acme'");
        }

        @Test
        void unreadableOrInvalidOtherFilesAndOtherFileTypesAreIgnored() throws IOException {
            Path acme = profileFile("acme.yaml", "acme", "auditDirectory: ~/a\n");
            write(configDirectory().resolve("broken.yaml"), "groups: [unclosed\n");
            write(configDirectory().resolve("notes.txt"), "profile:\n  name: acme\n" + GROUPS);
            Files.createDirectories(configDirectory().resolve("dir.yaml"));

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).isEmpty();
        }

        // --- 002 US2 review P1: nested prefixes derive identical variable names -------------

        private Path profileWithGroup(String fileName, String name, String group)
            throws IOException {
            return write(configDirectory().resolve(fileName), """
                profile:
                  name: %s
                groups:
                  - name: %s
                    nodes:
                      - name: node1
                        baseUrl: https://node1.example.test:8443
                """.formatted(name, group));
        }

        @Test
        void anotherProfileDerivingTheSameVariableNameIsAnErrorNamingOnlyTheNames()
            throws IOException {
            // INUBIT_ACME + group 2-test == INUBIT_ACME_2 + group test
            Path acme = profileWithGroup("acme.yaml", "acme", "2-test");
            Path acme2 = profileWithGroup("acme-2.yaml", "acme-2", "test");

            ValidationReport fromAcme = validateFile(acme);
            ValidationReport fromAcme2 = validateFile(acme2);

            assertThat(fromAcme.errors()).singleElement().asString()
                .contains(acme2.toString(), "'acme-2'", "INUBIT_ACME_2_TEST_USERNAME",
                    "INUBIT_ACME_2_TEST_PASSWORD", "INUBIT_ACME_2_TEST_NODE1_PASSWORD")
                .doesNotContain("secret-value");
            assertThat(fromAcme2.errors()).singleElement().asString()
                .contains(acme.toString(), "'acme'", "INUBIT_ACME_2_TEST_USERNAME");
        }

        @Test
        void nestedPrefixesWithoutACommonVariableNameAreNoError() throws IOException {
            Path acme = profileWithGroup("acme.yaml", "acme", "dev");
            profileWithGroup("acme-2.yaml", "acme-2", "test");

            ValidationReport report = validateFile(acme);

            assertThat(report.errors()).isEmpty();
            assertThat(report.warnings()).isEmpty();
        }

        @Test
        void theExplicitPrefixInubitAgainstADefaultPrefixIsAnErrorWhenNamesMeet()
            throws IOException {
            // INUBIT + group acme-test == INUBIT_ACME + group test
            Path legacy = write(configDirectory().resolve("legacy.yaml"), """
                profile:
                  name: legacy
                credentials:
                  envPrefix: INUBIT
                groups:
                  - name: acme-test
                    nodes:
                      - name: node1
                        baseUrl: https://node1.example.test:8443
                """);
            profileWithGroup("acme.yaml", "acme", "test");

            assertThat(validateFile(legacy).errors()).singleElement().asString()
                .contains("INUBIT_ACME_TEST_USERNAME");
        }

        // --- 002 US2 review P3a: names from other files are printed only if valid ------------

        @Test
        void anInvalidProfileNameOfAnotherFileIsNeverPrinted() throws IOException {
            Path acme = profileFile("acme.yaml", "acme", "");
            profileFile("odd.yaml", "\"x\\e[31mred\\u0007\"",
                "credentials:\n  envPrefix: INUBIT_ACME\n");

            ValidationReport report = validateFile(acme);

            assertThat(String.join("\n", report.warnings()) + String.join("\n", report.errors()))
                .contains("(invalid profile.name)", "credential variable prefix INUBIT_ACME")
                .doesNotContain("\u001b", "\u0007", "[31m");
        }
    }

    /** 002 US2 review P3b: "audit" would put the profile's audit data into the 001 directory. */
    @Test
    void theProfileNameAuditIsReserved() {
        ValidationReport report = validate(server("").replace("name: acme", "name: audit"));

        assertThat(report.errors()).anySatisfy(error -> assertThat(error)
            .contains("profile.name", "'audit'", "reserved"));
    }

    /** T011 (feature 003, FR-001, FR-002): the workspace setting. */
    @Nested
    class Workspace {

        @Test
        void theDefaultWorkspaceIsPrepared() {
            ValidationReport report = validate(server(""));

            assertThat(report.errors()).isEmpty();
            assertThat(prepared).containsExactly(HOME.resolve(".inubit-mcp/acme/workspace"));
        }

        @Test
        void aRelativeWorkspaceIsAnErrorNamingThePath() {
            ValidationReport report = validate("workspace: relative/ws\n" + server(""));

            assertThat(report.errors()).singleElement().asString()
                .contains("workspace", "relative/ws", "must be absolute after expansion");
            assertThat(prepared).isEmpty();
        }

        @Test
        void anUnusableWorkspaceIsAnError() {
            workspaceResult = new WorkspaceDirectory.Unusable(
                "The workspace /srv/ws must be readable and writable");

            ValidationReport report = validate("workspace: /srv/ws\n" + server(""));

            assertThat(report.errors()).containsExactly(
                "The workspace /srv/ws must be readable and writable");
        }

        @Test
        void nothingIsPreparedForAnInvalidProfileName() {
            ValidationReport report = validate(server("").replace("name: acme", "name: Acme!"));

            assertThat(report.errors()).isNotEmpty();
            assertThat(prepared).isEmpty();
        }
    }

    /** T011: preparing a workspace on the real file system. */
    @Nested
    class WorkspaceOnDisk {

        @TempDir
        Path home;

        @Test
        void aMissingWorkspaceIsCreatedOwnerOnlyWithItsMissingParents() throws IOException {
            Path workspace = home.resolve("a/b/workspace");

            WorkspaceDirectory.Result result = WorkspaceDirectory.prepare(workspace);

            assertThat(result).isEqualTo(new WorkspaceDirectory.Usable(true));
            for (Path created : List.of(home.resolve("a"), home.resolve("a/b"), workspace)) {
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(created)))
                    .as(created.toString()).isEqualTo("rwx------");
            }
            assertThat(WorkspaceDirectory.prepare(workspace))
                .isEqualTo(new WorkspaceDirectory.Usable(false));
        }

        @Test
        void anExistingWorkspaceKeepsItsPermissions() throws IOException {
            Path workspace = Files.createDirectory(home.resolve("ws"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));

            assertThat(WorkspaceDirectory.prepare(workspace))
                .isEqualTo(new WorkspaceDirectory.Usable(false));
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace)))
                .isEqualTo("rwxr-x---");
        }

        @Test
        void aWorkspaceThatIsNotWritableIsUnusable() throws IOException {
            Path workspace = Files.createDirectory(home.resolve("ro"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));

            assertThat(WorkspaceDirectory.prepare(workspace))
                .isInstanceOfSatisfying(WorkspaceDirectory.Unusable.class, u -> assertThat(
                    u.problem()).contains(workspace.toString(), "must be readable and writable"));
        }

        @Test
        void aFileOrAnUncreatableWorkspaceIsUnusable() throws IOException {
            Path file = Files.writeString(home.resolve("file"), "x");

            assertThat(WorkspaceDirectory.prepare(file))
                .isInstanceOfSatisfying(WorkspaceDirectory.Unusable.class,
                    u -> assertThat(u.problem()).contains(file.toString(), "not a directory"));
            assertThat(WorkspaceDirectory.prepare(file.resolve("below")))
                .isInstanceOfSatisfying(WorkspaceDirectory.Unusable.class, u -> assertThat(
                    u.problem()).contains(file.resolve("below").toString(), "cannot be created"));
        }

        @Test
        void theValidatorCreatesTheWorkspaceAtStartup() {
            LoadedConfig loaded = new ConfigLoader(Map.of(), home, false).parse(server(""),
                home.resolve("config.yaml"));
            CredentialResolution credentials = new CredentialResolver(Map.of(
                "INUBIT_ACME_DEV_USERNAME", "u", "INUBIT_ACME_DEV_PASSWORD", "secret-value"),
                new SecretScrubber(), "INUBIT_ACME").resolve(loaded.config().nodeIds());

            ValidationReport report = new ConfigValidator(existing::contains, env, false,
                tempDirectory, source -> List.of()).validate(loaded, credentials);

            assertThat(report.errors()).isEmpty();
            assertThat(home.resolve(".inubit-mcp/acme/workspace")).isDirectory();
        }
    }
}
