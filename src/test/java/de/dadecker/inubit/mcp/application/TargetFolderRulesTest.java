package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.application.TargetFolderRules.CheckedFolder;
import de.dadecker.inubit.mcp.config.ConfigLoader;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.NetworkPathProbe;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Feature 015 T013 (research R-9, R-10, FR-008, FR-010, Q10, Q15): the rules a target folder of
 * {@code export_archives} must meet, with temporary directories outside every git tree, a fake
 * {@link NetworkPathProbe} and a fake home and environment.
 */
class TargetFolderRulesTest {

    /** Rule names of contracts/mcp-tools-delta.md "Folder rules". */
    private static final String RELATIVE = "is not an absolute path";
    private static final String DOT_ELEMENT = "has a '.' or '..' element";
    private static final String NETWORK = "is a network path";
    private static final String NOT_WRITABLE = "is not a writable directory";
    private static final String SERVER_DIRECTORY = "is or lies inside one of the server's"
        + " directories";
    private static final String GIT_TREE = "is or lies inside a git working tree";
    private static final String CLOUD = "the archives (with clear-text secrets) will be"
        + " synchronized to the cloud";

    private static final boolean POSIX =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir(factory = GitFreeTempDir.class)
    Path tempDir;

    private Path root;
    private Path home;
    private Path workspace;
    private Path audit;
    private Path defaultConfig;
    private Path elsewhere;
    private final Map<String, String> environment = new HashMap<>();
    private final FakeProbe probe = new FakeProbe();

    @BeforeEach
    void setUp() throws IOException {
        // on macOS the temporary directory /var/folders/... is reached through /var -> /private/var
        root = tempDir.toRealPath();
        home = Files.createDirectories(root.resolve("home"));
        workspace = Files.createDirectories(root.resolve("workspace"));
        audit = Files.createDirectories(root.resolve("audit"));
        defaultConfig = new ConfigLoader(Map.of(), home, false).defaultDirectory();
        elsewhere = Files.createDirectories(root.resolve("elsewhere"));
    }

    @Test
    void refusesARelativePath() {
        TargetFolderRules rules = rules();

        assertRefused(rules, "archives", RELATIVE);
        assertRefused(rules, "archives/out", RELATIVE);
        assertRefused(rules, "", RELATIVE);
    }

    @Test
    void controlCharactersOfTheFolderAreShownAsQuestionMarks() throws IOException {
        Path cloud = Files.createDirectories(home.resolve("Library").resolve("CloudStorage"));
        Map<String, TargetFolderRules> cases = new java.util.LinkedHashMap<>();
        cases.put("archives\nout", rules());
        cases.put(root + "/a\u0000b", rules());
        cases.put(workspace.resolve("x\ry").toString(), rules());
        cases.put("\\\\server\\share\u0007\u009B", windowsRules());

        for (Map.Entry<String, TargetFolderRules> entry : cases.entrySet()) {
            ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
                () -> entry.getValue().check(entry.getKey()));
            assertThat(refused).as("refusal").isNotNull();
            assertThat(refused.error().message())
                .contains("'" + entry.getKey().replaceAll("\\p{Cc}", "?") + "'")
                .doesNotContainPattern("\\p{Cc}");
        }
        assertThat(rules().check(cloud.resolve("a\tb").toString()).warnings()).singleElement()
            .asString().contains("a?b").doesNotContain("\t");
    }

    @Test
    void refusesADotOrDotDotElement() throws IOException {
        Files.createDirectories(root.resolve("a").resolve("b"));
        TargetFolderRules rules = rules();

        assertRefused(rules, root + "/a/./b", DOT_ELEMENT);
        assertRefused(rules, root + "/a/../a/b", DOT_ELEMENT);
        assertRefused(rules, root + "/a/b/..", DOT_ELEMENT);
        assertRefused(rules, root + "/a/b/.", DOT_ELEMENT);
    }

    @Test
    void refusesTheServerDirectoryBelowHomeAndItsDescendants() throws IOException {
        Path inubitMcp = Files.createDirectories(home.resolve(".inubit-mcp").resolve("acme"));
        TargetFolderRules rules = rules();

        assertRefused(rules, home.resolve(".inubit-mcp").toString(), SERVER_DIRECTORY);
        assertRefused(rules, inubitMcp.toString(), SERVER_DIRECTORY);
        assertRefused(rules, inubitMcp.resolve("new").resolve("deeper").toString(),
            SERVER_DIRECTORY);
    }

    @Test
    void refusesTheWorkspaceAndItsDescendants() throws IOException {
        Files.createDirectories(workspace.resolve("GRP-01"));
        TargetFolderRules rules = rules();

        assertRefused(rules, workspace.toString(), SERVER_DIRECTORY, "workspace");
        assertRefused(rules, workspace.resolve("GRP-01").toString(), SERVER_DIRECTORY);
        assertRefused(rules, workspace.resolve("new").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesTheAuditDirectoryAndItsDescendants() {
        TargetFolderRules rules = rules();

        assertRefused(rules, audit.toString(), SERVER_DIRECTORY, "auditDirectory");
        assertRefused(rules, audit.resolve("new").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesTheDefaultConfigurationDirectoryInPosixForm() throws IOException {
        assertThat(defaultConfig).isEqualTo(home.resolve(".config").resolve("inubit-mcp"));
        TargetFolderRules missing = rules();

        assertRefused(missing, defaultConfig.toString(), SERVER_DIRECTORY,
            "configuration directory");
        assertRefused(missing, defaultConfig.resolve("sub").toString(), SERVER_DIRECTORY);

        Files.createDirectories(defaultConfig.resolve("sub"));
        assertRefused(rules(), defaultConfig.toString(), SERVER_DIRECTORY);
        assertRefused(rules(), defaultConfig.resolve("sub").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesTheDefaultConfigurationDirectoryInAppDataForm() throws IOException {
        Path roaming = Files.createDirectories(root.resolve("AppData").resolve("Roaming"));
        Path appData = new ConfigLoader(Map.of("APPDATA", roaming.toString()), home, true)
            .defaultDirectory();
        assertThat(appData).isEqualTo(roaming.resolve("inubit-mcp"));
        TargetFolderRules rules = rules(List.of(appData, elsewhere));

        assertRefused(rules, appData.toString(), SERVER_DIRECTORY, "configuration directory");
        assertRefused(rules, appData.resolve("sub").toString(), SERVER_DIRECTORY);
        assertAccepted(rules, roaming.resolve("other"));

        Path withoutAppData = new ConfigLoader(Map.of(), home, true).defaultDirectory();
        assertThat(withoutAppData)
            .isEqualTo(home.resolve("AppData").resolve("Roaming").resolve("inubit-mcp"));
        TargetFolderRules fallback = rules(List.of(withoutAppData, elsewhere));
        assertRefused(fallback, withoutAppData.toString(), SERVER_DIRECTORY);
        assertRefused(fallback, withoutAppData.resolve("sub").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesTheFolderOfAConfigurationFileLoadedFromElsewhere() throws IOException {
        Files.createDirectories(elsewhere.resolve("sub"));
        TargetFolderRules rules = rules(List.of(defaultConfig, elsewhere));

        assertRefused(rules, elsewhere.toString(), SERVER_DIRECTORY,
            "folder of the configuration file in use");
        assertRefused(rules, elsewhere.resolve("sub").toString(), SERVER_DIRECTORY);
        assertRefused(rules, elsewhere.resolve("new").toString(), SERVER_DIRECTORY);
        assertAccepted(rules, root.resolve("elsewhere-2"));
        Files.createDirectories(root.resolve("elsewhere-2"));
        assertAccepted(rules, root.resolve("elsewhere-2"));
    }

    @Test
    void refusesAFolderInsideAGitTreeWithAGitDirectory() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path sub = Files.createDirectories(repo.resolve("sub"));
        TargetFolderRules rules = rules();

        assertRefused(rules, repo.toString(), GIT_TREE);
        assertRefused(rules, sub.toString(), GIT_TREE);
        assertRefused(rules, sub.resolve("new").toString(), GIT_TREE);
    }

    @Test
    void refusesAFolderInsideAGitTreeWithAGitFile() throws IOException {
        Path worktree = Files.createDirectories(root.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../repo/.git/worktrees/wt\n");
        Path sub = Files.createDirectories(worktree.resolve("sub"));
        TargetFolderRules rules = rules();

        assertRefused(rules, worktree.toString(), GIT_TREE);
        assertRefused(rules, sub.toString(), GIT_TREE);
    }

    @Test
    void refusesAMissingFolderWhoseNearestExistingAncestorIsInAGitTree() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.writeString(repo.resolve(".git"), "gitdir: elsewhere\n");
        Path inside = Files.createDirectories(repo.resolve("inside"));

        assertRefused(rules(), inside.resolve("a").resolve("b").resolve("c").toString(),
            GIT_TREE);
    }

    @Test
    void refusesALinkIntoTheWorkspace() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path inner = Files.createDirectories(workspace.resolve("inner"));
        Path link = link(root.resolve("links").resolve("ws"), inner);
        TargetFolderRules rules = rules();

        assertRefused(rules, link.toString(), SERVER_DIRECTORY, "workspace");
        assertRefused(rules, link.resolve("new").toString(), SERVER_DIRECTORY, "workspace");
    }

    @Test
    void refusesALinkIntoAGitTree() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path sub = Files.createDirectories(repo.resolve("sub"));
        Path link = link(root.resolve("links").resolve("git"), sub);

        assertRefused(rules(), link.toString(), GIT_TREE);
        assertRefused(rules(), link.resolve("new").toString(), GIT_TREE);
    }

    @Test
    void refusesALinkIntoTheDefaultConfigurationDirectory() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path inner = Files.createDirectories(defaultConfig.resolve("inner"));
        Path link = link(root.resolve("links").resolve("config"), inner);

        assertRefused(rules(), link.toString(), SERVER_DIRECTORY, "configuration directory");
        assertRefused(rules(), link.resolve("new").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesALinkIntoTheFolderOfTheConfigurationFile() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path inner = Files.createDirectories(elsewhere.resolve("inner"));
        Path link = link(root.resolve("links").resolve("elsewhere"), inner);
        TargetFolderRules rules = rules(List.of(defaultConfig, elsewhere));

        assertRefused(rules, link.toString(), SERVER_DIRECTORY,
            "folder of the configuration file in use");
        assertRefused(rules, link.resolve("new").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesAFolderBelowAForbiddenRootThatIsItselfALink() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path realWorkspace = Files.createDirectories(root.resolve("real-workspace"));
        Path workspaceLink = link(root.resolve("workspace-link"), realWorkspace);
        Path realConfig = Files.createDirectories(root.resolve("real-config"));
        Path configLink = link(root.resolve("config-link"), realConfig);
        TargetFolderRules rules = new TargetFolderRules(home, workspaceLink, audit,
            List.of(defaultConfig, configLink), environment, probe, false);

        assertRefused(rules, realWorkspace.toString(), SERVER_DIRECTORY, "workspace");
        assertRefused(rules, realWorkspace.resolve("x").toString(), SERVER_DIRECTORY);
        assertRefused(rules, workspaceLink.resolve("x").toString(), SERVER_DIRECTORY);
        assertRefused(rules, realConfig.toString(), SERVER_DIRECTORY,
            "folder of the configuration file in use");
        assertRefused(rules, realConfig.resolve("sub").toString(), SERVER_DIRECTORY);
        assertRefused(rules, configLink.resolve("sub").toString(), SERVER_DIRECTORY);
    }

    @Test
    void refusesAFolderGivenInsideTheWorkspaceThroughALinkPointingOutOfIt() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = link(workspace.resolve("out"), outside);

        assertRefused(rules(), link.resolve("x").toString(), SERVER_DIRECTORY, "workspace");
        assertAccepted(rules(), outside.resolve("x"));
    }

    @Test
    void refusesAFolderGivenInsideAGitTreeThroughALinkPointingOutOfIt() throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = link(repo.resolve("link"), outside);

        String message = assertRefused(rules(), link.resolve("x").toString(), GIT_TREE);
        assertThat(message).contains("a .git entry in " + repo + ")");
        assertAccepted(rules(), outside.resolve("x"));
    }

    @Test
    void refusesAFolderGivenInsideTheServerDirectoryBelowHomeThroughALinkPointingOutOfIt()
        throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = link(home.resolve(".inubit-mcp").resolve("out"), outside);

        assertRefused(rules(), link.resolve("x").toString(), SERVER_DIRECTORY, "~/.inubit-mcp");
        assertAccepted(rules(), outside.resolve("x"));
    }

    @Test
    void refusesAFolderGivenInsideTheAuditDirectoryThroughALinkPointingOutOfIt()
        throws IOException {
        assumeTrue(POSIX, "symbolic links");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = link(audit.resolve("out"), outside);

        assertRefused(rules(), link.resolve("x").toString(), SERVER_DIRECTORY,
            "auditDirectory");
        assertAccepted(rules(), outside.resolve("x"));
    }

    @Test
    void reportsNotWritableBeforeServerDirectoryForAFileInsideTheWorkspace() throws IOException {
        Path file = Files.writeString(workspace.resolve("file.txt"), "x");

        assertThat(assertRefused(rules(), file.toString(), NOT_WRITABLE))
            .doesNotContain(SERVER_DIRECTORY);
        assertThat(assertRefused(rules(), file.resolve("sub").toString(), NOT_WRITABLE))
            .doesNotContain(SERVER_DIRECTORY);
    }

    @Test
    void reportsServerDirectoryBeforeGitTreeForAWorkspaceInsideAGitTree() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path gitWorkspace = Files.createDirectories(repo.resolve("workspace"));
        TargetFolderRules rules = new TargetFolderRules(home, gitWorkspace, audit,
            List.of(defaultConfig, elsewhere), environment, probe, false);

        assertThat(assertRefused(rules, gitWorkspace.resolve("x").toString(), SERVER_DIRECTORY,
            "workspace")).doesNotContain(GIT_TREE);
        assertRefused(rules, repo.resolve("other").toString(), GIT_TREE);
    }

    @Test
    void reportsDotElementBeforeServerDirectory() {
        assertThat(assertRefused(rules(), workspace + "/a/../b", DOT_ELEMENT))
            .doesNotContain(SERVER_DIRECTORY);
    }

    @Test
    void refusesInsteadOfWarningBelowACloudSyncRootInsideAGitTree() throws IOException {
        Path cloud = Files.createDirectories(home.resolve("Library").resolve("CloudStorage")
            .resolve("Acme-Drive"));
        Files.createDirectories(cloud.resolve(".git"));

        assertThat(assertRefused(rules(), cloud.resolve("archives").toString(), GIT_TREE))
            .doesNotContain(CLOUD);
    }

    @Test
    void refusesAnExistingFile() throws IOException {
        Path file = Files.writeString(root.resolve("file.txt"), "x");

        assertRefused(rules(), file.toString(), NOT_WRITABLE);
    }

    @Test
    void refusesAMissingFolderBelowAFile() throws IOException {
        Path file = Files.writeString(root.resolve("file.txt"), "x");

        assertRefused(rules(), file.resolve("sub").toString(), NOT_WRITABLE);
    }

    @Test
    void refusesANonWritableFolderAndAMissingFolderBelowIt() throws IOException {
        assumeTrue(POSIX, "POSIX permissions");
        Path readOnly = Files.createDirectories(root.resolve("read-only"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeTrue(!Files.isWritable(readOnly), "not running with root privileges");

            assertRefused(rules(), readOnly.toString(), NOT_WRITABLE);
            assertRefused(rules(), readOnly.resolve("new").toString(), NOT_WRITABLE);
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void refusesUncRootsAsNetworkPathsWithTheWindowsFlag() {
        TargetFolderRules rules = windowsRules();

        assertNetworkRefusal(rules, "\\\\server\\share\\x");
        assertNetworkRefusal(rules, "\\\\?\\UNC\\server\\share");
        assertThat(probe.asked).isEmpty();
    }

    @Test
    void refusesARemoteDriveAsANetworkPathWithTheWindowsFlag() {
        probe.remote = 'Z';
        TargetFolderRules rules = windowsRules();

        assertNetworkRefusal(rules, "Z:\\x");
        assertThat(probe.asked).containsExactly('Z');
    }

    @Test
    void asksTheProbeWithTheUpperCaseLetterOfALowerCaseDrive() {
        probe.remote = 'Z';

        String message = assertRefused(windowsRules(), "z:\\x", NETWORK);
        assertThat(message).contains("Z: is a mapped network drive");
        assertThat(probe.asked).containsExactly('Z');
    }

    @Test
    void acceptsTheForwardSlashAsDriveSeparatorWithTheWindowsFlag() {
        probe.remote = 'Z';

        assertNetworkRefusal(windowsRules(), "Z:/x");
        assertThat(probe.asked).containsExactly('Z');
    }

    @Test
    void appliesTheAbsolutePathRuleToALocalDriveOnANonWindowsHost() {
        assumeTrue(!Path.of("C:\\x").isAbsolute(), "a host where 'C:\\x' is a relative path");
        probe.remote = 'Z';
        TargetFolderRules rules = windowsRules();

        assertThat(assertRefused(rules, "C:\\x", RELATIVE)).doesNotContain(NETWORK);
        assertThat(assertRefused(rules, "c:/x", RELATIVE)).doesNotContain(NETWORK);
        assertThat(probe.asked).containsExactly('C', 'C');
    }

    @Test
    void asksNoProbeForADriveRelativePathOrWithoutTheWindowsFlag() {
        assumeTrue(!Path.of("Z:\\x").isAbsolute(), "a host where 'Z:\\x' is a relative path");
        probe.remote = 'Z';

        assertRefused(windowsRules(), "Z:x", RELATIVE);
        assertRefused(rules(), "Z:\\x", RELATIVE);
        assertRefused(rules(), "\\\\server\\share\\x", RELATIVE);
        assertThat(probe.asked).isEmpty();
    }

    @Test
    void warnsBelowTheMacOsCloudSyncRoots() throws IOException {
        Path cloudStorage = Files.createDirectories(home.resolve("Library").resolve("CloudStorage")
            .resolve("Acme-Drive"));
        Path mobileDocuments = Files.createDirectories(home.resolve("Library")
            .resolve("Mobile Documents"));
        TargetFolderRules rules = rules();

        assertCloudWarning(rules.check(cloudStorage.resolve("archives").toString()));
        assertCloudWarning(rules.check(mobileDocuments.resolve("archives").toString()));
    }

    @Test
    void warnsBelowTheOneDriveFoldersOfTheEnvironment() throws IOException {
        for (String variable : List.of("OneDrive", "OneDriveCommercial", "OneDriveConsumer")) {
            Path folder = Files.createDirectories(root.resolve("sync-" + variable));
            environment.clear();
            environment.put(variable, folder.toString());

            assertCloudWarning(rules().check(folder.resolve("archives").toString()));
        }
    }

    @Test
    void acceptsANewFolderBelowTheTemporaryDirectory() {
        Path folder = root.resolve("archives").resolve("2026-11");

        CheckedFolder checked = rules().check(folder.toString());

        assertThat(checked).isEqualTo(new CheckedFolder(folder, false, root, List.of()));
    }

    @Test
    void acceptsAnExistingWritableFolder() throws IOException {
        Path folder = Files.createDirectories(root.resolve("archives"));

        CheckedFolder checked = rules().check(folder.toString());

        assertThat(checked).isEqualTo(new CheckedFolder(folder, true, folder, List.of()));
    }

    private TargetFolderRules rules() {
        return rules(List.of(defaultConfig, elsewhere));
    }

    private TargetFolderRules rules(List<Path> configurationRoots) {
        return new TargetFolderRules(home, workspace, audit, configurationRoots, environment,
            probe, false);
    }

    private TargetFolderRules windowsRules() {
        return new TargetFolderRules(home, workspace, audit, List.of(defaultConfig, elsewhere),
            environment, probe, true);
    }

    private static Path link(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        return Files.createSymbolicLink(link, target);
    }

    private static String assertRefused(TargetFolderRules rules, String folder,
        String... parts) {
        ToolErrorException refused = catchThrowableOfType(ToolErrorException.class,
            () -> rules.check(folder));
        assertThat(refused).as("refusal of '%s'", folder).isNotNull();
        assertThat(refused.error().code()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(refused.error().message()).contains(parts).contains("'" + folder + "'");
        return refused.error().message();
    }

    private static void assertNetworkRefusal(TargetFolderRules rules, String folder) {
        assertThat(assertRefused(rules, folder, NETWORK)).doesNotContain(RELATIVE);
    }

    private static void assertAccepted(TargetFolderRules rules, Path folder) {
        assertThat(rules.check(folder.toString()).path()).isEqualTo(folder);
    }

    private static void assertCloudWarning(CheckedFolder checked) {
        assertThat(checked.warnings()).singleElement().asString().contains(CLOUD)
            .contains(checked.path().toString());
    }

    private static final class FakeProbe implements NetworkPathProbe {

        private final List<Character> asked = new ArrayList<>();
        private char remote;

        @Override
        public boolean remoteDrive(char letter) {
            asked.add(letter);
            return letter == remote;
        }
    }
}
