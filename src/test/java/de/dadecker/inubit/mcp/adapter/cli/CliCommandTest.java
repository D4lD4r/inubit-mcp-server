package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CliPaths;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.testing.Platform;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T022: allow-listed command tokens and the R-11 quoting rule for {@code --execCommand}. */
class CliCommandTest {

    private static final boolean WINDOWS = Platform.windows();
    /** The drive of {@link #file} on Windows, where a path inside the command has one. */
    private static final String DRIVE = WINDOWS ? "C:" : "";

    /** The fictitious {@code posixPath} as an absolute path of the host. */
    private static Path file(String posixPath) {
        return Platform.absolutePath(posixPath);
    }

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
            .path("--exportFile", file("/tmp/inubit-mcp-1/history.zip"), WINDOWS)
            .build();

        assertThat(command.commandLine()).isEqualTo("export --exportWorkflowUser 'OWNERS'"
            + " --exportWorkflowType 'technical' --exportWorkflowGroup 'GRP-01 Group_1.0'"
            + " --includeHistory --exportFile '" + DRIVE + "/tmp/inubit-mcp-1/history.zip'");
        assertThat(command.name()).isEqualTo("export");
        assertThat(command).hasToString(command.commandLine());
    }

    @Test
    void theLiteralEmptyArgumentIsSupported() {
        CliCommand command = CliCommand.command("export")
            .emptyQuoted("--exportModule")
            .emptyQuoted("--exportModuleGroup")
            .quoted("--exportModuleUser", "OWNERS")
            .path("--exportFile", file("/tmp/x/modules.zip"), WINDOWS)
            .build();

        assertThat(command.commandLine()).isEqualTo("export --exportModule ''"
            + " --exportModuleGroup '' --exportModuleUser 'OWNERS'"
            + " --exportFile '" + DRIVE + "/tmp/x/modules.zip'");
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
            file(path), WINDOWS));
    }

    // --- T018 (feature 013, research R-3): paths inside the command on Windows -------------

    @Test
    void onWindowsAPathIsRenderedWithForwardSlashes() {
        assertThat(CliCommand.command("export").path("--exportFile",
            Path.of("C:\\Users\\svc\\AppData\\Local\\Temp\\inubit-mcp-1\\history.zip"), true)
            .build().commandLine())
            .isEqualTo("export --exportFile"
                + " 'C:/Users/svc/AppData/Local/Temp/inubit-mcp-1/history.zip'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:\\Users\\RUNNER~1\\x.zip", "\\\\files.example.test\\share\\x.zip",
        "C:\\a\\..\\x.zip", "C:x.zip", "C:\\it's\\x.zip"})
    void onWindowsAPathOutsideTheWindowsRuleIsRefusedNamingThatRule(String path) {
        assertThatThrownBy(() -> CliCommand.command("export").path("--exportFile", Path.of(path),
            true)).isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
                assertThat(e.error().likelyCause()).contains("local drive path",
                    CliPaths.WINDOWS_PATH_VALUE.pattern());
            });
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
        assertThat(CliCommand.command("export")
            .path("--exportFile", file("/tmp/v1..2/x.zip"), WINDOWS).build().commandLine())
            .isEqualTo("export --exportFile '" + DRIVE + "/tmp/v1..2/x.zip'");
    }

    @Test
    void processCommandsTakeExactlyOneProcessIdAndNoOptions() {
        assertInvalidInput(() -> CliCommand.command("kill").flag("--includeHistory"));
        assertInvalidInput(() -> CliCommand.command("processErrorStart")
            .quoted("--exportWorkflowUser", "OWNERS"));
        assertInvalidInput(() -> CliCommand.command("kill").emptyQuoted("--exportModule"));
        assertInvalidInput(() -> CliCommand.command("processErrorStart")
            .path("--exportFile", file("/tmp/x.zip"), WINDOWS));
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
            file("/tmp/x/import.zip"), WINDOWS).flag("--importWorkflow")
            .flag("--importWorkflowActive").quoted("--importUser", "jdoe")
            .flag("--returnProtocol").build().commandLine()).isEqualTo("import --importFile"
                + " '" + DRIVE + "/tmp/x/import.zip' --importWorkflow --importWorkflowActive"
                + " --importUser"
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
            .quoted("--exportTag", "TAG-01")
            .path("--exportFile", file("/tmp/x/release.zip"), WINDOWS)
            .build().commandLine()).isEqualTo("export --exportWorkflowUser 'jdoe'"
                + " --exportWorkflowType 'technical' --exportWorkflowGroup '' --exportTag"
                + " 'TAG-01' --exportFile '" + DRIVE + "/tmp/x/release.zip'");
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportTag", "a'b"));
        assertInvalidInput(() -> CliCommand.command("export").emptyQuoted("--exportTag"));
        assertInvalidInput(() -> CliCommand.command("import").quoted("--exportTag", "TAG-01"));
    }

    @Test
    void theEmptyGroupListIsRefusedWithoutATag() {
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportWorkflowUser", "jdoe").quoted("--exportWorkflowType", "technical")
            .emptyQuoted("--exportWorkflowGroup")
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS)
            .build());
        assertInvalidInput(() -> CliCommand.command("export")
            .emptyQuoted("--exportWorkflowGroup").build());
    }

    @Test
    void repositoryPathsAreQuotedAndOnlyForTheRepositoryOptions() {
        assertThat(CliCommand.command("export")
            .repositoryPath("--exportRepositoryPath", "/Root/jdoe/xsd/release.xsl")
            .path("--exportFile", file("/tmp/x/repository.zip"), WINDOWS).build().commandLine())
            .isEqualTo("export --exportRepositoryPath '/Root/jdoe/xsd/release.xsl'"
                + " --exportFile '" + DRIVE + "/tmp/x/repository.zip'");
        assertThat(CliCommand.command("import")
            .path("--importFile", file("/tmp/x/r.zip"), WINDOWS)
            .repositoryPath("--importRepositoryPath", "/Root/OWNERS").build().commandLine())
            .isEqualTo("import --importFile '" + DRIVE + "/tmp/x/r.zip' --importRepositoryPath"
                + " '/Root/OWNERS'");
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

    // --- feature 021 (T022, FR-005, research D-1): the trace download only -------------------

    @Test
    void traceDownloadIsTheOnlyTraceCommand() {
        assertThat(CliCommand.traceDownload(file("/tmp/inubit-mcp-1/trace.log"), WINDOWS)
            .commandLine())
            .isEqualTo("trace --download '" + DRIVE + "/tmp/inubit-mcp-1/trace.log'");
        assertThat(CliCommand.traceDownload(file("/tmp/inubit-mcp-1/trace.log"), WINDOWS).name())
            .isEqualTo("trace");
        assertThat(CliCommand.command("trace")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS).build().commandLine())
            .isEqualTo("trace --download '" + DRIVE + "/tmp/x/trace.log'");
    }

    @Test
    void onWindowsTheTraceFileIsRenderedWithForwardSlashes() {
        assertThat(CliCommand.traceDownload(
            Path.of("C:\\Users\\svc\\AppData\\Local\\Temp\\inubit-mcp-1\\trace.log"), true)
            .commandLine())
            .isEqualTo("trace --download 'C:/Users/svc/AppData/Local/Temp/inubit-mcp-1/trace.log'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "off", "-l", "-nl", "-p", "-r", "-d", "--level", "--exportFile",
        "--download;x", ""})
    void traceRefusesEveryOtherOptionOrFlag(String option) {
        assertInvalidInput(() -> CliCommand.command("trace").flag(option));
        assertInvalidInput(() -> CliCommand.command("trace").quoted(option, "info"));
        assertInvalidInput(() -> CliCommand.command("trace").emptyQuoted(option));
        assertInvalidInput(() -> CliCommand.command("trace")
            .path(option, file("/tmp/x/trace.log"), WINDOWS));
    }

    @Test
    void downloadTakesAFilePathOnly() {
        assertInvalidInput(() -> CliCommand.command("trace").flag("--download"));
        assertInvalidInput(() -> CliCommand.command("trace").quoted("--download", "trace.log"));
        assertInvalidInput(() -> CliCommand.command("trace").emptyQuoted("--download"));
        assertInvalidInput(() -> CliCommand.command("trace")
            .repositoryPath("--download", "/Root/trace.log"));
        assertInvalidInput(() -> CliCommand.traceDownload(file("/tmp/it's/trace.log"), WINDOWS));
        assertInvalidInput(() -> CliCommand.traceDownload(file("/tmp/../etc/trace.log"),
            WINDOWS));
        assertInvalidInput(() -> CliCommand.traceDownload(Path.of("relative/trace.log"),
            WINDOWS));
        assertInvalidInput(() -> CliCommand.traceDownload(Path.of("C:x.log"), true));
    }

    @Test
    void traceNeedsExactlyOneDownloadAndNothingElse() {
        // a missing --download would switch or show the trace state (SC-005)
        assertInvalidInput(() -> CliCommand.command("trace").build());
        assertInvalidInput(() -> CliCommand.command("trace").processId("1"));
        assertInvalidInput(() -> CliCommand.command("trace")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS).processId("1"));
        assertInvalidInput(() -> CliCommand.command("trace")
            .path("--download", file("/tmp/x/a.log"), WINDOWS)
            .path("--download", file("/tmp/x/b.log"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("trace")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS).flag("-l"));
        assertInvalidInput(() -> CliCommand.command("trace")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS).flag("on"));
    }

    @Test
    void otherCommandsDoNotTakeDownload() {
        assertInvalidInput(() -> CliCommand.command("export")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS));
        assertInvalidInput(() -> CliCommand.command("import")
            .path("--download", file("/tmp/x/trace.log"), WINDOWS));
    }

    // --- feature 022 (T041, research D-21): the read forms recorded in T039 -------------------

    @Test
    void libraryListIsTheRecordedFormPerType() {
        assertThat(CliCommand.libraryList("driver").commandLine())
            .isEqualTo("library --list --type driver");
        assertThat(CliCommand.libraryList("plugin").commandLine())
            .isEqualTo("library --list --type plugin");
        assertThat(CliCommand.libraryList("plugin").name()).isEqualTo("library");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Driver", "driver ", "jar", "driver;kill 1", "'driver'"})
    void libraryListRefusesOtherTypes(String type) {
        assertInvalidInput(() -> CliCommand.libraryList(type));
    }

    @Test
    void libraryIsOnlyTheListFormAndNeverDeletes() {
        assertInvalidInput(() -> CliCommand.libraryList(null));
        assertInvalidInput(() -> CliCommand.command("library").build());
        assertInvalidInput(() -> CliCommand.command("library").flag("--delete"));
        assertInvalidInput(() -> CliCommand.command("library").flag("--deleteAll"));
        assertInvalidInput(() -> CliCommand.command("library").quoted("--delete", "x.jar"));
        assertInvalidInput(() -> CliCommand.command("library").quoted("--upload", "x.jar"));
        assertInvalidInput(() -> CliCommand.command("library").quoted("--type", "driver")
            .build());
    }

    @Test
    void theOwnerExportWithoutGroupsIsTheRecordedForm() {
        assertThat(CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quoted("--exportWorkflowType", "technical").emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", List.of("Group A", "g-2", "g_3.x"))
            .path("--exportFile", file("/tmp/x/owner.zip"), WINDOWS)
            .build().commandLine()).isEqualTo("export --exportWorkflowUser 'jdoe'"
                + " --exportWorkflowType 'technical' --exportWorkflowGroup ''"
                + " --exportWorkflowGroupExclude 'Group A,g-2,g_3.x'"
                + " --exportFile '" + DRIVE + "/tmp/x/owner.zip'");
    }

    @Test
    void theGroupExcludeNeedsTheTypeAndTheEmptyGroupList() {
        // recorded in T039: without them INUBIT answers "2-NOK: Missing export option."
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quoted("--exportWorkflowType", "technical")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quoted("--exportWorkflowType", "technical").quoted("--exportWorkflowGroup", "g0")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
    }

    /** The recorded owner export without groups up to the exclude list, without the file. */
    private static CliCommand.Builder ownerWithoutGroups() {
        return CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .quoted("--exportWorkflowType", "technical").emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "all", "Technical", "business", "technical "})
    void theGroupExcludeTakesTheTechnicalTypeOnly(String type) {
        // owner decision 2026-11-14 (research D-21): technical workflows only
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .quoted("--exportWorkflowType", type)
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
    }

    @Test
    void theGroupExcludeRefusesTheEmptyType() {
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportWorkflowUser", "jdoe")
            .emptyQuoted("--exportWorkflowType").emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
    }

    @Test
    void theGroupExcludeTakesNoOtherExportOption() {
        assertThat(ownerWithoutGroups().path("--exportFile", file("/tmp/x/a.zip"), WINDOWS)
            .build().name()).isEqualTo("export");
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportTag", "TAG-01").build());
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportModule", "m1").build());
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportModuleGroup", "mg").build());
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportModuleUser", "jdoe")
            .build());
        assertInvalidInput(() -> ownerWithoutGroups().flag("--includeHistory").build());
        assertInvalidInput(() -> ownerWithoutGroups()
            .repositoryPath("--exportRepositoryPath", "/Root/jdoe").build());
        assertInvalidInput(() -> ownerWithoutGroups().flag("--exportMetadata").build());
    }

    @Test
    void theGroupExcludeTakesEachOptionOnce() {
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportWorkflowType", "business")
            .build());
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportWorkflowGroup", "g0")
            .build());
        assertInvalidInput(() -> ownerWithoutGroups().emptyQuoted("--exportWorkflowGroup")
            .build());
        assertInvalidInput(() -> ownerWithoutGroups()
            .quotedList("--exportWorkflowGroupExclude", List.of("g2")).build());
        assertInvalidInput(() -> ownerWithoutGroups().quoted("--exportWorkflowUser", "other")
            .build());
        assertInvalidInput(() -> ownerWithoutGroups()
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS)
            .path("--exportFile", file("/tmp/x/b.zip"), WINDOWS).build());
    }

    @Test
    void theGroupExcludeTakesOneToTwentyGroupsPerValue() {
        List<String> twenty = IntStream.rangeClosed(1, 20).mapToObj(i -> "g" + i).toList();
        assertThat(CliCommand.command("export").quoted("--exportWorkflowType", "technical")
            .emptyQuoted("--exportWorkflowGroup")
            .quotedList("--exportWorkflowGroupExclude", twenty).build().commandLine())
            .endsWith(" --exportWorkflowGroupExclude '" + String.join(",", twenty) + "'");
        List<String> twentyOne = IntStream.rangeClosed(1, 21).mapToObj(i -> "g" + i).toList();
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportWorkflowGroupExclude", twentyOne));
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportWorkflowGroupExclude", List.of()));
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportWorkflowGroupExclude", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a,b", "it's", "-g", " g", "a;b", "$(id)", "a\nb"})
    void listValuesFailingTheValueRuleAreRejected(String value) {
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1", value)));
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportMetadataNames", List.of(value)));
    }

    @Test
    void listOptionsTakeAListOnlyAndOtherOptionsNoList() {
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportWorkflowGroupExclude", "g1"));
        assertInvalidInput(() -> CliCommand.command("export")
            .emptyQuoted("--exportWorkflowGroupExclude"));
        assertInvalidInput(() -> CliCommand.command("export").flag("--exportMetadataNames"));
        assertInvalidInput(() -> CliCommand.command("export")
            .quoted("--exportMetadataNames", "Example1"));
        assertInvalidInput(() -> CliCommand.command("export")
            .path("--exportMetadataNames", file("/tmp/x/a.zip"), WINDOWS));
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportWorkflowGroup", List.of("g1")));
        assertInvalidInput(() -> CliCommand.command("import")
            .quotedList("--exportWorkflowGroupExclude", List.of("g1")));
    }

    @Test
    void theMetadataExportIsTheRecordedFormWithOptionalNames() {
        assertThat(CliCommand.command("export").flag("--exportMetadata")
            .path("--exportFile", file("/tmp/x/metadata.zip"), WINDOWS).build().commandLine())
            .isEqualTo("export --exportMetadata --exportFile '" + DRIVE + "/tmp/x/metadata.zip'");
        assertThat(CliCommand.command("export").flag("--exportMetadata")
            .quotedList("--exportMetadataNames", List.of("Example1", "Example2"))
            .path("--exportFile", file("/tmp/x/metadata.zip"), WINDOWS).build().commandLine())
            .isEqualTo("export --exportMetadata --exportMetadataNames 'Example1,Example2'"
                + " --exportFile '" + DRIVE + "/tmp/x/metadata.zip'");
        assertInvalidInput(() -> CliCommand.command("export").quoted("--exportMetadata", "x"));
    }

    @Test
    void theMetadataNamesTakeOneToFiftyNames() {
        List<String> fifty = IntStream.rangeClosed(1, 50).mapToObj(i -> "n" + i).toList();
        assertThat(CliCommand.command("export").flag("--exportMetadata")
            .quotedList("--exportMetadataNames", fifty).build().commandLine())
            .endsWith("'" + String.join(",", fifty) + "'");
        List<String> fiftyOne = IntStream.rangeClosed(1, 51).mapToObj(i -> "n" + i).toList();
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportMetadataNames", fiftyOne));
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportMetadataNames", List.of()));
    }

    @Test
    void theMetadataExportTakesNoWorkflowOptionsAndNamesNeedIt() {
        assertInvalidInput(() -> CliCommand.command("export")
            .quotedList("--exportMetadataNames", List.of("Example1"))
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("export").flag("--exportMetadata")
            .quoted("--exportWorkflowUser", "jdoe")
            .path("--exportFile", file("/tmp/x/a.zip"), WINDOWS).build());
        assertInvalidInput(() -> CliCommand.command("export").flag("--exportMetadata")
            .flag("--includeHistory").build());
        assertInvalidInput(() -> CliCommand.command("export").flag("--exportMetadata")
            .quoted("--exportModule", "m1").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ps", "fetchXSLT1.0", "fetchXSLT", "library --list"})
    void unrecordedReadCommandsStayRefused(String name) {
        // ps -u: the user column comes from REST (D-21); fetchXSLT1.0: not found on 8.1.17 (L-7)
        assertInvalidInput(() -> CliCommand.command(name));
    }
}

