package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T022: allow-listed command tokens and the R-11 quoting rule for {@code --execCommand}. */
class CliCommandTest {

    private static void assertInvalidInput(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ToolErrorException.class,
            e -> assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void processErrorStartAndKillTakeADecimalProcessId() {
        assertThat(CliCommand.processErrorStart("110190387").commandLine())
            .isEqualTo("processErrorStart 110190387");
        assertThat(CliCommand.kill("999999999").commandLine()).isEqualTo("kill 999999999");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "12 3", "1;rm", "-1", "12345678901234567890",
        "0f8fad5b-d9cb-469f-a165-70867728950e", "0", "0110190387"})
    void processIdsOtherThanUpTo19DigitsAreRejected(String pid) {
        assertInvalidInput(() -> CliCommand.processErrorStart(pid));
        assertInvalidInput(() -> CliCommand.kill(pid));
    }

    @ParameterizedTest
    @ValueSource(strings = {"noSuchCommand", "ps; kill 1", "uptime", "", "Kill"})
    void onlyAllowListedCommandsAreAccepted(String name) {
        assertInvalidInput(() -> CliCommand.command(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--rm", "-p", "--password", "--exportFile;x", "exportFile", ""})
    void onlyAllowListedOptionsAreAccepted(String option) {
        assertInvalidInput(() -> CliCommand.command("export").flag(option));
        assertInvalidInput(() -> CliCommand.command("export").quoted(option, "OWNERS"));
    }

    @Test
    void valuesAreWrappedInSingleQuotes() {
        CliCommand command = CliCommand.command("export")
            .quoted("--exportWorkflowUser", "OWNERS")
            .quoted("--exportWorkflowType", "technical")
            .quoted("--exportWorkflowGroup", "GRP-01 Group_1.0")
            .flag("--includeHistory")
            .path("--exportFile", Path.of("/tmp/inubit-mcp-1/history.zip"))
            .build();

        assertThat(command.commandLine()).isEqualTo("export --exportWorkflowUser 'OWNERS'"
            + " --exportWorkflowType 'technical' --exportWorkflowGroup 'GRP-01 Group_1.0'"
            + " --includeHistory --exportFile '/tmp/inubit-mcp-1/history.zip'");
        assertThat(command.name()).isEqualTo("export");
        assertThat(command).hasToString(command.commandLine());
    }

    @Test
    void theLiteralEmptyArgumentIsSupported() {
        CliCommand command = CliCommand.command("export")
            .emptyQuoted("--exportModule")
            .emptyQuoted("--exportModuleGroup")
            .quoted("--exportModuleUser", "OWNERS")
            .path("--exportFile", Path.of("/tmp/x/modules.zip"))
            .build();

        assertThat(command.commandLine()).isEqualTo("export --exportModule ''"
            + " --exportModuleGroup '' --exportModuleUser 'OWNERS'"
            + " --exportFile '/tmp/x/modules.zip'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"it's", "a'b", "''", "x\"y", "a;b", "$(id)", "`id`", "a\nb", "abc\n",
        "a\tb", "-rf", "--exportFile x", "-", " a", "a|b", "a&b", "a*b", "a/b", "ä", "a\\b", ""})
    void valuesFailingTheAllowlistOrContainingAQuoteAreRejectedBeforeLaunch(String value) {
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportWorkflowGroup", value));
    }

    @Test
    void valuesUpTo200CharsAreAccepted() {
        assertThat(CliCommand.command("export").quoted("--exportWorkflowGroup", "a".repeat(200))
            .build().commandLine()).endsWith("'" + "a".repeat(200) + "'");
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportWorkflowGroup", "a".repeat(201)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/it's/x.zip", "/tmp/a;b/x.zip", "/tmp/$(id)/x.zip",
        "relative/x.zip", "/tmp/../etc/x.zip", "/tmp/a/..", "/tmp/./../x.zip"})
    void unsafeOrRelativeExportPathsAreRejected(String path) {
        assertInvalidInput(() -> CliCommand.command("export").path("--exportFile",
            Path.of(path)));
    }

    @Test
    void theErrorNamesTheOptionButNotTheValue() {
        assertThatThrownBy(() -> CliCommand.command("export")
            .quoted("--exportWorkflowGroup", "evil'value"))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> assertThat(e.error()
                .toString()).contains("--exportWorkflowGroup").doesNotContain("evil'value"));
    }

    @Test
    void pathsMayContainDoubleDotsInsideANameButNoDotDotSegment() {
        assertThat(CliCommand.command("export").path("--exportFile", Path.of("/tmp/v1..2/x.zip"))
            .build().commandLine()).isEqualTo("export --exportFile '/tmp/v1..2/x.zip'");
    }

    @Test
    void processCommandsTakeExactlyOneProcessIdAndNoOptions() {
        assertInvalidInput(() -> CliCommand.command("kill").flag("--includeHistory"));
        assertInvalidInput(() -> CliCommand.command("processErrorStart")
            .quoted("--exportWorkflowUser", "OWNERS"));
        assertInvalidInput(() -> CliCommand.command("kill").emptyQuoted("--exportModule"));
        assertInvalidInput(() -> CliCommand.command("processErrorStart")
            .path("--exportFile", Path.of("/tmp/x.zip")));
        assertInvalidInput(() -> CliCommand.command("kill").build());
        assertInvalidInput(() -> CliCommand.command("kill").processId("1").processId("2"));
    }

    @Test
    void exportTakesNoProcessId() {
        assertInvalidInput(() -> CliCommand.command("export").processId("1"));
    }

    // --- feature 004 (T013, research D-8): import and tag -----------------------------------

    @Test
    void importTakesItsOwnOptions() {
        assertThat(CliCommand.command("import").path("--importFile",
            java.nio.file.Path.of("/tmp/x/import.zip")).flag("--importWorkflow")
            .flag("--importWorkflowActive").quoted("--importUser", "jdoe")
            .flag("--returnProtocol").build().commandLine()).isEqualTo("import --importFile"
                + " '/tmp/x/import.zip' --importWorkflow --importWorkflowActive --importUser"
                + " 'jdoe' --returnProtocol");
        assertThat(CliCommand.command("import").flag("--importModule").flag(
            "--importWorkflowInactive").quoted("--importUser", "OWNERS").build()
            .commandLine()).contains("--importModule", "--importUser 'OWNERS'");
        // research D-26: INUBIT refuses --importUserGroup ("Missing user or group!")
        assertInvalidInput(() -> CliCommand.command("import").quoted("--importUserGroup",
            "OWNERS"));
        assertInvalidInput(() -> CliCommand.command("import").quoted("--exportFile", "x"));
        assertInvalidInput(() -> CliCommand.command("import").flag("--importRepositoryPath"));
        assertInvalidInput(() -> CliCommand.command("import").flag("--importMetadata"));
        assertInvalidInput(() -> CliCommand.command("export").flag("--importWorkflow"));
    }

    @Test
    void tagTakesItsOwnOptions() {
        assertThat(CliCommand.command("tag").quoted("--tagMove", "REL-1")
            .quoted("--tagWorkflowGroup", "GRP-01").quoted("--tagWorkflowType", "technical")
            .quoted("--tagUser", "jdoe").build().commandLine()).isEqualTo("tag --tagMove"
                + " 'REL-1' --tagWorkflowGroup 'GRP-01' --tagWorkflowType 'technical' --tagUser"
                + " 'jdoe'");
        // research D-26: a tag is never removed (StartCLI removes it only owner-wide)
        assertInvalidInput(() -> CliCommand.command("tag").quoted("--tagDelete", "REL-1"));
        assertInvalidInput(() -> CliCommand.command("tag").quoted("--tagDiagram", "W"));
        assertInvalidInput(() -> CliCommand.command("tag").flag("--tagRemove"));
        assertInvalidInput(() -> CliCommand.command("tag").quoted("--tagMove", "a'b"));
    }

    // --- feature 005 (T004, research D-1, D-4): release and repository exports, repository import

    @Test
    void theReleaseExportTakesATagAndTheEmptyGroupList() {
        assertThat(CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quoted("--exportWorkflowType", "technical").emptyQuoted("--exportWorkflowGroup")
            .quoted("--exportTag", "TAG-01").path("--exportFile", Path.of("/tmp/x/release.zip"))
            .build().commandLine()).isEqualTo("export --exportWorkflowUser 'jdoe'"
                + " --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportTag"
                + " 'TAG-01' --exportFile '/tmp/x/release.zip'");
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportTag", "a'b"));
        assertInvalidInput(() -> CliCommand.command("export").emptyQuoted("--exportTag"));
        assertInvalidInput(() -> CliCommand.command("import").quoted("--exportTag", "TAG-01"));
    }

    @Test
    void theEmptyGroupListIsRefusedWithoutATag() {
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportWorkflowUser", "jdoe").quoted("--exportWorkflowType", "technical")
            .emptyQuoted("--exportWorkflowGroup").path("--exportFile", Path.of("/tmp/x/a.zip"))
            .build());
        assertInvalidInput(() -> CliCommand.command("export")
            .emptyQuoted("--exportWorkflowGroup").build());
    }

    @Test
    void repositoryPathsAreQuotedAndOnlyForTheRepositoryOptions() {
        assertThat(CliCommand.command("export")
            .repositoryPath("--exportRepositoryPath", "/Root/jdoe/xsd/release.xsl")
            .path("--exportFile", Path.of("/tmp/x/repository.zip")).build().commandLine())
            .isEqualTo("export --exportRepositoryPath '/Root/jdoe/xsd/release.xsl'"
                + " --exportFile '/tmp/x/repository.zip'");
        assertThat(CliCommand.command("import").path("--importFile", Path.of("/tmp/x/r.zip"))
            .repositoryPath("--importRepositoryPath", "/Root/OWNERS").build().commandLine())
            .isEqualTo("import --importFile '/tmp/x/r.zip' --importRepositoryPath '/Root/OWNERS'");
        assertThat(CliCommand.command("export")
            .repositoryPath("--exportRepositoryPath", "/Root/jdoe/My Dir/v1..2.xsd").build()
            .commandLine()).endsWith("'/Root/jdoe/My Dir/v1..2.xsd'");
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportRepositoryPath", "Root"));
        assertInvalidInput(() -> CliCommand.command("import")
            .quoted("--importRepositoryPath", "Root"));
        assertInvalidInput(() -> CliCommand.command("export")
            .repositoryPath("--exportWorkflowGroup", "/Root/jdoe"));
        assertInvalidInput(() -> CliCommand.command("import")
            .repositoryPath("--exportRepositoryPath", "/Root/jdoe"));
        assertInvalidInput(() -> CliCommand.command("tag")
            .repositoryPath("--tagRepositoryPath", "/Root/jdoe"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/Root", "/Root/", "Root/jdoe", "/root/jdoe", "/Root//jdoe",
        "/Root/jdoe/", "/Root/../etc", "/Root/jdoe/../x", "/Root/./jdoe", "/Root/jdoe/..",
        "/Root/it's", "/Root/a;b", "/Root/$(id)", "/Root/-rf", "/Root/ a", "/Root/a\nb",
        "/Root/a*b", "/Root/ä", "/Other/jdoe"})
    void repositoryPathsOutsideTheRuleAreRejected(String path) {
        assertInvalidInput(() -> CliCommand.command("export")
            .repositoryPath("--exportRepositoryPath", path));
        assertInvalidInput(() -> CliCommand.command("import")
            .repositoryPath("--importRepositoryPath", path));
    }

    @Test
    void repositoryPathSegmentsUpTo200CharsAreAccepted() {
        assertThat(CliCommand.command("export").repositoryPath("--exportRepositoryPath",
            "/Root/" + "a".repeat(200)).build().commandLine()).endsWith("a'");
        assertInvalidInput(() -> CliCommand.command("export").repositoryPath(
            "--exportRepositoryPath", "/Root/" + "a".repeat(201)));
        assertInvalidInput(() -> CliCommand.command("export").repositoryPath(
            "--exportRepositoryPath", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--exportTag", "--exportWorkflowGroup", "--exportRepositoryPath",
        "--exportWorkflowUser", "--exportFile", "--exportModule"})
    void optionsThatTakeAValueAreNoFlags(String option) {
        // stage 1 review #2: flag("--exportTag") would bypass the tag rule
        assertInvalidInput(() -> CliCommand.command("export").flag(option));
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser",
            "jdoe").emptyQuoted("--exportWorkflowGroup").flag(option).build());
    }

    @Test
    void flagsTakeNoValueAndRepositoryImportsNoFlag() {
        assertInvalidInput(() -> CliCommand.command("import").flag("--importRepositoryPath"));
        assertInvalidInput(() -> CliCommand.command("import").flag("--importFile"));
        assertInvalidInput(() -> CliCommand.command("export").quoted("--includeHistory", "x"));
        assertInvalidInput(() -> CliCommand.command("import").quoted("--returnProtocol", "x"));
        assertInvalidInput(() -> CliCommand.command("export").emptyQuoted("--includeHistory"));
    }
}

