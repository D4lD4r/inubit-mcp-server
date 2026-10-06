package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {

    private static final String MINIMAL = """
        profile:
          name: acme
        groups:
          - name: dev
            nodes:
              - name: node1
                baseUrl: https://inubit.example.test:8443
        """;

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();

    private ConfigLoader loader() {
        return new ConfigLoader(env, home, false);
    }

    private static Path resource(String name) {
        try {
            return Path.of(ConfigLoaderTest.class.getResource("/config/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private LoadedConfig load(String resourceName) {
        return loader().loadFile(resource(resourceName));
    }

    private static EffectiveNodeConfig server(LoadedConfig loaded, String id) {
        return loaded.config().effectiveNodes().stream()
            .filter(s -> s.id().equals(NodeId.parse(id)))
            .findFirst()
            .orElseThrow();
    }

    private Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Nested
    class Location {

        private Path defaultLocation;

        @BeforeEach
        void setUp() {
            defaultLocation = home.resolve(".config/inubit-mcp/config.yaml");
        }

        @Test
        void commandLineArgumentComesFirst() throws IOException {
            Path cli = write(home.resolve("cli.yaml"), MINIMAL);
            Path fromEnv = write(home.resolve("env.yaml"), MINIMAL);
            write(defaultLocation, MINIMAL);
            env.put("INUBIT_MCP_CONFIG", fromEnv.toString());

            assertThat(loader().locate(cli.toString())).isEqualTo(cli);
        }

        @Test
        void environmentVariableComesSecond() throws IOException {
            Path fromEnv = write(home.resolve("env.yaml"), MINIMAL);
            write(defaultLocation, MINIMAL);
            env.put("INUBIT_MCP_CONFIG", fromEnv.toString());

            assertThat(loader().locate()).isEqualTo(fromEnv);
        }

        @Test
        void userConfigDirectoryComesLast() throws IOException {
            write(defaultLocation, MINIMAL);

            assertThat(loader().locate()).isEqualTo(defaultLocation);
        }

        @Test
        void blankEnvironmentVariableIsIgnored() throws IOException {
            write(defaultLocation, MINIMAL);
            env.put("INUBIT_MCP_CONFIG", "  ");

            assertThat(loader().locate()).isEqualTo(defaultLocation);
        }

        @Test
        void windowsUsesAppData() throws IOException {
            Path appData = home.resolve("AppData/Roaming");
            Path windowsLocation = write(appData.resolve("inubit-mcp/config.yaml"), MINIMAL);
            env.put("APPDATA", appData.toString());

            assertThat(new ConfigLoader(env, home, true).locate())
                .isEqualTo(windowsLocation);
        }

        @Test
        void tildeIsExpandedInTheGivenLocations() throws IOException {
            Path cli = write(home.resolve("conf/cli.yaml"), MINIMAL);
            Path fromEnv = write(home.resolve("conf/env.yaml"), MINIMAL);

            assertThat(loader().locate("~/conf/cli.yaml")).isEqualTo(cli);
            env.put("INUBIT_MCP_CONFIG", "~/conf/env.yaml");
            assertThat(loader().locate()).isEqualTo(fromEnv);
        }

        @Test
        void missingFileListsTheSearchedLocations() {
            env.put("INUBIT_MCP_CONFIG", home.resolve("nowhere.yaml").toString());

            assertThatThrownBy(() -> loader().locate())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("No configuration file found")
                .hasMessageContaining("INUBIT_MCP_CONFIG")
                .hasMessageContaining(home.resolve("nowhere.yaml").toString());
        }

        @Test
        void nothingConfiguredListsAllThreeSources() {
            assertThatThrownBy(() -> loader().locate())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("--config")
                .hasMessageContaining("INUBIT_MCP_CONFIG")
                .hasMessageContaining(defaultLocation.toString());
        }

        @Test
        void anExplicitButMissingFileDoesNotFallBack() throws IOException {
            write(defaultLocation, MINIMAL);
            Path missing = home.resolve("missing.yaml");

            assertThatThrownBy(() -> loader().locate(missing.toString()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(missing.toString());
        }

        // --- 002 research D-9: profile selection ----------------------------------------------

        private Path profileFile(String name) {
            return home.resolve(".config/inubit-mcp/" + name + ".yaml");
        }

        private String profileYaml(String name) {
            return MINIMAL.replace("name: acme", "name: " + name);
        }

        @Test
        void theProfileArgumentSelectsItsFileInTheDefaultDirectory() throws IOException {
            Path acme = write(profileFile("acme"), profileYaml("acme"));
            write(profileFile("globex"), profileYaml("globex"));
            write(defaultLocation, MINIMAL);

            assertThat(loader().locate(null, "acme")).isEqualTo(acme);
        }

        @Test
        void onWindowsTheProfileFileIsInAppData() throws IOException {
            Path appData = home.resolve("AppData/Roaming");
            Path acme = write(appData.resolve("inubit-mcp/acme.yaml"), profileYaml("acme"));
            env.put("APPDATA", appData.toString());

            assertThat(new ConfigLoader(env, home, true).locate(null, "acme")).isEqualTo(acme);
        }

        @Test
        void theProfileEnvironmentVariableSelectsTheProfileFileLikeTheArgument()
            throws IOException {
            Path globex = write(profileFile("globex"), profileYaml("globex"));
            write(defaultLocation, MINIMAL);
            env.put("INUBIT_MCP_PROFILE", "globex");

            assertThat(loader().locate()).isEqualTo(globex);
        }

        @Test
        void theOrderIsConfigProfileConfigVariableProfileVariableDefault() throws IOException {
            Path cli = write(home.resolve("cli.yaml"), MINIMAL);
            Path acme = write(profileFile("acme"), profileYaml("acme"));
            Path fromEnv = write(home.resolve("env.yaml"), MINIMAL);
            Path globex = write(profileFile("globex"), profileYaml("globex"));
            write(defaultLocation, MINIMAL);
            env.put("INUBIT_MCP_CONFIG", fromEnv.toString());
            env.put("INUBIT_MCP_PROFILE", "globex");

            assertThat(loader().locate(cli.toString(), null)).isEqualTo(cli);
            assertThat(loader().locate(null, "acme")).isEqualTo(acme);
            assertThat(loader().locate(null, null)).isEqualTo(fromEnv);
            env.remove("INUBIT_MCP_CONFIG");
            assertThat(loader().locate(null, null)).isEqualTo(globex);
            env.remove("INUBIT_MCP_PROFILE");
            assertThat(loader().locate(null, null)).isEqualTo(defaultLocation);
        }

        @Test
        void aMissingProfileFileIsAnErrorWithoutFallback() throws IOException {
            write(defaultLocation, MINIMAL);

            assertThatThrownBy(() -> loader().locate(null, "acme"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("--profile acme")
                .hasMessageContaining(profileFile("acme").toString());
            env.put("INUBIT_MCP_PROFILE", "acme");
            assertThatThrownBy(() -> loader().locate())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("INUBIT_MCP_PROFILE=acme")
                .hasMessageContaining(profileFile("acme").toString());
        }

        @Test
        void anInvalidProfileNameInTheEnvironmentIsAnErrorAndNeverAPath() throws IOException {
            write(home.resolve("x.yaml"), MINIMAL);
            env.put("INUBIT_MCP_PROFILE", "../../x");

            assertThatThrownBy(() -> loader().locate())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("INUBIT_MCP_PROFILE")
                .hasMessageContaining(ProfileInfo.NAME_PATTERN.pattern());
        }

        @Test
        void theSelectedProfileMustBeTheOneInTheFile() throws IOException {
            write(profileFile("acme"), profileYaml("globex"));

            assertThatThrownBy(() -> loader().load(null, "acme"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("profile.name 'globex'")
                .hasMessageContaining("--profile acme");
            env.put("INUBIT_MCP_PROFILE", "acme");
            assertThatThrownBy(() -> loader().load(null, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("profile.name 'globex'")
                .hasMessageContaining("INUBIT_MCP_PROFILE=acme");
        }

        @Test
        void anInvalidDeclaredNameIsNotPrintedInTheMismatchError() throws IOException {
            // 002 US2 review P3a: no terminal control sequences from the file in the message
            write(profileFile("acme"), MINIMAL.replace("name: acme",
                "name: \"x\\e[2Jcleared\\u0007\""));

            assertThatThrownBy(() -> loader().load(null, "acme"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("profile.name (invalid profile.name)")
                .hasMessageNotContaining("\u001b").hasMessageNotContaining("[2J")
                .hasMessageNotContaining("\u0007");
        }

        @Test
        void theReservedNameAuditCannotBeSelected() throws IOException {
            write(profileFile("audit"), profileYaml("audit"));
            env.put("INUBIT_MCP_PROFILE", "audit");

            assertThatThrownBy(() -> loader().locate())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("INUBIT_MCP_PROFILE").hasMessageContaining("audit");
        }

        @Test
        void aMatchingProfileLoads() throws IOException {
            Path acme = write(profileFile("acme"), profileYaml("acme"));

            LoadedConfig loaded = loader().load(null, "acme");

            assertThat(loaded.source()).isEqualTo(acme);
            assertThat(loaded.config().profile().name()).isEqualTo("acme");
        }

        @Test
        void anExplicitConfigFileNeedsNoMatchingName() throws IOException {
            Path cli = write(home.resolve("cli.yaml"), profileYaml("globex"));

            assertThat(loader().load(cli.toString(), null).config().profile().name())
                .isEqualTo("globex");
        }

        @Test
        void loadUsesTheLocatedFile() throws IOException {
            Path cli = write(home.resolve("cli.yaml"), MINIMAL);

            LoadedConfig loaded = loader().load(cli.toString());

            assertThat(loaded.source()).isEqualTo(cli);
            assertThat(loaded.config().groups()).hasSize(1);
        }
    }

    @Nested
    class Parsing {

        @Test
        void parsesStagesAndServersInConfigOrder() {
            LoadedConfig loaded = load("full.yaml");

            assertThat(loaded.config().groups()).extracting(GroupConfig::name)
                .containsExactly("dev", "test", "prod");
            assertThat(loaded.config().groups().get(1).nodes())
                .extracting(NodeConfig::name)
                .containsExactly("inubit01", "inubit02");
            assertThat(loaded.config().effectiveNodes()).extracting(s -> s.id().value())
                .containsExactly("dev/inubit01", "test/inubit01", "test/inubit02",
                    "prod/inubit01", "prod/inubit02");
            assertThat(loaded.credentialKeys()).isEmpty();
        }

        @Test
        void parsesTopLevelSettings() {
            ProfileConfig config = load("full.yaml").config();

            assertThat(config.logLevel()).isEqualTo(LogLevel.DEBUG);
            assertThat(config.auditDirectory()).isEqualTo(home.resolve("audit-dir"));
            assertThat(config.resultLimits()).isEqualTo(new ResultLimits(20, 1000));
        }

        @Test
        void topLevelDefaultsApplyWhenOmitted() {
            ProfileConfig config = load("minimal.yaml").config();

            assertThat(config.logLevel()).isEqualTo(LogLevel.INFO);
            // 002 research D-7: one audit directory per profile, below the injected home
            assertThat(config.auditDirectory())
                .isEqualTo(home.resolve(".inubit-mcp").resolve(config.profile().name())
                    .resolve("audit"));
            assertThat(config.resultLimits()).isEqualTo(new ResultLimits(100, 50000));
            assertThat(ResultLimits.DEFAULT).isEqualTo(new ResultLimits(100, 50000));
        }

        @Test
        void partialResultLimitsKeepTheOtherDefault() {
            ProfileConfig config = loader().parse(MINIMAL + "resultLimits:\n  maxItems: 7\n",
                home.resolve("inline.yaml")).config();

            assertThat(config.resultLimits()).isEqualTo(new ResultLimits(7, 50000));
        }

        @Test
        void builtInDefaultsApplyToEveryInheritedField() {
            EffectiveNodeConfig server = server(load("minimal.yaml"), "dev/node1");

            assertThat(server.timeout()).isEqualTo(Duration.parse("PT5S"));
            assertThat(server.cliTimeout()).isEqualTo(Duration.parse("PT30S"));
            assertThat(server.cliExportTimeout()).isEqualTo(Duration.parse("PT120S"));
            assertThat(server.hangingThreshold()).isEqualTo(Duration.parse("PT60M"));
            assertThat(server.confirmationTtl()).isEqualTo(Duration.parse("PT5M"));
            assertThat(server.inventory().owner()).as("no built-in owner (FR-014)")
                .isEmpty();
            assertThat(server.inventory().cacheTtl()).isEqualTo(Duration.parse("PT10M"));
            assertThat(server.write().enabled()).isFalse();
            assertThat(server.write().productionOptIn()).isFalse();
            assertThat(server.write().confirmation()).isEqualTo(ConfirmationMode.SERVER);
            assertThat(server.versionLine()).isEqualTo(VersionLine.AUTO);
            assertThat(server.production()).isFalse();
            assertThat(server.allowInsecureHttp()).isFalse();
            assertThat(server.tls().trustStore()).isEmpty();
            assertThat(server.tls().disableHostnameVerification()).isFalse();
            assertThat(server.tls().pinnedCertificateSha256()).isEmpty();
            assertThat(server.cli().home()).isEmpty();
            assertThat(server.cli().javaHome()).isEmpty();
            assertThat(server.cliConfigured()).isFalse();
        }

        @Test
        void defaultsSectionAppliesWhereStageAndServerAreSilent() {
            EffectiveNodeConfig server = server(load("full.yaml"), "dev/inubit01");

            assertThat(server.timeout()).isEqualTo(Duration.parse("PT7S"));
            assertThat(server.cliTimeout()).isEqualTo(Duration.parse("PT31S"));
            assertThat(server.cliExportTimeout()).isEqualTo(Duration.parse("PT121S"));
            assertThat(server.hangingThreshold()).isEqualTo(Duration.parse("PT61M"));
            assertThat(server.confirmationTtl()).isEqualTo(Duration.parse("PT6M"));
            assertThat(server.inventory().owner()).contains("DEFOWNER");
            assertThat(server.inventory().cacheTtl()).isEqualTo(Duration.parse("PT11M"));
            assertThat(server.cli().home()).contains(home.resolve("client"));
            assertThat(server.cli().javaHome()).contains(Path.of("/opt/jdk17"));
            assertThat(server.cliConfigured()).isTrue();
        }

        @Test
        void stageOverridesDefaults() {
            EffectiveNodeConfig server = server(load("full.yaml"), "test/inubit01");

            assertThat(server.timeout()).isEqualTo(Duration.parse("PT8S"));
            assertThat(server.cliTimeout()).isEqualTo(Duration.parse("PT32S"));
            assertThat(server.cliExportTimeout()).isEqualTo(Duration.parse("PT122S"));
            assertThat(server.hangingThreshold()).isEqualTo(Duration.parse("PT62M"));
            assertThat(server.confirmationTtl()).isEqualTo(Duration.parse("PT7M"));
            assertThat(server.inventory().owner()).contains("STAGEOWNER");
            assertThat(server.inventory().cacheTtl()).isEqualTo(Duration.parse("PT11M"));
            assertThat(server.versionLine()).isEqualTo(VersionLine.V8_1);
            assertThat(server.write().enabled()).isTrue();
            assertThat(server.write().confirmation()).isEqualTo(ConfirmationMode.SERVER);
            assertThat(server.tls().trustStore())
                .contains(home.resolve(".config/inubit-mcp/truststore.p12"));
            assertThat(server.cli().home()).contains(Path.of("/opt/stage-client"));
            assertThat(server.cli().javaHome()).contains(Path.of("/opt/jdk17"));
        }

        @Test
        void serverOverridesStageFieldByField() {
            EffectiveNodeConfig server = server(load("full.yaml"), "test/inubit02");

            assertThat(server.timeout()).isEqualTo(Duration.parse("PT9S"));
            assertThat(server.cliTimeout()).isEqualTo(Duration.parse("PT33S"));
            assertThat(server.cliExportTimeout()).isEqualTo(Duration.parse("PT123S"));
            assertThat(server.hangingThreshold()).isEqualTo(Duration.parse("PT63M"));
            assertThat(server.confirmationTtl()).isEqualTo(Duration.parse("PT8M"));
            assertThat(server.inventory().owner()).contains("SRVOWNER");
            assertThat(server.inventory().cacheTtl()).isEqualTo(Duration.parse("PT13M"));
            assertThat(server.versionLine()).isEqualTo(VersionLine.V9_X);
            assertThat(server.write().enabled()).isFalse();
            assertThat(server.write().confirmation()).isEqualTo(ConfirmationMode.CLIENT);
            // trustStore comes from the stage, the other TLS fields from the server
            assertThat(server.tls().trustStore())
                .contains(home.resolve(".config/inubit-mcp/truststore.p12"));
            assertThat(server.tls().disableHostnameVerification()).isTrue();
            assertThat(server.tls().pinnedCertificateSha256()).contains(
                "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89");
            assertThat(server.cli().url())
                .isEqualTo(URI.create(
                    "https://cli.example.test:8443/ibis/servlet/IBISSoapServlet"));
            assertThat(server.cli().home()).contains(Path.of("/opt/server-client"));
            assertThat(server.cli().javaHome()).contains(Path.of("/opt/server-jdk"));
        }

        @Test
        void cliUrlDefaultsToTheSoapServletOfTheBaseUrl() {
            LoadedConfig loaded = load("full.yaml");

            assertThat(server(loaded, "test/inubit01").cli().url()).isEqualTo(
                URI.create(
                    "https://inubit-test-01.example.test:8443/ibis/servlet/IBISSoapServlet"));
            assertThat(server(loaded, "dev/inubit01").baseUrl())
                .isEqualTo(URI.create("https://inubit-dev-01.example.test:8443"));
        }

        @Test
        void productionIsAStagePropertyAndLocksWriteAccessWithoutOptIn() {
            LoadedConfig loaded = load("full.yaml");
            EffectiveNodeConfig optedIn = server(loaded, "prod/inubit01");
            EffectiveNodeConfig locked = server(loaded, "prod/inubit02");

            assertThat(optedIn.production()).isTrue();
            assertThat(optedIn.write().productionOptIn()).isTrue();
            assertThat(optedIn.effectiveWriteEnabled()).isTrue();
            assertThat(locked.production()).isTrue();
            assertThat(locked.write().enabled()).isTrue();
            assertThat(locked.effectiveWriteEnabled()).isFalse();
            assertThat(locked.allowInsecureHttp()).isTrue();
            assertThat(server(loaded, "dev/inubit01").effectiveWriteEnabled()).isTrue();
            assertThat(server(loaded, "test/inubit02").effectiveWriteEnabled()).isFalse();
        }

        @Test
        void topLevelXKeysAndAnchorsAreAccepted() {
            LoadedConfig loaded = load("anchors.yaml");

            for (String id : List.of("dev/node1", "qa/node1", "qa/node2")) {
                EffectiveNodeConfig server = server(loaded, id);
                assertThat(server.tls().trustStore())
                    .contains(home.resolve(".config/inubit-mcp/truststore.p12"));
                assertThat(server.tls().disableHostnameVerification()).isTrue();
                assertThat(server.tls().pinnedCertificateSha256()).isPresent();
            }
        }

        @Test
        void anchorsOnSequencesAreResolved() {
            String yaml = """
                profile:
                  name: acme
                x-nodes: &servers
                  - name: node1
                    baseUrl: https://inubit.example.test:8443
                groups:
                  - name: a
                    nodes: *servers
                  - name: b
                    nodes: *servers
                """;

            assertThat(loader().parse(yaml, home.resolve("seq.yaml")).config().effectiveNodes())
                .extracting(s -> s.id().value())
                .containsExactly("a/node1", "b/node1");
        }

        @Test
        void anchorsOnScalarsAreRejectedWithAHint() {
            // Jackson's YAML parser does not report anchors on scalar values
            String yaml = MINIMAL + "x-t: &t PT3S\ndefaults:\n  timeout: *t\n";

            assertThatThrownBy(() -> loader().parse(yaml, home.resolve("scalar.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Undefined alias '*t'")
                .hasMessageContaining("mappings and sequences");
        }

        @Test
        void unknownTopLevelKeyIsRejected() {
            assertThatThrownBy(() -> load("unknown-top-level-key.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown key 'logLevl'");
        }

        @Test
        void unknownNestedKeyIsRejectedWithItsPath() {
            assertThatThrownBy(() -> load("unknown-server-key.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown key 'tiemout'")
                .hasMessageContaining("groups[0].nodes[0]");
        }

        @Test
        void xKeysBelowTheTopLevelAreRejected() {
            assertThatThrownBy(() -> load("nested-x-key.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown key 'x-comment'");
        }

        @Test
        void credentialKeysAreReportedWithTheirPathsAndNeverTheirValues() {
            LoadedConfig loaded = load("credentials-in-yaml.yaml");

            assertThat(loaded.credentialKeys()).containsExactlyInAnyOrder(
                "username", "x-shared.password", "groups[0].credentials",
                "groups[0].nodes[0].username", "groups[0].nodes[0].password",
                "groups[0].nodes[0].tls.trustStorePassword");
            assertThat(loaded.toString()).doesNotContain("do-not-put-me-here", "jdoe",
                "in-an-x-block", "also-not-here", "top-level-user");
            assertThat(loaded.config().effectiveNodes()).hasSize(1);
        }

        @Test
        void duplicateKeysAreRejected() {
            assertThatThrownBy(() -> load("duplicate-key.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("name");
        }

        @Test
        void malformedYamlIsRejectedWithItsLocation() {
            assertThatThrownBy(() -> load("malformed.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("malformed.yaml")
                .hasMessageContaining("line");
        }

        @Test
        void undefinedAliasIsRejected() {
            assertThatThrownBy(() -> load("undefined-alias.yaml"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("missing");
        }

        @Test
        void emptyDocumentIsRejected() {
            assertThatThrownBy(() -> loader().parse("# nothing\n", home.resolve("empty.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("empty");
        }

        @Test
        void topLevelSequenceIsRejected() {
            assertThatThrownBy(() -> loader().parse("- a\n- b\n", home.resolve("list.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("mapping");
        }

        @Test
        void invalidValueIsRejected() {
            assertThatThrownBy(() -> loader().parse(MINIMAL + "logLevel: VERBOSE\n",
                home.resolve("bad.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("logLevel")
                .hasMessageContaining("ERROR, WARN, INFO, DEBUG, TRACE")
                .hasMessageNotContaining("VERBOSE");
        }

        @Test
        void explicitNullAuditDirectoryUsesTheDefault() {
            ProfileConfig config = loader().parse(MINIMAL + "auditDirectory: null\n",
                home.resolve("null.yaml")).config();

            assertThat(config.auditDirectory()).isEqualTo(home.resolve(".inubit-mcp/acme/audit"));
        }

        @Test
        void twoProfilesGetSeparateDefaultAuditDirectories() {
            ProfileConfig acme = loader().parse(MINIMAL, home.resolve("acme.yaml")).config();
            ProfileConfig globex = loader().parse(MINIMAL.replace("name: acme", "name: globex"),
                home.resolve("globex.yaml")).config();

            assertThat(acme.auditDirectory()).isEqualTo(home.resolve(".inubit-mcp/acme/audit"));
            assertThat(globex.auditDirectory())
                .isEqualTo(home.resolve(".inubit-mcp/globex/audit"));
        }

        @Test
        void anInvalidProfileNameNeverBecomesPartOfTheDefaultAuditDirectory() {
            // the name is reported by ConfigValidator; the server never starts with it
            ProfileConfig config = loader().parse(MINIMAL.replace("name: acme",
                "name: \"../../etc\""), home.resolve("odd.yaml")).config();

            assertThat(config.auditDirectory().normalize().toString()).doesNotContain("etc");
            assertThat(config.auditDirectory())
                .isEqualTo(home.resolve(".inubit-mcp/(invalid profile.name)/audit"))
                .as("not the 001 directory").isNotEqualTo(home.resolve(".inubit-mcp/audit"));
            assertThat(config.auditDirectory().normalize().startsWith(home.resolve(".inubit-mcp")))
                .isTrue();
        }

        @Test
        void durationsMustBeIsoStrings() {
            for (String value : new String[] {"5", "abc", "true", "{a: 1}"}) {
                String yaml = MINIMAL + "defaults:\n  timeout: " + value + "\n";
                assertThatThrownBy(() -> loader().parse(yaml, home.resolve("d.yaml")))
                    .as(value)
                    .isInstanceOf(ConfigException.class)
                    .hasMessageContaining("defaults.timeout")
                    .hasMessageContaining("ISO-8601");
            }
        }

        @Test
        void nestedDurationErrorsNameTheKeyPath() {
            String yaml = MINIMAL.replace("baseUrl:", "cliTimeout: 30\n        baseUrl:");

            assertThatThrownBy(() -> loader().parse(yaml, home.resolve("d.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("groups[0].nodes[0].cliTimeout");
        }

        @Test
        void userInfoInUrlsIsReportedAndTheValuesAreDroppedWithoutEcho() {
            String yaml = """
                profile:
                  name: acme
                groups:
                  - name: dev
                    cli:
                      url: https://cliuser:cl1pw@cli.example.test/ibis/servlet/IBISSoapServlet
                    nodes:
                      - name: node1
                        baseUrl: https://jdoe:hunter2@inubit.example.test:8443
                """;

            LoadedConfig loaded = loader().parse(yaml, home.resolve("u.yaml"));

            assertThat(loaded.urlProblems()).hasSize(2);
            assertThat(loaded.urlProblems()).anySatisfy(p -> assertThat(p)
                .contains("dev/node1", "baseUrl", "user info"));
            assertThat(loaded.urlProblems()).anySatisfy(p -> assertThat(p)
                .contains("groups[0] 'dev'", "cli.url", "user info"));
            GroupConfig stage = loaded.config().groups().get(0);
            assertThat(stage.cli().url()).isEmpty();
            assertThat(stage.nodes().get(0).baseUrl()).isEmpty();
            assertThat(loaded.toString() + loaded.urlProblems())
                .doesNotContain("hunter2", "jdoe", "cl1pw", "cliuser", "example.test");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://jdoe:hunter2@inubit.example.test:8443",
            "https://jdoe:hunter2@x@inubit.example.test:8443",
            "https://jdoe:hunter2/q@inubit.example.test:8443",
            "https://jdoe:hunter2#x@inubit.example.test:8443",
            "https://jdoe:hunter2?x@inubit.example.test:8443",
            "https://inubit.example.test:8443/path@hunter2",
            "https://jdoe:hunter2@bad host"})
        void anyAtSignInTheBaseUrlCountsAsUserInfoAndDropsTheValue(String url) {
            String yaml = MINIMAL.replace("https://inubit.example.test:8443", "\"" + url + "\"");

            LoadedConfig loaded = loader().parse(yaml, home.resolve("at.yaml"));

            assertThat(loaded.urlProblems()).singleElement().satisfies(p -> assertThat(p)
                .contains("dev/node1", "baseUrl", "user info")
                .doesNotContain("hunter2", "jdoe", "example.test"));
            assertThat(loaded.config().groups().get(0).nodes().get(0).baseUrl()).isEmpty();
            assertThat(loaded.toString()).doesNotContain("hunter2", "jdoe");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "https://jdoe:hunter2@x@cli.example.test/x",
            "https://jdoe:hunter2/q@cli.example.test/x",
            "https://jdoe:hunter2#x@cli.example.test/x"})
        void anyAtSignInTheCliUrlCountsAsUserInfoAndDropsTheValue(String url) {
            String yaml = MINIMAL + "        cli:\n          url: \"" + url + "\"\n";

            LoadedConfig loaded = loader().parse(yaml, home.resolve("at-cli.yaml"));

            assertThat(loaded.urlProblems()).singleElement().satisfies(p -> assertThat(p)
                .contains("dev/node1", "cli.url", "user info")
                .doesNotContain("hunter2", "jdoe", "example.test"));
            assertThat(server(loaded, "dev/node1").cli().url()).isEqualTo(URI.create(
                "https://inubit.example.test:8443/ibis/servlet/IBISSoapServlet"));
            assertThat(loaded.toString()).doesNotContain("hunter2", "jdoe");
        }

        @Test
        void queryAndFragmentInTheBaseUrlAreReportedAndStripped() {
            String yaml = MINIMAL.replace("https://inubit.example.test:8443",
                "https://inubit.example.test:8443/base/?token=abc#frag");

            LoadedConfig loaded = loader().parse(yaml, home.resolve("q.yaml"));

            assertThat(loaded.urlProblems()).singleElement().satisfies(p -> assertThat(p)
                .contains("dev/node1", "query or fragment").doesNotContain("abc"));
            assertThat(server(loaded, "dev/node1").baseUrl())
                .isEqualTo(URI.create("https://inubit.example.test:8443/base"));
            assertThat(loaded.toString()).doesNotContain("token=abc");
        }

        @Test
        void malformedUrlIsRejectedWithoutEchoingIt() {
            String yaml = MINIMAL.replace("https://inubit.example.test:8443",
                "\"https://bad host/hunter2\"");

            assertThatThrownBy(() -> loader().parse(yaml, home.resolve("m.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("groups[0].nodes[0].baseUrl")
                .hasMessageNotContaining("hunter2");
        }

        @Test
        void effectiveConfigRejectsUrlsWithUserInfo() {
            NodeConfig entry = new NodeConfig("node1",
                java.util.Optional.of(URI.create("https://u:secretpw@h.example.test")), false, null,
                null, null, null, null, null, null, null, null, null);
            GroupConfig stage = new GroupConfig("dev", false, List.of(entry), null, null, null,
                null, null, null, null, null, null, null);

            assertThatThrownBy(() -> EffectiveNodeConfig.resolve(Defaults.EMPTY, stage, entry,
                "INUBIT_ACME"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secretpw");
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://u:secretpw#x@h.example.test",
            "https://h.example.test/secretpw@x"})
        void effectiveConfigRejectsUrlsWithAnyAtSign(String url) {
            NodeConfig entry = new NodeConfig("node1",
                java.util.Optional.of(URI.create(url)), false, null,
                null, null, null, null, null, null, null, null, null);
            GroupConfig stage = new GroupConfig("dev", false, List.of(entry), null, null, null,
                null, null, null, null, null, null, null);

            assertThatThrownBy(() -> EffectiveNodeConfig.resolve(Defaults.EMPTY, stage, entry,
                "INUBIT_ACME"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secretpw");
        }

        @Test
        void trailingSlashesAreRemovedFromThePathOnly() {
            String yaml = MINIMAL.replace("https://inubit.example.test:8443",
                "https://inubit.example.test:8443/base//");

            assertThat(server(loader().parse(yaml, home.resolve("s.yaml")), "dev/node1").baseUrl())
                .isEqualTo(URI.create("https://inubit.example.test:8443/base"));
        }

        @Test
        void mergeKeysAreRejectedWithAHint() {
            String yaml = """
                profile:
                  name: acme
                x-tls: &tls
                  disableHostnameVerification: false
                groups:
                  - name: dev
                    tls:
                      <<: *tls
                    nodes:
                      - name: node1
                        baseUrl: https://inubit.example.test:8443
                """;

            assertThatThrownBy(() -> loader().parse(yaml, home.resolve("merge.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Merge keys")
                .hasMessageContaining("anchor");
        }

        @Test
        void multipleYamlDocumentsAreRejected() {
            assertThatThrownBy(() -> loader().parse(MINIMAL + "---\n" + MINIMAL,
                home.resolve("multi.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("one YAML document");
        }

        @Test
        void nullListEntriesAreRejectedWithTheirPath() {
            String yaml = "profile:\n  name: acme\ngroups:\n  - name: dev\n    nodes:\n      -\n";

            assertThatThrownBy(() -> loader().parse(yaml, home.resolve("null-entry.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Empty list entry at groups[0].nodes[0]");
        }

        @Test
        void serverIdsIncludeServersWithoutBaseUrl() {
            ProfileConfig config = loader().parse("""
                profile:
                  name: acme
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                      - name: INVALID
                """, home.resolve("ids.yaml")).config();

            assertThat(config.nodeIds()).containsExactly(NodeId.parse("dev/node1"));
            assertThat(config.resolvableNodes()).isEmpty();
        }

        @Test
        void unreadableFileIsReported() {
            Path missing = home.resolve("missing.yaml");

            assertThatThrownBy(() -> loader().loadFile(missing))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(missing.toString());
        }
    }

    /** T011 (feature 003, FR-001): the optional top-level {@code workspace}. */
    @Nested
    class Workspace {

        @Test
        void defaultsToTheProfilesDirectoryBelowTheHome() {
            ProfileConfig config = loader().parse(MINIMAL, home.resolve("acme.yaml")).config();

            assertThat(config.workspace()).isEqualTo(home.resolve(".inubit-mcp/acme/workspace"));
            assertThat(loader().parse(MINIMAL + "workspace: null\n", home.resolve("n.yaml"))
                .config().workspace()).isEqualTo(home.resolve(".inubit-mcp/acme/workspace"));
        }

        @Test
        void twoProfilesGetSeparateDefaultWorkspaces() {
            ProfileConfig globex = loader().parse(MINIMAL.replace("name: acme", "name: globex"),
                home.resolve("globex.yaml")).config();

            assertThat(globex.workspace()).isEqualTo(home.resolve(".inubit-mcp/globex/workspace"));
        }

        @Test
        void aGivenWorkspaceIsUsedWithTheHomeExpanded() {
            assertThat(loader().parse(MINIMAL + "workspace: ~/work/acme-inubit\n",
                home.resolve("a.yaml")).config().workspace())
                .isEqualTo(home.resolve("work/acme-inubit"));
            assertThat(loader().parse(MINIMAL + "workspace: /srv/inubit/acme\n",
                home.resolve("b.yaml")).config().workspace())
                .isEqualTo(Path.of("/srv/inubit/acme"));
            // relative paths are loaded as given; ConfigValidator reports them
            assertThat(loader().parse(MINIMAL + "workspace: relative/ws\n",
                home.resolve("c.yaml")).config().workspace()).isEqualTo(Path.of("relative/ws"));
        }

        @Test
        void anInvalidProfileNameNeverBecomesPartOfTheDefaultWorkspace() {
            ProfileConfig config = loader().parse(MINIMAL.replace("name: acme",
                "name: \"../../etc\""), home.resolve("odd.yaml")).config();

            assertThat(config.workspace())
                .isEqualTo(home.resolve(".inubit-mcp/(invalid profile.name)/workspace"));
        }

        @Test
        void aWorkspaceThatIsNoPathIsRefused() {
            assertThatThrownBy(() -> loader().parse(MINIMAL + "workspace: [a, b]\n",
                home.resolve("d.yaml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("workspace")
                .hasMessageContaining("a file system path");
        }
    }
}
