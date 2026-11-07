package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.testing.Platform;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code --check-config} warnings of feature 017 (T024, contracts/configuration-delta.md →
 * {@code --check-config}, research D-17): {@code defaults.cliClients} entries, JDK majors,
 * {@code V9_X} and the servlet address of a pinned 9.x node.
 */
class ConfigValidatorCliClientsTest {

    private static final Path HOME = Platform.absolutePath("/home/test");
    private static final String HOST = "inubit-acme.example.test";
    private static final String SERVLET = EffectiveNodeConfig.DEFAULT_CLI_PATH;

    private final Map<String, String> env = new HashMap<>();
    private final Set<Path> existing = new HashSet<>();
    /** {@code <javaHome>/release} contents by JDK directory. */
    private final Map<Path, String> releases = new HashMap<>();
    private final List<String> allWarnings = new ArrayList<>();
    private boolean windows;
    private Path tempDirectory = Platform.posixPath("/var/tmp/inubit-test");

    @BeforeEach
    void setUp() {
        env.put("JAVA_HOME", "/opt/jdk-server");
        for (String client : List.of("/opt/c81", "/opt/c90", "/opt/c91", "/opt/default")) {
            existing.add(Path.of(client));
            existing.add(Path.of(client, "bin", "startcli.sh"));
            existing.add(Path.of(client, "bin", "startcli.bat"));
        }
        for (String jdk : List.of("/opt/jdk-17", "/opt/jdk-21", "/opt/jdk-11", "/opt/jdk-server")) {
            existing.add(Path.of(jdk));
        }
        release("/opt/jdk-17", "17.0.9");
        release("/opt/jdk-21", "21.0.2");
        release("/opt/jdk-11", "11.0.21");
    }

    @AfterEach
    void noWarningLeaks() {
        // leak check: no URL, host or credential reference in any warning of these profiles
        assertThat(String.join("\n", allWarnings)).doesNotContain("://", HOST, "S3cr3t",
            "USERNAME", "PASSWORD");
    }

    private void release(String javaHome, String version) {
        releases.put(Path.of(javaHome).resolve("release"),
            "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
    }

    private ValidationReport validate(String yaml) {
        LoadedConfig loaded = new ConfigLoader(Map.of(), HOME, windows)
            .parse(yaml, HOME.resolve("config.yaml"));
        ValidationReport report = new ConfigValidator(existing::contains, env, windows,
            tempDirectory, source -> List.of(),
            workspace -> new WorkspaceDirectory.Usable(false), StartCliHost.unchecked(),
            path -> Optional.ofNullable(releases.get(path))).validate(loaded);
        allWarnings.addAll(report.warnings());
        return report;
    }

    private List<String> warnings(String yaml) {
        ValidationReport report = validate(yaml);
        assertThat(report.errors()).isEmpty();
        return report.warnings();
    }

    private static String profile(String defaults, String nodeSettings) {
        return """
            profile:
              name: acme
            defaults:
              inventory:
                owner: INTEGRATION
            """ + defaults.indent(2) + """
            groups:
              - name: dev
                nodes:
                  - name: node1
                    baseUrl: https://%s:8443
            """.formatted(HOST) + nodeSettings.indent(8);
    }

    private static final String ENTRIES = """
        cliClients:
          "8.1": { home: /opt/c81, javaHome: /opt/jdk-17 }
          "9.0": { home: /opt/c90, javaHome: /opt/jdk-21 }
        """;

    @Nested
    class Entries {

        @Test
        void aMatchingProfileHasNoWarning() {
            assertThat(warnings(profile(ENTRIES, ""))).isEmpty();
        }

        @Test
        void aKeyTheMatrixDoesNotDeclareIsAWarning() {
            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.1": { home: /opt/c91, javaHome: /opt/jdk-21 }
                """, ""));

            assertThat(warnings).containsExactly("defaults.cliClients.\"9.1\": INUBIT 9.1 is not"
                + " declared supported; nodes detected as 9.1 use this client with unverified reads"
                + " and refused writes");
        }

        @Test
        void aMissingHomeOrJavaHomeDirectoryIsAWarning() {
            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/missing-client, javaHome: /opt/missing-jdk }
                """, ""));

            assertThat(warnings).containsExactly(
                "defaults.cliClients.\"9.0\": home " + Path.of("/opt/missing-client")
                    + " does not exist",
                "defaults.cliClients.\"9.0\": javaHome " + Path.of("/opt/missing-jdk")
                    + " does not exist");
        }

        @Test
        void anExistingHomeWithoutStartCliIsAWarning() {
            // review of stage 7, S1: typically the INUBIT installation instead of its client
            existing.add(Path.of("/opt/inubit-9.0"));

            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/inubit-9.0, javaHome: /opt/jdk-21 }
                """, ""));

            assertThat(warnings).containsExactly("defaults.cliClients.\"9.0\": "
                + Path.of("/opt/inubit-9.0", "bin", "startcli.sh") + " not found; CLI-backed"
                + " capabilities report CLI_UNAVAILABLE on nodes using this entry");
        }

        @Test
        void anEntryWithoutAnyJdkIsAWarning() {
            // review of stage 7, N4: no entry javaHome, no defaults.cliJavaHome, no JAVA_HOME
            env.remove("JAVA_HOME");

            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/c90 }
                """, ""));

            assertThat(warnings).contains("defaults.cliClients.\"9.0\": neither its javaHome,"
                + " defaults.cliJavaHome nor JAVA_HOME is set; CLI-backed capabilities report"
                + " CLI_UNAVAILABLE on nodes using this entry without their own cli.javaHome");
        }

        @Test
        void theJdkOfAn81Or90EntryMustBeTheOneThatVersionShipsWith() {
            List<String> warnings = warnings(profile("""
                cliClients:
                  "8.1": { home: /opt/c81, javaHome: /opt/jdk-21 }
                  "9.0": { home: /opt/c90, javaHome: /opt/jdk-17 }
                """, ""));

            assertThat(warnings).containsExactly(
                "defaults.cliClients.\"8.1\": the JDK in " + Path.of("/opt/jdk-21")
                    + " is Java 21; INUBIT 8.1 ships with Java 17",
                "defaults.cliClients.\"9.0\": the JDK in " + Path.of("/opt/jdk-17")
                    + " is Java 17; INUBIT 9.0 ships with Java 21");
        }

        @Test
        void anEntryWithoutJavaHomeUsesTheDefaultJdkThenJavaHome() {
            release("/opt/jdk-server", "11.0.2");

            List<String> withDefault = warnings(profile("""
                cliJavaHome: /opt/jdk-17
                cliClients:
                  "9.0": { home: /opt/c90 }
                """, ""));
            List<String> withServerJdk = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/c90 }
                """, ""));

            assertThat(withDefault).containsExactly("defaults.cliClients.\"9.0\": the JDK in "
                + Path.of("/opt/jdk-17") + " is Java 17; INUBIT 9.0 ships with Java 21");
            // JAVA_HOME of the server's environment is named as given
            assertThat(withServerJdk).containsExactly("defaults.cliClients.\"9.0\": the JDK in "
                + "/opt/jdk-server is Java 11; INUBIT 9.0 ships with Java 21");
        }

        @Test
        void anUnknownJdkMajorOrAnotherVersionIsNoJdkWarning() {
            // /opt/jdk-server has no release file; 9.1 has no documented JDK (reviews.md row 6)
            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/c90, javaHome: /opt/jdk-server }
                  "9.1": { home: /opt/c91, javaHome: /opt/jdk-11 }
                """, ""));

            assertThat(warnings).containsExactly("defaults.cliClients.\"9.1\": INUBIT 9.1 is not"
                + " declared supported; nodes detected as 9.1 use this client with unverified reads"
                + " and refused writes");
        }

        @Test
        void entriesAloneCountAsAConfiguredCli() {
            // the profile-level checks of a configured CLI apply to an AUTO node with entries only
            List<String> warnings = warnings("""
                profile:
                  name: acme
                defaults:
                  cliClients:
                    "9.0": { home: /opt/c90, javaHome: /opt/jdk-21 }
                groups:
                  - name: dev
                    nodes:
                      - name: node1
                        baseUrl: https://%s:8443
                """.formatted(HOST));

            assertThat(warnings).singleElement().asString().startsWith("No inventory.owner is set");
        }
    }

    @Nested
    class PinnedNodes {

        @Test
        void aNodePinnedTo90WithAnotherJdkIsAWarning() {
            List<String> warnings = warnings(profile("""
                cliHome: /opt/default
                cliJavaHome: /opt/jdk-17
                """, "versionLine: V9_0\n"));

            assertThat(warnings).containsExactly("dev/node1: the JDK in " + Path.of("/opt/jdk-17")
                + " is Java 17; INUBIT 9.0 ships with Java 21");
        }

        @Test
        void aNodePinnedTo81UsesItsOwnJdkThenJavaHome() {
            release("/opt/jdk-server", "21.0.1");

            List<String> own = warnings(profile(ENTRIES, """
                versionLine: 8.1.12
                cli:
                  home: /opt/default
                  javaHome: /opt/jdk-11
                """));
            List<String> server = warnings(profile(ENTRIES, """
                versionLine: V8_1
                cli:
                  home: /opt/default
                """));

            assertThat(own).containsExactly("dev/node1: the JDK in " + Path.of("/opt/jdk-11")
                + " is Java 11; INUBIT 8.1 ships with Java 17");
            assertThat(server).containsExactly("dev/node1: the JDK in "
                + "/opt/jdk-server is Java 21; INUBIT 8.1 ships with Java 17");
        }

        @Test
        void aNodeUsingTheEntryAndItsJdkIsNotWarnedTwice() {
            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: /opt/c90, javaHome: /opt/jdk-17 }
                """, "versionLine: V9_0\n"));

            assertThat(warnings).containsExactly("defaults.cliClients.\"9.0\": the JDK in "
                + Path.of("/opt/jdk-17") + " is Java 17; INUBIT 9.0 ships with Java 21");
        }

        @Test
        void aNodeUsingTheEntryWithItsOwnJdkIsWarned() {
            List<String> warnings = warnings(profile(ENTRIES, """
                versionLine: V9_0
                cli:
                  javaHome: /opt/jdk-17
                """));

            assertThat(warnings).containsExactly("dev/node1: the JDK in " + Path.of("/opt/jdk-17")
                + " is Java 17; INUBIT 9.0 ships with Java 21");
        }

        @Test
        void autoAndWholeMajorLinesAreNotJdkChecked() {
            List<String> auto = warnings(profile("""
                cliHome: /opt/default
                cliJavaHome: /opt/jdk-11
                cliClients:
                  "9.1": { home: /opt/c91, javaHome: /opt/jdk-21 }
                """, ""));

            assertThat(auto).noneMatch(warning -> warning.startsWith("dev/node1"));
        }

        @Test
        void a081ProfileWithoutVersionEntriesKeepsItsWarnings() {
            // FR-009: no cliClients and no 9.x node: the 0.8.0 output, without a JDK warning
            List<String> warnings = warnings(profile("""
                cliHome: /opt/default
                cliJavaHome: /opt/jdk-11
                """, "versionLine: V8_1\n"));

            assertThat(warnings).isEmpty();
        }
    }

    @Nested
    class Lines {

        @Test
        void versionLine9xSuggestsV90OrAuto() {
            List<String> warnings = warnings(profile("", "versionLine: V9_X\n"));

            assertThat(warnings).containsExactly("versionLine V9_X of dev/node1 does not select"
                + " 9.0 support; use V9_0 (INUBIT 9.0.x) or AUTO");
        }

        @Test
        void aPinned9xNodeWithTheServletCliUrlIsAWarning() {
            String url = "url: https://" + HOST + ":8443" + SERVLET + "\n";

            List<String> line = warnings(profile("", "versionLine: V9_0\ncli:\n  " + url));
            List<String> pin = warnings(profile("", "versionLine: 9.0.3\ncli:\n  " + url));

            String expected = "cli.url of dev/node1 uses /ibis/servlet/IBISSoapServlet, which"
                + " INUBIT 9.0.2 and later removed; remove cli.url or set it to the base URL";
            assertThat(line).containsExactly(expected);
            assertThat(pin).containsExactly(expected);
        }

        @Test
        void theServletCliUrlOfAn81OrAutoNodeIsNoWarning() {
            String url = "url: https://" + HOST + ":8443" + SERVLET + "\n";

            assertThat(warnings(profile("", "versionLine: V8_1\ncli:\n  " + url))).isEmpty();
            assertThat(warnings(profile("", "cli:\n  " + url))).isEmpty();
            assertThat(warnings(profile("", "versionLine: V9_0\ncli:\n  url: https://" + HOST
                + ":8443/custom/cli\n"))).isEmpty();
        }
    }

    @Nested
    class OnWindows {

        @BeforeEach
        void onWindows() {
            windows = true;
            tempDirectory = Path.of("C:/Temp/inubit-test");
        }

        @Test
        void aNetworkPathAsEntryHomeIsAWarning() {
            String home = "\\\\files.example.test\\share\\client";
            existing.add(Path.of(home));

            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: '%s', javaHome: /opt/jdk-21 }
                """.formatted(home), ""));

            assertThat(warnings).containsExactly("defaults.cliClients.\"9.0\": home is a network"
                + " path (\\\\…); StartCLI needs a local drive path on Windows");
        }

        @Test
        void aCharacterTheCommandInterpreterInterpretsIsNamed() {
            existing.add(Path.of("/opt/c%90"));
            existing.add(Path.of("/opt/jdk;21"));

            List<String> warnings = warnings(profile("""
                cliClients:
                  "9.0": { home: '/opt/c%90', javaHome: '/opt/jdk;21' }
                """, ""));

            assertThat(warnings).containsExactly(
                "defaults.cliClients.\"9.0\": home contains a character the Windows command"
                    + " interpreter interprets (percent sign); CLI-backed capabilities report"
                    + " CLI_UNAVAILABLE",
                "defaults.cliClients.\"9.0\": javaHome contains a character the Windows command"
                    + " interpreter interprets (semicolon); CLI-backed capabilities report"
                    + " CLI_UNAVAILABLE");
        }
    }

    @Nested
    class ClientRange {

        private static final MajorMinor NINE_ZERO = MajorMinor.parse("9.0").orElseThrow();

        private ClientVersion client(String version, String from, String to) {
            return new ClientVersion(InubitVersion.parse(version).orElseThrow(),
                InubitVersion.parse(from), InubitVersion.parse(to));
        }

        @Test
        void aRangeThatExcludesTheEntrysKeyIsNamed() {
            Optional<String> warning = ConfigValidator.clientRangeWarning(NINE_ZERO,
                Path.of("/opt/c81"), client("8.1.17", "4.0.1", "8.1.17"));

            assertThat(warning).contains("defaults.cliClients.\"9.0\": the StartCLI client in "
                + Path.of("/opt/c81") + " supports INUBIT 4.0.1 to 8.1.17, which excludes 9.0");
            warning.ifPresent(allWarnings::add);
        }

        @Test
        void aRangeThatIncludesTheKeyOrCannotBeEvaluatedIsNoWarning() {
            assertThat(ConfigValidator.clientRangeWarning(NINE_ZERO, Path.of("/opt/c90"),
                client("9.0.7", "9.0.0", "9.0.10"))).isEmpty();
            assertThat(ConfigValidator.clientRangeWarning(NINE_ZERO, Path.of("/opt/c90"),
                new ClientVersion(InubitVersion.parse("9.0.7").orElseThrow(), Optional.empty(),
                    Optional.empty()))).isEmpty();
        }
    }
}
