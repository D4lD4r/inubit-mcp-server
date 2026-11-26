package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.TestNodeConfig;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner.Selection;
import de.dadecker.inubit.mcp.config.CliChoice;
import de.dadecker.inubit.mcp.config.CliChoice.AddressKind;
import de.dadecker.inubit.mcp.config.CliChoice.HomeSource;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ClientKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.testing.FakeStartCli;
import de.dadecker.inubit.mcp.testing.MutableClock;
import de.dadecker.inubit.mcp.testing.Platform;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 017 (T011, FR-005, research D-8): the messages of a missing StartCLI client name the
 * {@code defaults.cliClients} entry of the version only where such an entry would be chosen;
 * otherwise they are those of 0.8.0 (FR-009).
 */
@Timeout(30)
class CliRunnerMessagesTest {

    private static final String USER = "jdoe";
    private static final String PASSWORD = "S3cr3t-017-msg";
    private static final String BASE_URL = "https://inubit-acme.example.test:8443";
    private static final InubitVersion V8_1_17 =
        new InubitVersion(8, 1, OptionalInt.of(17), "8.1.17");
    private static final InubitVersion V9_0_7 =
        new InubitVersion(9, 0, OptionalInt.of(7), "9.0.7");
    private static final InubitVersion V9_1_3 =
        new InubitVersion(9, 1, OptionalInt.of(3), "9.1.3");
    private static final InubitVersion V7_4_0_90 = InubitVersion.parse("7.4.0.90").orElseThrow();
    private static final InubitVersion V6_1_0_89 = InubitVersion.parse("6.1.0.89").orElseThrow();

    @TempDir
    Path root;

    private ToolError refusal(EffectiveNodeConfig server, Optional<InubitVersion> version,
        Map<String, String> parentEnvironment) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = TestCliRunners.forHost(launcher, parentEnvironment,
            new CliResources("acme", PrivateFiles.forHost()), id -> version);
        try {
            runner.run(server, USER, Secret.of(PASSWORD),
                CliCommand.processErrorStart("110190387"), Duration.ofSeconds(5),
                new CredentialGuard(server.secretLabels(),
                    new MutableClock(Instant.parse("2026-10-01T08:00:00Z"))));
        } catch (ToolErrorException e) {
            assertThat(launcher.launchCount()).as("nothing is started").isZero();
            // leak check
            assertThat(e.error().toString()).doesNotContain(PASSWORD, "inubit-acme", "://");
            return e.error();
        }
        throw new AssertionError("expected a refusal");
    }

    private ToolError refusal(EffectiveNodeConfig server, Optional<InubitVersion> version) {
        return refusal(server, version, Map.of("PATH", Platform.searchPath()));
    }

    /**
     * Feature 025 T033 (FR-029): the selection of a 7.4 node whose matrix entry has
     * {@code ownClientOnly} and that found no client of its own; the production matrix sets the
     * flag with the flip (T035), so the choice is built here as {@code cliFor} makes it then.
     */
    private Selection ownClientOnlyWithoutClient(EffectiveNodeConfig server) {
        CliChoice choice = new CliChoice(new EffectiveNodeConfig.Cli(
                URI.create(BASE_URL + EffectiveNodeConfig.DEFAULT_CLI_PATH), Optional.empty(),
                Optional.empty()),
            HomeSource.NONE, Optional.empty(), AddressKind.SERVLET_PATH, OptionalInt.of(7), true,
            true);
        return new Selection(server.withCli(choice.cli()), choice, new ClientKey("", ""),
            Optional.empty(), Optional.of(V7_4_0_90));
    }

    private ToolError refusal(Selection selection) {
        FakeProcessLauncher launcher = FakeProcessLauncher.of("", "", 0);
        CliRunner runner = TestCliRunners.forHost(launcher, Map.of("PATH", Platform.searchPath()),
            new CliResources("acme", PrivateFiles.forHost()));
        try {
            runner.checkAvailable(selection);
        } catch (ToolErrorException e) {
            assertThat(launcher.launchCount()).as("nothing is started").isZero();
            return e.error();
        }
        throw new AssertionError("expected a refusal");
    }

    private static TestNodeConfig node() {
        return TestNodeConfig.node().baseUrl(BASE_URL).versionLine(VersionSetting.AUTO);
    }

    private static void assertTheVersionText(ToolError error, String version, String line) {
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message())
            .isEqualTo("No CLI home is configured for dev/node1 (INUBIT " + version + ")");
        assertThat(error.likelyCause()).isEqualTo("Neither cli.home, defaults.cliClients.\""
            + line + "\" nor cliHome is set");
        assertThat(error.nextStep()).isEqualTo("Add defaults.cliClients.\"" + line + "\".home"
            + " (the INUBIT " + line + " client directory) or set cliHome");
    }

    private static void assertThe080Text(ToolError error) {
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message()).isEqualTo("No CLI home is configured for dev/node1");
        assertThat(error.likelyCause()).isEqualTo("cliHome / cli.home is not set");
        assertThat(error.nextStep()).isEqualTo("Set cliHome to the INUBIT client directory");
    }

    private static void assertTheOwnClientText(ToolError error) {
        assertThat(error.code()).isEqualTo(ErrorCode.CLI_UNAVAILABLE);
        assertThat(error.message())
            .isEqualTo("No CLI home is configured for dev/node1 (INUBIT 7.4.0.90)");
        assertThat(error.likelyCause())
            .isEqualTo("Neither cli.home nor defaults.cliClients.\"7.4\" is set");
        assertThat(error.nextStep())
            .isEqualTo("Add defaults.cliClients.\"7.4\".home (the INUBIT 7.4 client directory)");
        assertThat(error.toString()).doesNotContain("cliHome");
    }

    @Test
    void anOwnClientOnlyVersionNamesItsEntryAndNeverCliHome() throws IOException {
        // feature 025 T033 (FR-029): only defaults.cliHome, or a cliClients entry of another line
        Path defaultClient = Files.createDirectories(root.resolve("default-client"));
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));
        VersionSetting pin = new VersionSetting.Pinned(V7_4_0_90);

        Selection onlyCliHome = ownClientOnlyWithoutClient(
            node().versionLine(pin).defaultCliHome(defaultClient).build());
        Selection otherEntry = ownClientOnlyWithoutClient(node().versionLine(pin)
            .cliClient("9.0", client90, Optional.empty()).build());

        assertThat(onlyCliHome.entryHint()).contains(new MajorMinor(7, 4));
        assertTheOwnClientText(refusal(onlyCliHome));
        assertThat(otherEntry.entryHint()).contains(new MajorMinor(7, 4));
        assertTheOwnClientText(refusal(otherEntry));
    }

    @Test
    void onNineWithoutAnyClientTheEntryOfTheVersionIsNamed() {
        assertTheVersionText(refusal(node().build(), Optional.of(V9_0_7)), "9.0.7", "9.0");
    }

    @Test
    void withCliClientsConfiguredTheMissingEntryIsNamedBelowNineToo() throws IOException {
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));
        EffectiveNodeConfig server = node().cliClient("9.0", client90, Optional.empty()).build();

        assertTheVersionText(refusal(server, Optional.of(V8_1_17)), "8.1.17", "8.1");
    }

    @Test
    void withoutCliClientsBelowNineThe080TextIsKept() {
        assertThe080Text(refusal(node().build(), Optional.of(V8_1_17)));
        assertThe080Text(refusal(node().versionLine(new VersionSetting.Pinned(V6_1_0_89)).build(),
            Optional.of(V6_1_0_89)));
        assertThe080Text(refusal(TestNodeConfig.node().baseUrl(BASE_URL).build(),
            Optional.empty()));
    }

    @Test
    void anUnknownVersionOrALineWithoutMinorKeepsThe080Text() throws IOException {
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));

        assertThe080Text(refusal(node().cliClient("9.0", client90, Optional.empty()).build(),
            Optional.empty()));
        assertThe080Text(refusal(node().versionLine(VersionSetting.V9_X).build(),
            Optional.empty()));
    }

    @Test
    void aLineWithoutMinorKeepsThe080TextEvenWithAnObservedVersion() throws IOException {
        // feature 017 (research D-3, FR-011): V9_X never chooses an entry, so none is named
        Path client90 = Files.createDirectories(root.resolve("client-9.0"));

        assertThe080Text(refusal(node().versionLine(VersionSetting.V9_X)
            .cliClient("9.0", client90, Optional.empty()).build(), Optional.of(V9_1_3)));
        assertThe080Text(refusal(node().versionLine(VersionSetting.V9_X).build(),
            Optional.of(V9_1_3)));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the POSIX script is installed")
    void theNoJdkHintNamesTheJdkOfEveryDeclaredVersion() throws IOException {
        Path client = Files.createDirectories(root.resolve("client"));
        FakeStartCli.install(client);

        ToolError error = refusal(TestNodeConfig.node().baseUrl(BASE_URL).cliHome(client)
            .build(), Optional.of(V8_1_17), Map.of("PATH", Platform.searchPath()));

        assertThat(error.message()).isEqualTo("No JDK for StartCLI on dev/node1");
        assertThat(error.nextStep()).isEqualTo("Set cli.javaHome to the JDK the INUBIT client"
            + " needs (8.1: JDK 17, 9.0: JDK 21)");
    }
}
