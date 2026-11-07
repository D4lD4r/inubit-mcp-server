package de.dadecker.inubit.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T029 (feature 013, FR-016): no document says any more that StartCLI-backed tools are not
 * supported on Windows; README and docs/setup.md describe the Windows client setup and its rules
 * (FR-002, FR-003, FR-007, FR-009, FR-011), docs/tools.md the Windows error texts,
 * docs/live-tests.md the live check on Windows, and the CHANGELOG names the change.
 */
class DocsWindowsStatementsTest {

    private static final Path README = Path.of("README.md");
    private static final Path SETUP = Path.of("docs/setup.md");
    private static final Path TOOLS = Path.of("docs/tools.md");
    private static final Path LIVE = Path.of("docs/live-tests.md");
    private static final Path CHANGELOG = Path.of("CHANGELOG.md");
    private static final List<Path> DOCUMENTS =
        List.of(README, SETUP, TOOLS, LIVE, Path.of("SECURITY.md"));

    /** The text with quote markers and line breaks folded into single spaces. */
    private static String folded(Path file) throws IOException {
        return Files.readString(file).replaceAll("\\n> ?", " ").replaceAll("\\s+", " ");
    }

    /** The folded part of {@code file} from {@code heading} up to the next heading. */
    private static String section(Path file, String heading) throws IOException {
        String text = folded(file);
        assertThat(text).contains(heading);
        String rest = text.substring(text.indexOf(heading) + heading.length());
        int end = rest.indexOf(" #");
        return end < 0 ? rest : rest.substring(0, end);
    }

    @Test
    void noDocumentSaysThatStartCliIsNotSupportedOnWindows() throws IOException {
        for (Path document : DOCUMENTS) {
            assertThat(folded(document)).as(document.toString())
                .doesNotContainIgnoringCase("not supported on Windows")
                .doesNotContain("REST-based tools only", "REST-backed tools only")
                .doesNotContain("no JDK, Windows", "or JDK, Windows", "(or on Windows)");
        }
    }

    @Test
    void theReadmeNamesTheWindowsClientScriptAndItsJavaRuntime() throws IOException {
        String readme = folded(README);
        assertThat(readme).contains("`bin\\startcli.bat`", "Java 17 runtime",
            "[StartCLI on Windows](docs/setup.md#startcli-on-windows)");
    }

    @Test
    void theSetupGuideDescribesTheWindowsClientSetupAndItsRules() throws IOException {
        assertThat(folded(SETUP)).contains("`bin\\startcli.bat` on Windows",
            "[StartCLI on Windows](#startcli-on-windows)");
        String windows = section(SETUP, "### StartCLI on Windows");
        assertThat(windows).contains(
            "`cli.home`", "`bin\\startcli.bat`", "`cli.javaHome`", "Java 17",
            "`\"` `%` `!` `^` `&` `|` `<` `>`", "control characters", "outside ASCII", "`;`",
            "network path", "standard input", "never as a process argument",
            "password of ASCII characters", "`AUTH_FAILED`", "`CLI_UNAVAILABLE`",
            "`INVALID_INPUT`", "6143 characters", "259 characters", "drive letter",
            "`-Djava.io.tmpdir=C:\\mcp-temp`", "8.3", "UNC",
            "NoDefaultCurrentDirectoryInExePath=1", "`JAVA_TOOL_OPTIONS`", "UTF-8",
            "job", "SYSTEM", "access list", "`icacls`", "inherited", "git",
            "INUBIT 6.1", "feature 012", "docs/live-tests.md");
    }

    @Test
    void theToolReferenceListsTheWindowsErrorTexts() throws IOException {
        String windows = section(TOOLS, "### StartCLI on Windows");
        assertThat(windows).contains(
            "contains a character the Windows command interpreter interprets",
            "cli.home is a network path",
            "the StartCLI command line would exceed 6143 characters",
            "The StartCLI command line for",
            "contains a non-ASCII character",
            "StartCLI's last output was its password prompt",
            "the Windows system directory could not be read",
            "StartCLI processes could not be bound to the server",
            "a protected access list without inherited entries");
        assertThat(folded(TOOLS)).contains("[StartCLI on Windows](#startcli-on-windows)");
    }

    @Test
    void theLiveTestGuideHasAWindowsSection() throws IOException {
        String windows = section(LIVE, "## StartCLI live check on Windows (feature 013)");
        assertThat(windows).contains("`InventoryLiveTest`", "`ArtifactExportLiveTest`",
            "`CredentialStoreLiveTest`", "`DevelopmentLiveTest`", "`DeploymentLiveTest`",
            "`INUBIT_LIVE_NODE`", "`INUBIT_MCP_PROFILE`", "PowerShell", "approval",
            "password prompt", "code page", "quickstart.md");
    }

    @Test
    void theChangelogNamesTheChangeUnderRelease080() throws IOException {
        String changelog = Files.readString(CHANGELOG);
        int start = changelog.indexOf("## [0.8.0]");
        assertThat(start).isNotNegative();
        String release = changelog.substring(start, changelog.indexOf("## [", start + 1));
        assertThat(release.replaceAll("\\s+", " ")).contains("Windows",
            "`bin\\startcli.bat`", "StartCLI-backed tools");
    }
}
