package de.dadecker.inubit.mcp.adapter.cli.v61;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.CliResources;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.StartCliEnvironment;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestCliRunners;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Feature 013 T028 (FR-012): the 6.1 connection sequence of {@link V61ConnectionTest} on a
 * simulated Windows host — every inherited test, plus what is specific to the Windows launch: the
 * session goes through the command interpreter to {@code bin\startcli.bat} with the password and
 * the session commands on standard input, a trust store passed through {@code JAVA_TOOL_OPTIONS}
 * is appended to the Windows settings, CRLF output reads as on macOS, and a username or password
 * the interpreter would change is refused before any launch or contact.
 */
@DisplayName("V61ConnectionTest (Windows)")
class V61ConnectionWindowsTest extends V61ConnectionTest {

    private static final String SYSTEM_DIRECTORY = TestCliRunners.SYSTEM_DIRECTORY;

    @Override
    CliRunner runner(ProcessLauncher launcher) {
        return TestCliRunners.simulatedWindows(launcher, parentEnvironment,
            new ProcessContainment.Bound(), new CliResources("acme", PrivateFiles.forHost()));
    }

    @Test
    void theSessionStartsTheBatchFileThroughTheInterpreterWithTheSecretsOnStandardInput() {
        Launchers launchers = Launchers.withOptions();

        CliResult result = connection(node().build(), launchers).session(COMMANDS, BOUND);

        assertThat(result.exitCode()).isZero();
        ProcessLauncher.LaunchSpec spec = launchers.session.last().spec();
        String url = "https://localhost:" + inubit.port() + EffectiveNodeConfig.DEFAULT_CLI_PATH;
        assertThat(spec.command()).startsWith(SYSTEM_DIRECTORY + "\\cmd.exe", "/d", "/v:off",
            "/s", "/c").hasSize(6);
        assertThat(spec.command().get(5))
            .startsWith("\"\"" + cliHome.resolve("bin").resolve("startcli.bat") + "\" \"-u\"")
            .endsWith("\"" + url + "\"\"")
            .doesNotContain(PASSWORD, "uname");
        assertThat(spec.startCliArguments()).startsWith("-u", "jdoe").endsWith(url)
            .doesNotContain("--execCommand");
        assertThat(spec.workingDirectory()).isEqualTo(cliHome);
        assertThat(new String(launchers.session.last().stdinBytes(), StandardCharsets.UTF_8))
            .isEqualTo(PASSWORD + "\nuname\nuptime\nexit\n");
        assertThat(spec.environment())
            .containsEntry("PATH", javaHome + "\\bin;" + SYSTEM_DIRECTORY)
            .containsEntry("ComSpec", SYSTEM_DIRECTORY + "\\cmd.exe");
        assertThat(spec.environment().values()).noneMatch(value -> value.contains(PASSWORD));
    }

    @Test
    void theClientProbeRunsTheBatchFileWithHelpOnly() {
        Launchers launchers = Launchers.withOptions();

        connection(node().build(), launchers).session(COMMANDS, BOUND);

        ProcessLauncher.LaunchSpec probe = launchers.help.last().spec();
        assertThat(probe.command()).startsWith(SYSTEM_DIRECTORY + "\\cmd.exe");
        assertThat(probe.startCliArguments()).containsExactly("-h");
        assertThat(launchers.help.last().stdinBytes()).isEmpty();
    }

    @Test
    void aTrustStoreWithoutClientOptionIsAppendedToTheWindowsJavaSettings() throws IOException,
        GeneralSecurityException {
        Path jks = javaHome.resolve("truststore.jks");
        KeyStore store = KeyStore.getInstance("JKS");
        store.load(null, null);
        store.setCertificateEntry("localhost", CERTS.localhostCertificate());
        try (OutputStream out = Files.newOutputStream(jks)) {
            store.store(out, new char[0]);
        }
        Launchers launchers = Launchers.sixOne();

        connection(node().trustStore(jks).build(), launchers).session(COMMANDS, BOUND);

        assertThat(launchers.session.last().spec().environment()).containsEntry(
            "JAVA_TOOL_OPTIONS", StartCliEnvironment.WINDOWS_JAVA_TOOL_OPTIONS
                + " -Djavax.net.ssl.trustStore=" + jks + " -Djavax.net.ssl.trustStoreType=JKS");
        assertThat(launchers.sessionCommand()).doesNotContain("--trustStoreFilePath");
    }

    @Test
    void crlfOutputOfTheSessionReadsAsOnMacOs() {
        Launchers launchers = new Launchers(FakeProcessLauncher.of(HELP_WITH_OPTIONS, "", 0),
            FakeProcessLauncher.of("Password: \r\ncli> Linux\r\ncli> 10:15 up 3 days\r\n", "", 0));

        CliResult result = connection(node().build(), launchers).session(COMMANDS, BOUND);

        assertThat(result.stdout())
            .isEqualTo("Password: \ncli> Linux\ncli> 10:15 up 3 days\n");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "j%doe|S3cr3t-v61-pw|username|percent sign",
        "`j|doe`|S3cr3t-v61-pw|username|vertical bar",
        "jd\u00f6e|S3cr3t-v61-pw|username|non-ASCII character",
        "jdoe|S3cr3t-p\u00e4ss|password|non-ASCII"})
    void aRefusedUsernameOrPasswordIsAuthFailedBeforeAnyLaunchOrContact(String username,
        String password, String entry, String characterClass) {
        Launchers launchers = Launchers.withOptions();

        ToolError error = errorOf(() -> connection(node().build(),
            credentials(username, password), launchers).session(COMMANDS, BOUND));

        assertThat(error.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error.node()).contains(ID);
        assertThat(error.message()).contains("the " + entry + " in", characterClass);
        assertThat(error.toString()).doesNotContain(username, password);
        assertThat(launchers.order).as("no probe, no session").isEmpty();
        assertThat(inubit.connections()).as("no handshake").isZero();
        assertThat(guard.confirmed()).isFalse();
    }
}
