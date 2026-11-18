package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.adapter.cli.CliCommand.TagScope;
import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.UnlistedTagCommand;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.TaggedVersion;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.ActiveTag;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Answer;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Folder;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.FolderTags;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.GroupTags;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Inputs;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Outcome;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Read;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Removal;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.RemoveActive;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.SetActive;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Step;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Stop;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.TagExport;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.TagGroup;
import de.dadecker.inubit.mcp.live.ManageTagsLiveTestSupport.Unlisted;
import de.dadecker.inubit.mcp.live.ServerLogLiveTestSupport.Run;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.TestAbortedException;

/**
 * Feature 022 T055 (live check L-4): the offline parts of {@link ManageTagsLiveTest} — the gate
 * and inputs, the steps and cleanup commands in their forms, the derived reads on the recorded
 * fixtures, the W15 folder, the sequence with its precondition, stop and cleanup on a simulated
 * node, and the records and lines. A plain unit test (not tagged {@code live}): nothing is
 * contacted.
 */
class ManageTagsLiveTestSupportTest {

    private static final NodeId NODE = NodeId.of("test", "node1");
    private static final String STEPS = "S1,S2,W13,S3,W14,S4,W15,W16,W17,W18";
    private static final String TAG = "l4-test";
    private static final String G1 = "GRP-T1";
    private static final String G2 = "GRP-T2";
    private static final String OWNER = "owner1";
    private static final String USER = "tester";
    private static final String FOLDER = "/Root/owner1/xsd";
    private static final String FILE = FOLDER + "/release.xsl";
    /** {@link #FILE} as the lines show it: relative to the owner root. */
    private static final String SHOWN = "~/xsd/release.xsl";
    /** The tagged repository file of the recorded {@code export_release.zip}. */
    private static final String FIXTURE_FILE = "/Root/jdoe/xsd/release.xsl";
    private static final Inputs DERIVED =
        new Inputs(TAG, G1, G2, Optional.empty(), USER, OWNER);
    private static final String RAW_LINE = "mail.smtp.password=example-only-0001";

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- gate and inputs -------------------------------------------------------------------------

    @Test
    void withoutTheStepListTheCheckIsSkipped() {
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.requireApproval(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining(ManageTagsLiveTestSupport.STEPS_VARIABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"S1,S2", "S2,S1,W13,S3,W14,S4,W15,W16,W17,W18",
        "S1,S2,W13,S3,W14,S4,W15,W16,W17,W18,C1", "approved"})
    void onlyTheExactStepListRuns(String list) {
        env.put(ManageTagsLiveTestSupport.STEPS_VARIABLE, list);

        assertThatThrownBy(() -> ManageTagsLiveTestSupport.requireApproval(env))
            .isInstanceOf(AssertionError.class).hasMessageContaining("nothing was run");
    }

    @Test
    void theExactStepListPassesTheGate() {
        env.put(ManageTagsLiveTestSupport.STEPS_VARIABLE, " S1, S2,W13,S3,W14,S4,W15,W16,W17,W18 ");

        assertThatCode(() -> ManageTagsLiveTestSupport.requireApproval(env))
            .doesNotThrowAnyException();
        assertThat(String.join(",", ManageTagsLiveTestSupport.STEP_IDS)).isEqualTo(STEPS);
    }

    @Test
    void theInputsAreReadAndLoginResolvesToTheStartCliUser() {
        inputs("derived", "login");

        Inputs inputs = ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), "cli-user");

        assertThat(inputs).isEqualTo(new Inputs(TAG, G1, G2, Optional.empty(), "cli-user",
            OWNER));
        inputs(FOLDER, USER);
        assertThat(ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), "cli-user"))
            .isEqualTo(new Inputs(TAG, G1, G2, Optional.of(FOLDER), USER, OWNER));
    }

    @ParameterizedTest
    @ValueSource(strings = {ManageTagsLiveTestSupport.TAG_VARIABLE,
        ManageTagsLiveTestSupport.GROUPS_VARIABLE, ManageTagsLiveTestSupport.REPOSITORY_PATH_VARIABLE,
        ManageTagsLiveTestSupport.USER_VARIABLE})
    void everyInputIsRequired(String variable) {
        inputs("derived", USER);
        env.remove(variable);

        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), USER))
            .isInstanceOf(AssertionError.class).hasMessageContaining(variable)
            .hasMessageContaining("nothing was run");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GRP-T1", "GRP-T1,GRP-T1", "GRP-T1,GRP-T2,GRP-T3", "GRP-T1,"})
    void theGroupsAreExactlyTwoDifferentOnes(String groups) {
        inputs("derived", USER);
        env.put(ManageTagsLiveTestSupport.GROUPS_VARIABLE, groups);

        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), USER))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining(ManageTagsLiveTestSupport.GROUPS_VARIABLE);
    }

    @Test
    void aValueStartCliCannotTakeFailsWithoutEchoingIt() {
        inputs("derived", USER);
        env.put(ManageTagsLiveTestSupport.TAG_VARIABLE, "it's-secret-1");

        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), USER))
            .isInstanceOf(AssertionError.class).hasMessageContaining("nothing was run")
            .hasMessageNotContaining("secret-1");
        inputs("Root/no-slash", USER);
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), USER))
            .hasMessageContaining(ManageTagsLiveTestSupport.REPOSITORY_PATH_VARIABLE);
        inputs("derived", "-tester");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER), USER))
            .hasMessageContaining("nothing was run");
    }

    @Test
    void aNodeWithoutInventoryOwnerFails() {
        inputs("derived", USER);

        assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.empty(), USER))
            .isInstanceOf(AssertionError.class).hasMessageContaining("inventory.owner");
    }

    @Test
    void theNodeNeedsWriteAndDevelopment() {
        assertThatCode(() -> ManageTagsLiveTestSupport.requireNode(NODE, true, true))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.requireNode(NODE, false, true))
            .hasMessageContaining("write.enabled");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.requireNode(NODE, true, false))
            .hasMessageContaining("development.enabled");
    }

    @Test
    void theTaskFolderIsT055OrItsRunNumber() {
        assertThat(ManageTagsLiveTestSupport.taskDirectory(env)).isEqualTo("T055");
        env.put(ManageTagsLiveTestSupport.RUN_VARIABLE, "2");
        assertThat(ManageTagsLiveTestSupport.taskDirectory(env)).isEqualTo("T055-2");
        env.put(ManageTagsLiveTestSupport.RUN_VARIABLE, "1");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.taskDirectory(env))
            .hasMessageContaining("nothing was run");
    }

    @Test
    void theFolderReadNeedsARecordFolderStartCliCanTake() {
        assertThatCode(() -> ManageTagsLiveTestSupport.requireExportPath(
            Path.of("/home/tester/recordings/T055"), false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.requireExportPath(
            Path.of("/home/tester/rec'ordings/T055"), false))
            .hasMessageContaining("nothing was run");
    }

    // --- steps -----------------------------------------------------------------------------------

    @Test
    void theStepsAreTheApprovedFormsInOrder() {
        Inputs stated = new Inputs(TAG, G1, G2, Optional.of(FOLDER), USER, OWNER);

        assertThat(ManageTagsLiveTestSupport.steps(stated)).extracting(Step::id)
            .containsExactly(STEPS.split(","));
        assertThat(ManageTagsLiveTestSupport.steps(stated)).extracting(Step::form)
            .containsExactly(
                "tag --tagMove 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType 'technical'"
                    + " --tagUser 'owner1'",
                "tag --tagMove 'l4-test' --tagWorkflowGroup 'GRP-T2' --tagWorkflowType 'technical'"
                    + " --tagUser 'owner1'",
                "tag --tagRemove 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType"
                    + " 'technical'",
                "tag --tagMove 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType 'technical'"
                    + " --tagUser 'owner1'",
                "tag --tagDelete 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType"
                    + " 'technical'",
                "tag --tagMove 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType 'technical'"
                    + " --tagUser 'owner1'",
                "tag --tagRemove 'l4-test' --tagRepositoryPath '/Root/owner1/xsd'",
                "tag --tagRemove 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType"
                    + " 'technical' --tagUser 'owner1'",
                "tag --tagSetActive 'l4-test' --tagUser 'tester'",
                "tag --tagRemoveActive --tagUser 'tester'");
        assertThat(ManageTagsLiveTestSupport.steps(DERIVED).get(6).form())
            .isEqualTo("tag --tagRemove 'l4-test' --tagRepositoryPath '<derived folder>'");
        assertThat(ManageTagsLiveTestSupport.steps(stated)).extracting(Step::stem)
            .doesNotHaveDuplicates();
        assertThat(ManageTagsLiveTestSupport.steps(stated))
            .extracting(step -> step.expectTagged().orElse("-"))
            .containsExactly("g1", "g2", "-", "g1", "-", "g1", "-", "-", "-", "-");
    }

    @Test
    void theCleanupIsScopedFormsOfTheCatalogueOnly() {
        assertThat(ManageTagsLiveTestSupport.cleanupSteps(DERIVED, Optional.of(FOLDER)))
            .extracting(step -> step.id() + " " + step.form()).containsExactly(
                "C1 tag --tagDelete 'l4-test' --tagWorkflowGroup 'GRP-T1' --tagWorkflowType"
                    + " 'technical'",
                "C2 tag --tagDelete 'l4-test' --tagWorkflowGroup 'GRP-T2' --tagWorkflowType"
                    + " 'technical'",
                "C3 tag --tagRemove 'l4-test' --tagRepositoryPath '/Root/owner1/xsd'",
                "C4 tag --tagRemoveActive --tagUser 'tester'");
        assertThat(ManageTagsLiveTestSupport.cleanupSteps(DERIVED, Optional.empty()))
            .extracting(Step::id).containsExactly("C1", "C2", "C4");
    }

    @Test
    void theUnlistedFormTakesOnlyValuesStartCliCanCarry() {
        assertThatThrownBy(() -> UnlistedTagCommand.removeWithOwner(TAG, G1, "technical",
            "jd'oe")).isInstanceOf(ToolErrorException.class)
            .hasMessageNotContaining("jd'oe");
    }

    // --- reads -----------------------------------------------------------------------------------

    @Test
    void theHistoryOfAGroupGivesTheVersionsCarryingTheTag() throws IOException {
        byte[] history = entry(FakeProcessLauncher.fixture("export_history_sample.zip"),
            "versionHistory.xml");

        GroupTags tagged = ManageTagsLiveTestSupport.groupTags(NODE, history, "TAG-01");
        GroupTags other = ManageTagsLiveTestSupport.groupTags(NODE, history, TAG);

        assertThat(tagged.workflows()).isEqualTo(3);
        assertThat(tagged.tagged()).hasSize(1);
        assertThat(other).isEqualTo(new GroupTags(3, List.of()));
        assertThat(ManageTagsLiveTestSupport.describe(other)).isEqualTo("3 workflows, none tagged");
    }

    @Test
    void theExportByTheTagGivesItsGroupsVersionsAndTaggedRepositoryFiles() {
        TagExport export = ManageTagsLiveTestSupport.tagExport(
            FakeProcessLauncher.fixture("export_release.zip"), "TAG-01");

        assertThat(export.groups()).containsExactly("GRP-01");
        assertThat(export.versions()).isEqualTo(11);
        assertThat(export.files()).containsExactly(Map.entry(FIXTURE_FILE, "1.0"));
        assertThat(ManageTagsLiveTestSupport.describe(export, DERIVED)).isEqualTo(
            "groups 1 other, 11 versions, 1 repository files tagged: /Root/<other>/xsd/release.xsl"
                + " v1.0");
        assertThat(ManageTagsLiveTestSupport.describe(export,
            new Inputs(TAG, "GRP-01", G2, Optional.empty(), USER, OWNER)))
            .startsWith("groups g1, 11 versions");
    }

    @Test
    void aFolderExportGivesItsFilesAndThoseCarryingTheTag() throws IOException {
        FolderTags untagged = ManageTagsLiveTestSupport.folderTags(
            FakeProcessLauncher.fixture("export_repository.zip"), "TAG-01");
        FolderTags tagged = ManageTagsLiveTestSupport.folderTags(
            entry(FakeProcessLauncher.fixture("export_release.zip"), "Repository.zip"), "TAG-01");

        assertThat(untagged).isEqualTo(new FolderTags(2, new TreeMap<>()));
        assertThat(tagged).isEqualTo(new FolderTags(1,
            new TreeMap<>(Map.of(FIXTURE_FILE, "1.0"))));
        assertThat(ManageTagsLiveTestSupport.describe(untagged, DERIVED))
            .isEqualTo("2 files, 0 repository files tagged");
    }

    @Test
    void theActiveTagNamesTheTestTagOnly() {
        assertThat(ActiveTag.of(Optional.of(TAG), TAG)).isEqualTo(ActiveTag.TEST_TAG);
        assertThat(ActiveTag.of(Optional.of("REL-7"), TAG)).isEqualTo(ActiveTag.OTHER);
        assertThat(ActiveTag.of(Optional.empty(), TAG)).isEqualTo(ActiveTag.NONE);
        assertThat(ManageTagsLiveTestSupport.describe(Optional.of(ActiveTag.OTHER)))
            .isEqualTo("another tag");
        assertThat(ManageTagsLiveTestSupport.describe(Optional.empty())).isEqualTo("unknown");
    }

    @Test
    void theW15FolderIsTheDeepestFolderBelowTheOwnerRootOrTheStatedOne() {
        TagExport tagged = export(Set.of(G1), Map.of(FILE, "1.0",
            "/Root/owner1/xsd/sub/z.xsd", "1.1", "/Root/owner1/a/x.xsd", "2.0",
            "/Root/owner1/top.xsd", "1.0", "/Root/other/xsd/deep/er/y.xsd", "3.0"));

        Folder derived = ManageTagsLiveTestSupport.w15Folder(Optional.empty(), tagged, OWNER);
        Folder stated = ManageTagsLiveTestSupport.w15Folder(Optional.of(FOLDER), tagged, OWNER);
        Folder elsewhere = ManageTagsLiveTestSupport.w15Folder(Optional.of("/Root/owner1/b"),
            tagged, OWNER);

        assertThat(derived).isEqualTo(new Folder(Optional.of("/Root/owner1/xsd/sub"),
            "folder ~/xsd/sub (derived: the deepest of 3 folders below the owner's repository root"
                + " holding files carrying the tag)", false));
        assertThat(stated).isEqualTo(new Folder(Optional.of(FOLDER), "folder ~/xsd (stated)",
            false));
        assertThat(elsewhere).isEqualTo(new Folder(Optional.empty(), "the stated folder ~/b"
            + " holds none of the 5 files carrying the tag", true));
    }

    @Test
    void theOwnerRootAndAnotherOwnersFolderAreNeverTheW15Folder() {
        TagExport outside = export(Set.of(G1), Map.of("/Root/owner1/top.xsd", "1.0",
            "/Root/other/xsd/y.xsd", "3.0"));

        Folder derived = ManageTagsLiveTestSupport.w15Folder(Optional.empty(), outside, OWNER);
        Folder root = ManageTagsLiveTestSupport.w15Folder(Optional.of("/Root/owner1"), outside,
            OWNER);
        Folder other = ManageTagsLiveTestSupport.w15Folder(Optional.of("/Root/other/xsd"),
            outside, OWNER);
        Folder none = ManageTagsLiveTestSupport.w15Folder(Optional.empty(), TagExport.NONE, OWNER);

        assertThat(derived).isEqualTo(new Folder(Optional.empty(), "not applicable: none of the 2"
            + " folders of files carrying the tag lies below the owner's repository root", false));
        assertThat(root).isEqualTo(new Folder(Optional.empty(), "the stated folder ~ is not below"
            + " the owner's repository root", true));
        assertThat(other).isEqualTo(new Folder(Optional.empty(), "the stated folder"
            + " /Root/<other>/xsd is not below the owner's repository root", true));
        assertThat(none).isEqualTo(new Folder(Optional.empty(), "not applicable: the tag reached"
            + " no repository file", false));
        assertThat(String.join(" ", derived.note(), root.note(), other.note()))
            .doesNotContain(OWNER, "other/");
    }

    @Test
    void aStatedFolderMustLieBelowTheOwnerRoot() {
        for (String folder : List.of("/Root/owner1", "/Root/other/xsd", "/Root/owner10/xsd")) {
            inputs(folder, USER);

            assertThatThrownBy(() -> ManageTagsLiveTestSupport.inputs(env, Optional.of(OWNER),
                USER)).as(folder).isInstanceOf(AssertionError.class)
                .hasMessageContaining(ManageTagsLiveTestSupport.REPOSITORY_PATH_VARIABLE)
                .hasMessageContaining("nothing was run");
        }
    }

    // --- the sequence ----------------------------------------------------------------------------

    @Test
    void allStepsRunOnceInOrderEachFollowedByItsReadThenTheCleanupAndTheCheck()
        throws IOException {
        FakeNode node = new FakeNode();

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(node.sent).containsExactly("S1", "S2", "W13", "S3", "W14", "S4", "W15",
            "W16", "W17", "W18", "C1", "C2");
        assertThat(node.sentForms.get("W15"))
            .isEqualTo("tag --tagRemove 'l4-test' --tagRepositoryPath '/Root/owner1/xsd'");
        assertThat(node.reads).containsSubsequence("B0-H1", "B0-H2", "B0-T", "B0-A", "S1-after-H1",
            "S2-after-T", "W14-after-T", "S4-after-T", "W15-before-P", "W15-after-P",
            "W16-after-P", "W17-after-A", "W18-after-A", "V-H1", "V-H2", "V-T", "V-A", "V-F1-P");
        assertThat(node.reads).doesNotContain("B0-P", "S4-after-P");
        assertThat(outcome.stop()).isEmpty();
        assertThat(outcome.cleanup()).containsExactly("C1 done", "C2 done");
        assertThat(outcome.remains()).isEmpty();
        assertThat(outcome.notes()).containsExactly("W16 refused by INUBIT (exit 1)");
        assertThatCode(() -> ManageTagsLiveTestSupport.assertOutcome(outcome))
            .doesNotThrowAnyException();
        assertThat(node.records.get("W15-folder")).contains("folder ~/xsd", "derived");
        assertThat(node.records.get("V-F1")).isEqualTo(
            "folder ~/xsd 2 files, 0 repository files tagged");
        assertThat(node.records.get("W17-tag-set-active")).isEqualTo(
            "answer done; active tag the test tag");
        assertThat(node.records.get("S2-tag-move-g2")).isEqualTo("answer done; before: g1 1"
            + " workflow, 1 tagged: workflow W-1 v2; g2 1 workflow, none tagged; by tag groups"
            + " g1, 1 versions, 1 repository files tagged: " + SHOWN + " v1.0; after: g1 1"
            + " workflow, 1 tagged: workflow W-1 v2; g2 1 workflow, 1 tagged: workflow W-2 v2; by"
            + " tag groups g1, g2, 2 versions, 1 repository files tagged: " + SHOWN + " v1.0");
        assertThat(String.join("\n", node.records.values())).doesNotContain(G1, G2, OWNER, USER);
    }

    @Test
    void aTagInUseOrAnActiveTagStopsBeforeAnyWrite() throws IOException {
        FakeNode used = new FakeNode();
        used.g2 = true;
        FakeNode active = new FakeNode();
        active.active = ActiveTag.OTHER;
        FakeNode empty = new FakeNode();
        empty.workflows = 0;

        Outcome first = ManageTagsLiveTestSupport.run(used, DERIVED);
        Outcome second = ManageTagsLiveTestSupport.run(active, DERIVED);
        Outcome third = ManageTagsLiveTestSupport.run(empty, DERIVED);

        assertThat(used.sent).isEmpty();
        assertThat(active.sent).isEmpty();
        assertThat(empty.sent).isEmpty();
        assertThat(first.stop().orElseThrow().code()).contains("in use", "nothing was written");
        assertThat(second.stop().orElseThrow().code()).contains("active tag is another tag");
        assertThat(third.stop().orElseThrow().code()).contains("g1 holds no workflow");
        assertThat(first.cleanup()).isEmpty();
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.assertOutcome(first))
            .hasMessageContaining("stopped at B0");
    }

    @Test
    void anErrorStopsTheSequenceAndOnlyTheCleanupRuns() throws IOException {
        FakeNode node = new FakeNode();
        node.failing.put("W13", "IMPORT_FAILED");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(node.sent).containsExactly("S1", "S2", "W13", "C1", "C2");
        assertThat(node.reads).contains("W13-after-H1").doesNotContain("S3-after-H1");
        assertThat(outcome.stop()).contains(new Stop("W13", "IMPORT_FAILED"));
        // the group removals of the simulated node leave the repository file tagged; V finds it
        assertThat(outcome.remains()).containsExactly(
            "folder ~/xsd 2 files, 1 repository files tagged: " + SHOWN + " v1.0");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.assertOutcome(outcome))
            .hasMessageContaining("stopped at W13: IMPORT_FAILED")
            .hasMessageContaining("cleanup C1 done, C2 done")
            .hasMessageContaining("the owner must remove it: folder ~/xsd");
    }

    @Test
    void aTagStepWithoutEffectIsADeviation() throws IOException {
        FakeNode node = new FakeNode();
        node.noEffect.add("S2");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(outcome.stop().orElseThrow().code())
            .isEqualTo("DEVIATION (g2 carries no version with the tag)");
        assertThat(node.sent).containsExactly("S1", "S2", "C1");
    }

    @Test
    void anUnknownReadStopsAndItsPartIsCleanedAsIfTagged() throws IOException {
        FakeNode node = new FakeNode();
        node.unknownRead = "W14-after-H2";

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(outcome.stop()).contains(new Stop("W14", "READ g2 unknown"));
        assertThat(node.sent).endsWith("W14", "C2");
    }

    @Test
    void withoutATaggedRepositoryFileW15IsNotApplicableAndNoFolderIsRead() throws IOException {
        FakeNode node = new FakeNode();
        node.repository = List.of();

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(node.sent).doesNotContain("W15", "C3");
        assertThat(node.reads).noneMatch(read -> read.endsWith("-P"));
        assertThat(outcome.notes()).contains(
            "W15 not applicable: the tag reached no repository file");
        assertThat(outcome.stop()).isEmpty();
    }

    @Test
    void aStatedFolderWithoutTaggedFileStopsBeforeW15() throws IOException {
        FakeNode node = new FakeNode();

        Outcome outcome = ManageTagsLiveTestSupport.run(node,
            new Inputs(TAG, G1, G2, Optional.of("/Root/owner1/other"), USER, OWNER));

        assertThat(outcome.stop()).contains(new Stop("W15", "FOLDER NOT TAGGED"));
        assertThat(node.sent).containsExactly("S1", "S2", "W13", "S3", "W14", "S4", "C1", "C2");
    }

    @Test
    void aFailedSetActiveIsUndoneByTheCleanup() throws IOException {
        FakeNode node = new FakeNode();
        node.failing.put("W17", "TIMEOUT");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(outcome.stop()).contains(new Stop("W17", "TIMEOUT"));
        assertThat(node.sent).endsWith("W17", "C1", "C2", "C4");
        assertThat(node.reads).contains("V-A");
    }

    @Test
    void whatRemainsAfterTheCleanupFailsTheCheckForTheOwner() throws IOException {
        FakeNode node = new FakeNode();
        node.noEffect.add("C2");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(outcome.remains()).containsExactly("g2 1 workflow, 1 tagged: workflow W-2 v2",
            "by tag groups g2, 1 versions, 0 repository files tagged");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.assertOutcome(outcome))
            .hasMessageContaining("ran all steps")
            .hasMessageContaining("the owner must remove it");
    }

    @Test
    void aFileStillTaggedInAnyFolderARunSawFailsTheCheckAfterTheCleanup() throws IOException {
        FakeNode node = new FakeNode();
        node.repository = List.of(FILE, "/Root/owner1/other/b.xsd");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        assertThat(node.sentForms.get("W15"))
            .isEqualTo("tag --tagRemove 'l4-test' --tagRepositoryPath '/Root/owner1/other'");
        assertThat(node.sent).endsWith("W18", "C1", "C2");
        assertThat(node.reads).endsWith("V-A", "V-F1-P", "V-F2-P");
        assertThat(node.records.get("V-F1")).isEqualTo(
            "folder ~/other 2 files, 0 repository files tagged");
        assertThat(outcome.remains()).containsExactly(
            "folder ~/xsd 2 files, 1 repository files tagged: " + SHOWN + " v1.0");
        assertThatThrownBy(() -> ManageTagsLiveTestSupport.assertOutcome(outcome))
            .hasMessageContaining("the owner must remove it").hasMessageContaining(SHOWN);
    }

    @Test
    void anExceptionAfterAStepWasSentCountsEverythingUnknownSoTheCleanupRuns()
        throws IOException {
        FakeNode node = new FakeNode();
        node.throwing.add("S1");

        Outcome outcome = ManageTagsLiveTestSupport.run(node, DERIVED);

        // the last read (B0) showed nothing tagged, yet S1 took effect before the exception
        assertThat(outcome.stop()).contains(new Stop("S1", "IllegalStateException"));
        assertThat(node.sent).containsExactly("S1", "C1", "C2");
        assertThat(outcome.remains()).isEmpty();
        assertThat(outcome.cleanup()).containsExactly("C1 done", "C2 done");
    }

    // --- records ---------------------------------------------------------------------------------

    @Test
    void aCommandIsRecordedOwnerOnlyAndItsLineShowsNoOutput() throws IOException {
        Run run = new Run(false);
        run.stdout().writeBytes(RAW_LINE.getBytes(StandardCharsets.UTF_8));
        run.finished(0);
        Read read = Read.history("S1-after", 1);

        String line = ManageTagsLiveTestSupport.recordCli(PrivateFiles.forHost(), temp, read,
            List.of(run), Optional.of(ErrorCode.TIMEOUT));
        String state = ManageTagsLiveTestSupport.recordState(PrivateFiles.forHost(), temp,
            "S1-tag-move-g1", "answer done");

        assertThat(line).isEqualTo("[live] T055 S1-after-H1: exit 0, stdout " + RAW_LINE.length()
            + " bytes, stderr 0 bytes, error TIMEOUT");
        assertThat(state).isEqualTo("[live] T055 S1-tag-move-g1: answer done");
        assertThat(temp.resolve("S1-after-H1-history-g1.stdout")).hasContent(RAW_LINE);
        assertThat(temp.resolve("S1-after-H1-history-g1.error")).hasContent("TIMEOUT");
        assertThat(temp.resolve("S1-tag-move-g1.state")).hasContent("answer done");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(
                temp.resolve("S1-after-H1-history-g1.stdout")))).isEqualTo("rw-------");
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void inputs(String path, String user) {
        env.put(ManageTagsLiveTestSupport.TAG_VARIABLE, TAG);
        env.put(ManageTagsLiveTestSupport.GROUPS_VARIABLE, G1 + ", " + G2);
        env.put(ManageTagsLiveTestSupport.REPOSITORY_PATH_VARIABLE, path);
        env.put(ManageTagsLiveTestSupport.USER_VARIABLE, user);
    }

    private static byte[] entry(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static TagExport export(Set<String> groups, Map<String, String> files) {
        return new TagExport(new TreeSet<>(groups), groups.size(), new TreeMap<>(files));
    }

    /**
     * A node whose tags act as scoped: a group-scoped form changes that group only (never a
     * repository file), the folder form the files below the folder only; {@code --tagMove} also
     * tags the {@link #repository} files. W16 is refused with exit 1. Steps in {@link #noEffect}
     * answer but change nothing; a step in {@link #throwing} throws after its effect.
     */
    private static final class FakeNode implements ManageTagsLiveTestSupport.Node {

        boolean g1;
        boolean g2;
        List<String> repository = List.of(FILE);
        final Set<String> fileTags = new TreeSet<>();
        final Set<String> throwing = new HashSet<>();
        int workflows = 1;
        ActiveTag active = ActiveTag.NONE;
        String unknownRead = "";
        final Map<String, String> failing = new HashMap<>();
        final Set<String> noEffect = new HashSet<>();
        final List<String> sent = new ArrayList<>();
        final Map<String, String> sentForms = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        final Map<String, String> records = new LinkedHashMap<>();

        @Override
        public Optional<GroupTags> group(String label, int which) {
            String id = Read.history(label, which).id();
            reads.add(id);
            if (id.equals(unknownRead)) {
                return Optional.empty();
            }
            boolean tagged = which == 1 ? g1 : g2;
            return Optional.of(new GroupTags(workflows, tagged
                ? List.of(new TaggedVersion(TaggedVersion.Kind.WORKFLOW, "W-" + which, 2))
                : List.of()));
        }

        @Override
        public Optional<TagExport> byTag(String label) {
            reads.add(Read.byTag(label).id());
            Set<String> groups = new TreeSet<>();
            if (g1) {
                groups.add(G1);
            }
            if (g2) {
                groups.add(G2);
            }
            return Optional.of(groups.isEmpty() ? TagExport.NONE
                : export(groups, versions(fileTags)));
        }

        @Override
        public Optional<FolderTags> folder(String label, String path) {
            reads.add(Read.folder(label).id());
            return Optional.of(new FolderTags(2, new TreeMap<>(versions(fileTags.stream()
                .filter(file -> file.startsWith(path + "/")).toList()))));
        }

        private static Map<String, String> versions(Collection<String> files) {
            Map<String, String> versions = new TreeMap<>();
            files.forEach(file -> versions.put(file, "1.0"));
            return versions;
        }

        @Override
        public Optional<ActiveTag> activeTag(String label) {
            reads.add(Read.user(label).id());
            return Optional.of(active);
        }

        @Override
        public Answer run(Step step) {
            sent.add(step.id());
            sentForms.put(step.id(), step.form());
            if (failing.containsKey(step.id())) {
                return new Answer(Optional.of(failing.get(step.id())), false);
            }
            if (step.action() instanceof Unlisted) {
                return new Answer(Optional.of("exit 1"), true);
            }
            if (noEffect.contains(step.id())) {
                return Answer.DONE;
            }
            switch (step.action()) {
                case TagGroup tag -> {
                    if (tag.group().equals(G1)) {
                        g1 = true;
                    } else {
                        g2 = true;
                    }
                    fileTags.addAll(repository);
                }
                case Removal removal -> {
                    if (removal.scope() instanceof TagScope.DiagramGroup group) {
                        if (group.group().equals(G1)) {
                            g1 = false;
                        } else {
                            g2 = false;
                        }
                    } else if (removal.scope() instanceof TagScope.RepositoryPath path) {
                        fileTags.removeIf(file -> file.startsWith(path.path() + "/"));
                    }
                }
                case SetActive set -> active = ActiveTag.TEST_TAG;
                case RemoveActive remove -> active = ActiveTag.NONE;
                default -> throw new IllegalStateException(step.id());
            }
            if (throwing.contains(step.id())) {
                throw new IllegalStateException(step.id());
            }
            return Answer.DONE;
        }

        @Override
        public void record(String stem, String text) {
            // as the live node, which creates each .state file once
            if (records.put(stem, text) != null) {
                throw new IllegalStateException(stem + " recorded twice");
            }
        }
    }
}
