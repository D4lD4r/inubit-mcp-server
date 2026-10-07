package de.dadecker.inubit.mcp.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T001 (feature 004, research D-22, D-24, D-25): the neutralized import, tag and finger
 * recordings and the synthetic failure cases are complete and consistent, so that the later
 * tests can rely on their shape (documented in {@code fixtures/v8_1/cli/README.md}).
 */
class ImportFixturesTest {

    private static final Pattern PROTOCOL_ROW = Pattern.compile(
        "^INFORMATION (Module|Diagram) \\[([^\\]]+)\\] was (created|modified)\\.\\s+(\\S+)\\s+(\\S+)\\s*$");
    private static final Pattern TOTAL = Pattern.compile("^Total: (\\d+)\\s*$");

    @ParameterizedTest
    @ValueSource(strings = {"import_created", "import_modified", "import_created_and_modified",
        "import_module_only", "import_workflow_only", "import_nok", "import_protocol_mismatch",
        "import_timeout", "tag_ok", "tag_delete_ok", "finger_user", "finger_not_registered"})
    void everyCaseHasStdoutStderrAndExitCode(String fixtureCase) {
        for (String suffix : List.of(".stdout", ".stderr", ".exit")) {
            assertThat(resource("/fixtures/v8_1/cli/" + fixtureCase + suffix))
                .as(fixtureCase + suffix).isNotNull();
        }
        assertThat(FakeProcessLauncher.fixtureText(fixtureCase + ".stderr"))
            .isEqualTo("Picked up JAVA_TOOL_OPTIONS: -Duser.language=en -Duser.country=US\n");
        assertThat(FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"))
            .startsWith("JAVA_HOME is set\nPassword: \n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"import_created", "import_modified", "import_created_and_modified",
        "import_module_only", "import_workflow_only", "import_protocol_mismatch"})
    void protocolsAreTablesWhoseTotalCountsTheirRows(String fixtureCase) {
        String stdout = FakeProcessLauncher.fixtureText(fixtureCase + ".stdout");

        List<String> rows = rows(stdout);
        Matcher total = TOTAL.matcher(stdout.lines()
            .filter(line -> line.startsWith("Total: ")).findFirst().orElse(""));

        assertThat(stdout.lines()).anyMatch(line -> line.startsWith(
            "TYPE        DESCRIPTION "));
        assertThat(total.matches()).as("Total line of " + fixtureCase).isTrue();
        assertThat(rows).hasSize(Integer.parseInt(total.group(1)));
        assertThat(exit(fixtureCase)).isZero();
        assertThat(stdout).doesNotContain("-NOK");
        assertThat(rows).allSatisfy(row -> assertThat(row).endsWith(" jdoe"));
        // review M2: INUBIT's CRLF survives git (.gitattributes -text)
        assertThat(stdout).contains("GROUP/USER\r\n").contains("\r\nTotal: ");
    }

    @Test
    void theMismatchProtocolHasExactlyOneArtifactMoreThanTheModifiedOne() {
        List<String> modified = rows(FakeProcessLauncher.fixtureText("import_modified.stdout"));
        List<String> mismatch = rows(FakeProcessLauncher.fixtureText(
            "import_protocol_mismatch.stdout"));

        assertThat(mismatch).hasSize(modified.size() + 1);
        List<String> extra = new ArrayList<>(mismatch);
        extra.removeAll(modified);
        assertThat(extra).hasSize(1);
    }

    @Test
    void theCreatedAndModifiedCasesUseTheirVerbs() {
        assertThat(rows(FakeProcessLauncher.fixtureText("import_created.stdout")))
            .allMatch(row -> row.contains(" was created."));
        assertThat(rows(FakeProcessLauncher.fixtureText("import_modified.stdout")))
            .allMatch(row -> row.contains(" was modified."));
        assertThat(rows(FakeProcessLauncher.fixtureText("import_created_and_modified.stdout")))
            .anyMatch(row -> row.contains(" was created."))
            .anyMatch(row -> row.contains(" was modified."));
        assertThat(rows(FakeProcessLauncher.fixtureText("import_module_only.stdout")))
            .singleElement().asString().startsWith("INFORMATION Module [");
        assertThat(rows(FakeProcessLauncher.fixtureText("import_workflow_only.stdout")))
            .singleElement().asString().startsWith("INFORMATION Diagram [");
    }

    @Test
    void theNokCaseHasAnNokLineAndExitCodeOne() {
        assertThat(FakeProcessLauncher.fixtureText("import_nok.stdout").lines())
            .anyMatch(line -> line.matches("^\\d+-NOK: .+"));
        assertThat(exit("import_nok")).isEqualTo(1);
    }

    @Test
    void theTimeoutCaseStopsBeforeTheProtocol() {
        assertThat(FakeProcessLauncher.fixtureText("import_timeout.stdout"))
            .doesNotContain("TYPE ").doesNotContain("Total:");
        assertThat(exit("import_timeout")).isEqualTo(143);
    }

    @Test
    void tagRunsPrintNoResultLine() {
        for (String fixtureCase : List.of("tag_ok", "tag_delete_ok")) {
            assertThat(exit(fixtureCase)).isZero();
            assertThat(FakeProcessLauncher.fixtureText(fixtureCase + ".stdout"))
                .doesNotContain("ERROR").doesNotContain("-OK");
        }
    }

    @Test
    void fingerKnowsUsersButNotUserGroups() {
        assertThat(FakeProcessLauncher.fixtureText("finger_user.stdout"))
            .contains("Login: jdoe").contains("Email: jdoe@example.test");
        assertThat(exit("finger_user")).isZero();
        assertThat(FakeProcessLauncher.fixtureText("finger_not_registered.stdout"))
            .contains("The user or group \"OWNERS\" is not registered in the INUBIT Process"
                + " Engine.");
        assertThat(exit("finger_not_registered")).isEqualTo(1);
    }

    @Test
    void theWorkflowOnlyArchiveHasNoModuleAndNoRepository() throws IOException {
        List<String> entries = new ArrayList<>();
        String index = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(
            FakeProcessLauncher.fixture("import_workflow_only.zip")))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                if (entry.getName().equals("module/module.xml")) {
                    index = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        assertThat(entries).containsExactly("archive.properties", "workflow/workflow.xml",
            "module/module.xml");
        assertThat(index).contains("<Modules>").doesNotContain("<Module ");
    }

    @Test
    void theCommentProbeHistoryShowsWhichShapeInubitKeeps() {
        String history = FakeProcessLauncher.fixtureText("import_comment_history.xml");

        assertThat(history).contains(
            "<CheckinComment>DefaultCommitCommentImport###REASON-PROBE three###@@@Deploying User:")
            .contains("<CheckinComment>DefaultCommitCommentImport###</CheckinComment>");
    }

    private static List<String> rows(String stdout) {
        return stdout.lines().map(String::stripTrailing)
            .filter(line -> PROTOCOL_ROW.matcher(line).matches()).toList();
    }

    private static int exit(String fixtureCase) {
        return Integer.parseInt(FakeProcessLauncher.fixtureText(fixtureCase + ".exit").strip());
    }

    private static InputStream resource(String path) {
        return ImportFixturesTest.class.getResourceAsStream(path);
    }
}
