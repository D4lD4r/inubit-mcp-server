package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature 019 (T009, FR-002, research D-2, contracts/configuration-delta.md): the knowledge
 * directory never equals, lies inside or contains a directory of the server or of another
 * profile.
 */
class ConfigValidatorKnowledgeTest {

    private static final String GROUPS = """
        groups:
          - name: test
            nodes:
              - name: node1
                baseUrl: https://node1.test.example.test:8443
        """;

    @TempDir
    Path home;

    @TempDir
    Path tempDirectory;

    private Path configDirectory() {
        return home.resolve(".config/inubit-mcp");
    }

    private Path write(Path file, String yaml) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, yaml);
    }

    private Path profileFile(String fileName, String name, String settings) throws IOException {
        return write(configDirectory().resolve(fileName),
            "profile:\n  name: " + name + "\n  description: desc-knowledge-7\n" + settings
                + GROUPS);
    }

    private List<String> errors(Path file) {
        return validate(file).errors();
    }

    private ValidationReport validate(Path file) {
        ConfigLoader loader = new ConfigLoader(Map.of(), home, false);
        LoadedConfig loaded = loader.loadFile(file);
        return new ConfigValidator(Files::exists, Map.of(), false, tempDirectory,
            loader::otherProfiles, workspace -> new WorkspaceDirectory.Usable(false))
            .withServerPaths(home, configDirectory())
            .validate(loaded);
    }

    private static String knowledge(Path directory) {
        return "knowledge:\n  directory: " + directory + "\n";
    }

    private String equal(Path k, String what, Path p) {
        return "The knowledge directory " + k + " is the same directory as the " + what + " " + p
            + "; set knowledge.directory to a folder of its own";
    }

    private String inside(Path k, String what, Path p) {
        return "The knowledge directory " + k + " is inside the " + what + " " + p
            + "; set knowledge.directory to a folder outside it";
    }

    private String contains(Path k, String what, Path p) {
        return "The knowledge directory " + k + " contains the " + what + " " + p
            + "; set knowledge.directory to a folder that does not contain it";
    }

    @Test
    void theDefaultKnowledgeDirectoryIsAccepted() throws IOException {
        Path acme = profileFile("acme.yaml", "acme", "");
        profileFile("globex.yaml", "globex", "");

        ValidationReport report = validate(acme);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void aRelativeDirectoryIsAnError() throws IOException {
        Path acme = profileFile("acme.yaml", "acme", "knowledge:\n  directory: notes\n");

        assertThat(errors(acme)).containsExactly("knowledge.directory notes must be absolute"
            + " after expansion of ~ (e.g. ~/Notes/inubit/acme)");
    }

    static Stream<Arguments> ownDirectories() {
        return Stream.of(
            Arguments.of("workspace: ~/a/p\n", "a/p", "workspace"),
            Arguments.of("auditDirectory: ~/a/p\n", "a/p", "audit directory"),
            Arguments.of("", ".inubit-mcp/acme/backups", "backup directory"),
            Arguments.of("", ".inubit-mcp/acme/deployments", "deployment ledger directory"),
            Arguments.of("", ".inubit-mcp/acme/packages", "package directory"),
            Arguments.of("", ".config/inubit-mcp", "configuration directory"));
    }

    @ParameterizedTest
    @MethodSource("ownDirectories")
    void equalInsideAndContainsAreEachAnError(String settings, String relative, String what)
        throws IOException {
        Path p = home.resolve(relative);
        Path k = p.resolve("notes");
        Path acme = profileFile("acme.yaml", "acme", settings + knowledge(p));
        assertThat(errors(acme)).as("equal").contains(equal(p, what, p));

        acme = profileFile("acme.yaml", "acme", settings + knowledge(k));
        assertThat(errors(acme)).as("inside").contains(inside(k, what, p));

        acme = profileFile("acme.yaml", "acme", settings + knowledge(p.getParent()));
        assertThat(errors(acme)).as("contains").contains(contains(p.getParent(), what, p));
    }

    @Test
    void theServerDataDirectoryMayOnlyNotBeEqualOrContained() throws IOException {
        Path data = home.resolve(".inubit-mcp");
        String what = "server data directory ~/.inubit-mcp";

        assertThat(errors(profileFile("acme.yaml", "acme", knowledge(data))))
            .contains(equal(data, what, data));
        assertThat(errors(profileFile("acme.yaml", "acme", knowledge(home))))
            .contains(contains(home, what, data));
        assertThat(errors(profileFile("acme.yaml", "acme",
            knowledge(data.resolve("acme/notes"))))).isEmpty();
        assertThat(errors(profileFile("acme.yaml", "acme",
            knowledge(data.resolve("shared-notes"))))).isEmpty();
    }

    @Test
    void theDirectoriesOfAnotherProfileAreErrorsNamingItsFile() throws IOException {
        Path globex = profileFile("globex.yaml", "globex", "workspace: ~/g/ws\n"
            + "auditDirectory: ~/g/audit\n" + knowledge(home.resolve("g/notes")));
        String file = " (" + globex + ")";

        assertThat(errors(profileFile("acme.yaml", "acme", knowledge(home.resolve("g/ws")))))
            .containsExactly(equal(home.resolve("g/ws"), "workspace of profile 'globex'" + file,
                home.resolve("g/ws")));
        assertThat(errors(profileFile("acme.yaml", "acme",
            knowledge(home.resolve("g/audit/x")))))
            .containsExactly(inside(home.resolve("g/audit/x"),
                "audit directory of profile 'globex'" + file, home.resolve("g/audit")));
        assertThat(errors(profileFile("acme.yaml", "acme", knowledge(home.resolve("g/notes")))))
            .containsExactly(equal(home.resolve("g/notes"),
                "knowledge directory of profile 'globex'" + file, home.resolve("g/notes")));
        Path otherData = home.resolve(".inubit-mcp/globex");
        assertThat(errors(profileFile("acme.yaml", "acme",
            knowledge(otherData.resolve("notes")))))
            .containsExactly(inside(otherData.resolve("notes"),
                "data directory of profile 'globex'", otherData));
    }

    @Test
    void aCopyOfTheSameProfileSharesTheKnowledgeWithTheExistingWarningOnly() throws IOException {
        Path acme = profileFile("acme.yaml", "acme", "");
        profileFile("acme-copy.yaml", "acme", "");

        ValidationReport report = validate(acme);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).singleElement().asString()
            .contains("the profile name 'acme'").doesNotContain("knowledge");
    }

    @Test
    void theFolderOfAConfigurationFileElsewhereIsForbiddenUnlessItHoldsTheDataDirectory()
        throws IOException {
        Path elsewhere = home.resolve("elsewhere");
        Path file = write(elsewhere.resolve("acme.yaml"), "profile:\n  name: acme\n"
            + knowledge(elsewhere.resolve("notes")) + GROUPS);
        assertThat(errors(file)).containsExactly(inside(elsewhere.resolve("notes"),
            "folder of the configuration file", elsewhere));

        file = write(elsewhere.resolve("acme.yaml"), "profile:\n  name: acme\n"
            + knowledge(elsewhere) + GROUPS);
        assertThat(errors(file)).containsExactly(equal(elsewhere,
            "folder of the configuration file", elsewhere));

        // a file in the home directory: the default knowledge directory lies inside that folder
        Path inHome = write(home.resolve("acme.yaml"), "profile:\n  name: acme\n" + GROUPS);
        assertThat(errors(inHome)).isEmpty();
    }

    @Test
    void theDataDirectoryExemptionDoesNotCoverAWorkspaceOrAuditDirectoryHoldingIt()
        throws IOException {
        Path k = home.resolve(".inubit-mcp/acme/knowledge");

        assertThat(errors(profileFile("acme.yaml", "acme", "workspace: ~\n")))
            .contains(inside(k, "workspace", home));
        Path ownData = home.resolve(".inubit-mcp/acme");
        assertThat(errors(profileFile("acme.yaml", "acme", "auditDirectory: ~/.inubit-mcp/acme\n")))
            .contains(inside(k, "audit directory", ownData));

        Path globex = profileFile("globex.yaml", "globex", "auditDirectory: ~/.inubit-mcp/acme\n");
        assertThat(errors(profileFile("acme.yaml", "acme", "")))
            .contains(inside(k, "audit directory of profile 'globex' (" + globex + ")", ownData));
    }

    @Test
    void aSymbolicLinkRootIsResolved() throws IOException {
        Files.createDirectories(home.resolve("work"));
        Files.createSymbolicLink(home.resolve("alias"), home.resolve("work"));

        assertThat(errors(profileFile("acme.yaml", "acme",
            "workspace: ~/work\n" + knowledge(home.resolve("alias")))))
            .containsExactly(equal(home.resolve("alias"), "workspace", home.resolve("work")));
    }

    @Test
    void onACaseInsensitiveFileSystemTheCaseDoesNotMatter() throws IOException {
        Files.createDirectories(home.resolve("Work"));
        assumeTrue(Files.exists(home.resolve("WORK")), "case-sensitive file system");

        assertThat(errors(profileFile("acme.yaml", "acme",
            "workspace: ~/Work\n" + knowledge(home.resolve("work/notes")))))
            .containsExactly(inside(home.resolve("work/notes"), "workspace", home.resolve("Work")));
    }

    @Test
    void anOverlapErrorNamesOnlyPaths() throws IOException {
        List<String> errors = errors(profileFile("acme.yaml", "acme",
            "workspace: ~/w\n" + knowledge(home.resolve("w"))));

        assertThat(errors).isNotEmpty().allSatisfy(error -> assertThat(error)
            .doesNotContain("desc-knowledge-7", "node1.test.example.test"));
    }
}
