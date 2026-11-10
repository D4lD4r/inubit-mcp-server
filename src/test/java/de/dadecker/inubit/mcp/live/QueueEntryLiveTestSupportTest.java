package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.dadecker.inubit.mcp.adapter.cli.FakeProcessLauncher;
import de.dadecker.inubit.mcp.adapter.cli.ProcessLauncher.LaunchSpec;
import de.dadecker.inubit.mcp.adapter.cli.queue.QueueEntryVersionProfile;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.domain.model.EntryPart;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.ProcessState;
import de.dadecker.inubit.mcp.domain.model.QueueEntries;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRef;
import de.dadecker.inubit.mcp.domain.model.QueueEntryRow;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.domain.port.PrivateFileSystem;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.Checked;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.Entry;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.HelperRecorder;
import de.dadecker.inubit.mcp.live.QueueEntryLiveTestSupport.HelperRun;
import de.dadecker.inubit.mcp.testing.GitFreeTempDir;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import de.dadecker.inubit.mcp.testing.TestSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature 020 T033 (research D-21): the offline parts of {@link QueueEntryLiveTest} — the
 * parsing of {@code INUBIT_LIVE_QUEUE_ENTRY}, the record directory and its owner-only files, the
 * rows-before/after check, the log check and the recording of the helper run. A plain unit test
 * (not tagged {@code live}): fake environments, temporary directories and a fake helper; nothing
 * is contacted.
 */
class QueueEntryLiveTestSupportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FILE = "Module-0001(1001)@100000001@00000000";
    private static final Instant SINCE = Instant.parse("2026-01-02T03:04:05Z");

    @TempDir(factory = GitFreeTempDir.class)
    Path temp;

    private final Map<String, String> env = new HashMap<>();

    // --- INUBIT_LIVE_QUEUE_ENTRY ---------------------------------------------------------------

    @Test
    void withoutTheEntryVariableTheLiveTestIsSkipped() {
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.entry(env))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("INUBIT_LIVE_QUEUE_ENTRY");
        env.put("INUBIT_LIVE_QUEUE_ENTRY", "  ");
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.entry(env))
            .isInstanceOf(TestAbortedException.class);
    }

    @Test
    void aProcessIdAloneNamesTheProcess() {
        env.put("INUBIT_LIVE_QUEUE_ENTRY", "1234");

        assertThat(QueueEntryLiveTestSupport.entry(env))
            .isEqualTo(new Entry("1234", Optional.empty()));
    }

    @Test
    void aProcessIdWithAFileNameNamesOneEntry() {
        env.put("INUBIT_LIVE_QUEUE_ENTRY", "1234/" + FILE);

        assertThat(QueueEntryLiveTestSupport.entry(env))
            .isEqualTo(new Entry("1234", Optional.of(FILE)));
    }

    @Test
    void anInvalidProcessIdIsRefusedNamingTheVariableAndItsForm() {
        for (String value : List.of("0", "01234", "abc", "-1", "12345678901234567890", "12 34",
            "/" + FILE, "1234 ")) {
            env.put("INUBIT_LIVE_QUEUE_ENTRY", value);
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.entry(env)).as(value)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_QUEUE_ENTRY")
                .hasMessageContaining("<processId>[/<fileName>]");
        }
    }

    @Test
    void anInvalidFileNameIsRefusedWithoutEchoingIt() {
        for (String fileName : List.of("", "a/b", "Module\\0001", "Module\u00070001",
            "Module\u00850001", "x".repeat(256))) {
            env.put("INUBIT_LIVE_QUEUE_ENTRY", "1234/" + fileName);
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.entry(env)).as(fileName)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_QUEUE_ENTRY")
                .satisfies(e -> {
                    if (!fileName.isEmpty()) {
                        assertThat(e.getMessage()).doesNotContain(fileName);
                    }
                });
        }
    }

    /** The file names accepted are exactly those the tool's input schema accepts. */
    @Test
    void theFileNameRuleAgreesWithTheToolInputSchema() throws IOException {
        Pattern schema;
        try (InputStream in = getClass().getResourceAsStream(
            "/schemas/read_queue_entry.input.json")) {
            schema = Pattern.compile(JSON.readTree(in).path("properties").path("fileName")
                .path("pattern").asString());
        }
        for (String fileName : List.of(FILE, "x", "x".repeat(255), "x".repeat(256),
            "with space", "Ü-0001", "a/b", "a\\b", "a\u0000b", "a\u001Fb", "a\u007Fb",
            "a\u009Fb", "a\u00A0b", "a\tb")) {
            env.put("INUBIT_LIVE_QUEUE_ENTRY", "1/" + fileName);
            boolean accepted;
            try {
                QueueEntryLiveTestSupport.entry(env);
                accepted = true;
            } catch (AssertionError e) {
                accepted = false;
            }
            assertThat(accepted).as(fileName).isEqualTo(schema.matcher(fileName).matches());
        }
    }

    @Test
    void theNegativeCaseUsesAFictitiousButValidFileName() {
        assertThat(QueueEntryLiveTestSupport.NOT_FOUND_FILE_NAME)
            .isEqualTo("Module-0000(0)@0@00000000");
        assertThat(QueueEntryRow.isValidFileName(QueueEntryLiveTestSupport.NOT_FOUND_FILE_NAME))
            .isTrue();
    }

    // --- INUBIT_LIVE_RECORD_DIR ----------------------------------------------------------------

    @Test
    void withoutTheRecordDirectoryNothingIsRecorded() throws IOException {
        Path repository = temp.resolve("repository");

        assertThat(QueueEntryLiveTestSupport.recordDirectory(env, repository)).isEmpty();
        env.put("INUBIT_LIVE_RECORD_DIR", " ");
        assertThat(QueueEntryLiveTestSupport.recordDirectory(env, repository)).isEmpty();
    }

    @Test
    void aRelativeRecordDirectoryIsRefused() {
        env.put("INUBIT_LIVE_RECORD_DIR", "records");

        assertThatThrownBy(() -> QueueEntryLiveTestSupport.recordDirectory(env,
            temp.resolve("repository")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
            .hasMessageContaining("absolute");
    }

    @Test
    void aRecordDirectoryInsideTheRepositoryIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        for (Path inside : List.of(repository, repository.resolve("target/records"))) {
            assertRefusedAsInsideTheRepository(inside, repository);
        }
    }

    @Test
    void aRecordDirectoryReachingTheRepositoryThroughASymbolicLinkIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path link = symbolicLink(temp.resolve("link"), repository);
        Path parentLink = symbolicLink(temp.resolve("parent-link"), temp);

        for (Path inside : List.of(link, link.resolve("records"), link.resolve("records/live"),
            parentLink.resolve("repository/records"))) {
            assertRefusedAsInsideTheRepository(inside, repository);
        }
    }

    /** {@code link/..} is the link target's parent, so lexical normalizing would hide it. */
    @Test
    void aRecordDirectoryWithDotDotSegmentsIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path inner = Files.createDirectories(repository.resolve("src"));
        symbolicLink(temp.resolve("link-into-repo"), inner);

        for (String path : List.of(temp + "/missing/../link-into-repo/records",
            temp + "/link-into-repo/../records", temp + "/outside/../outside",
            temp + "/repository/x/../records", temp + "/other/../repository/records")) {
            Map<String, String> env = Map.of("INUBIT_LIVE_RECORD_DIR", path);
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.recordDirectory(env, repository))
                .as(path)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("must not contain .. segments");
        }
    }

    @Test
    void aCaseVariantOfTheRepositoryIsRefusedOnACaseInsensitiveFileSystem() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path variant = temp.resolve("REPOSITORY");
        assumeTrue(Files.isDirectory(variant), "the file system is case-sensitive");

        for (Path inside : List.of(variant, variant.resolve("records"),
            temp.resolve("Repository/Target/records"))) {
            assertRefusedAsInsideTheRepository(inside, repository);
        }
    }

    /** A recording inside any git working tree could be committed with it. */
    @Test
    void aRecordDirectoryInsideAnotherGitWorkingTreeIsRefused() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path clone = Files.createDirectories(temp.resolve("clone/.git")).getParent();
        Path worktree = Files.createDirectories(temp.resolve("worktree"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../clone/.git/worktrees/w\n");

        for (Path inside : List.of(clone, clone.resolve("records/live"),
            worktree.resolve("records"), symbolicLink(temp.resolve("clone-link"), clone)
                .resolve("records"))) {
            env.put("INUBIT_LIVE_RECORD_DIR", inside.toString());
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.recordDirectory(env, repository))
                .as(inside.toString())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
                .hasMessageContaining("git working tree");
        }
    }

    @Test
    void aRecordDirectoryOutsideTheRepositoryIsUsedAsItsRealPath() throws IOException {
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Path real = temp.toRealPath();

        env.put("INUBIT_LIVE_RECORD_DIR", temp.resolve("records").toString());
        assertThat(QueueEntryLiveTestSupport.recordDirectory(env, repository))
            .contains(real.resolve("records"));
        env.put("INUBIT_LIVE_RECORD_DIR", temp.resolve("repository-records").toString());
        assertThat(QueueEntryLiveTestSupport.recordDirectory(env, repository))
            .contains(real.resolve("repository-records"));
        Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
        env.put("INUBIT_LIVE_RECORD_DIR",
            symbolicLink(temp.resolve("elsewhere-link"), elsewhere).resolve("records").toString());
        assertThat(QueueEntryLiveTestSupport.recordDirectory(env, repository))
            .contains(real.resolve("elsewhere/records"));
    }

    @Test
    void eachRunGetsItsOwnOwnerOnlyDirectoryBelowTheRecordDirectory() throws IOException {
        Path records = temp.resolve("records/live");

        Path first = QueueEntryLiveTestSupport.runDirectory(PrivateFiles.forHost(), records);
        Path second = QueueEntryLiveTestSupport.runDirectory(PrivateFiles.forHost(), records);

        assertThat(first.getParent()).isEqualTo(records);
        assertThat(second.getParent()).isEqualTo(records);
        assertThat(first).isNotEqualTo(second);
        assertOwnerOnlyDirectory(records);
        assertOwnerOnlyDirectory(first);
    }

    @Test
    void theHelperRunIsWrittenAsOwnerOnlyCaseFiles() throws IOException {
        Path run = QueueEntryLiveTestSupport.runDirectory(PrivateFiles.forHost(),
            temp.resolve("records"));
        HelperRun helper = new HelperRun();
        helper.frames().write("#QE1:0 end\n".getBytes(StandardCharsets.UTF_8));
        helper.finished(4, Optional.of(new byte[] {1, 2}), Optional.of("<w/>".getBytes(
            StandardCharsets.UTF_8)));

        QueueEntryLiveTestSupport.record(PrivateFiles.forHost(), run, "error_entry", helper);

        assertThat(run.resolve("error_entry.frames")).hasContent("#QE1:0 end\n");
        assertThat(run.resolve("error_entry.exit")).hasContent("4\n");
        assertThat(run.resolve("error_entry.data")).hasBinaryContent(new byte[] {1, 2});
        assertThat(run.resolve("error_entry.wfsp")).hasContent("<w/>");
        try (Stream<Path> files = Files.list(run)) {
            for (Path file : files.toList()) {
                assertOwnerOnlyFile(file);
            }
        }
    }

    @Test
    void aPartTheHelperDidNotWriteGetsNoFile() throws IOException {
        Path run = QueueEntryLiveTestSupport.runDirectory(PrivateFiles.forHost(),
            temp.resolve("records"));
        HelperRun helper = new HelperRun();
        helper.finished(0, Optional.empty(), Optional.empty());

        QueueEntryLiveTestSupport.record(PrivateFiles.forHost(), run, "not_found_file", helper);

        try (Stream<Path> files = Files.list(run)) {
            assertThat(files.map(file -> file.getFileName().toString()))
                .containsExactlyInAnyOrder("not_found_file.frames", "not_found_file.exit");
        }
    }

    /** Without POSIX permissions or Windows access lists the content is not written at all. */
    @Test
    void aFileSystemWithoutOwnerOnlyFilesIsRefusedAndNothingIsWritten() throws IOException {
        Path records = temp.resolve("records");

        assertThatThrownBy(() -> QueueEntryLiveTestSupport.runDirectory(unsupported(), records))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
            .hasMessageContaining("nothing is recorded");
        assertThat(records).doesNotExist();
    }

    // --- rows before and after -----------------------------------------------------------------

    @Test
    void anUnchangedEntryPasses() {
        QueueEntryRow before = row(ProcessState.ERROR, SINCE, FILE, 42);

        assertThatCode(() -> QueueEntryLiveTestSupport.assertUnchanged(before,
            entries(row(ProcessState.ERROR, SINCE, FILE, 42)))).doesNotThrowAnyException();
    }

    @Test
    void theRowIsFoundByItsFileNameAmongTheRowsOfTheProcess() {
        QueueEntries entries = entries(row(ProcessState.ERROR, SINCE, "Module-0001(1)@1@1", 1),
            row(ProcessState.ERROR, SINCE, FILE, 42));

        assertThat(QueueEntryLiveTestSupport.row(entries, FILE)).map(QueueEntryRow::fileSize)
            .contains(42L);
        assertThat(QueueEntryLiveTestSupport.row(entries, "Module-0001(2)@2@2")).isEmpty();
    }

    @Test
    void aChangedStateStartTimeOrSizeFailsNamingTheFieldButNoValue() {
        QueueEntryRow before = row(ProcessState.ERROR, SINCE, FILE, 42);
        Map<String, QueueEntryRow> changed = Map.of(
            "state", row(ProcessState.QUEUED, SINCE, FILE, 42),
            "startTime", row(ProcessState.ERROR, SINCE.plusSeconds(1), FILE, 42),
            "fileSize", row(ProcessState.ERROR, SINCE, FILE, 43));
        changed.forEach((field, after) -> assertThatThrownBy(
            () -> QueueEntryLiveTestSupport.assertUnchanged(before, entries(after))).as(field)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining(field)
            .hasMessageNotContaining(FILE)
            .hasMessageNotContaining("43")
            .hasMessageNotContaining("QUEUED"));
    }

    @Test
    void anEntryWhoseFileNameIsGoneFailsAsLeftOrRenamed() {
        QueueEntryRow before = row(ProcessState.ERROR, SINCE, FILE, 42);

        for (QueueEntries after : List.of(entries(), entries(row(ProcessState.ERROR, SINCE,
            "Module-0001(1001)@100000001@00000001", 42)))) {
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertUnchanged(before, after))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fileName")
                .hasMessageNotContaining(FILE);
        }
    }

    @Test
    void otherFieldsAreNotCompared() {
        QueueEntryRow before = row(ProcessState.ERROR, SINCE, FILE, 42);
        QueueEntryRow after = new QueueEntryRow("1001", Optional.of("g-1"), ProcessState.ERROR,
            "ERROR", SINCE, "Workflow-0001", Optional.of("Module-0002"), Optional.of("Throw"),
            FILE, 42, new TreeMap<>(Map.of("userDefined1", "retry later")));

        assertThatCode(() -> QueueEntryLiveTestSupport.assertUnchanged(before, entries(after)))
            .doesNotThrowAnyException();
    }

    // --- returned values and the log -----------------------------------------------------------

    @Test
    void theReturnedValuesAreTheDataLinesAndTheUnmaskedUserVariableValues() {
        JsonNode result = JSON.readTree("""
            {"data": {"available": true, "kind": "text",
                      "text": "<order id=\\"Order-0001\\">\\n  <qty>1</qty>\\n</order>"},
             "variables": {"available": true, "items": [
               {"name": "orderId", "system": false, "kind": "text", "value": "Order-0001-A"},
               {"name": "apiToken", "system": false, "masked": true, "kind": "text",
                "value": "${secret:variables.apiToken}"},
               {"name": "customer", "system": false, "kind": "text", "value": "globex"},
               {"name": "four", "system": false, "kind": "text", "value": "acme"},
               {"name": "short", "system": false, "kind": "text", "value": "abc"},
               {"name": "ibis.workflow", "system": true, "kind": "text",
                "value": "Workflow-0001"},
               {"name": "blob", "system": false, "kind": "binary"}]}}
            """);

        assertThat(QueueEntryLiveTestSupport.returnedValues(result))
            .containsExactlyInAnyOrder(substring("<order id=\"Order-0001\">"),
                substring("<qty>1</qty>"), substring("Order-0001-A"), token("globex"),
                token("acme"));
    }

    @Test
    void aBinaryEntryHasNoTextToCheck() {
        JsonNode result = JSON.readTree("""
            {"data": {"available": true, "kind": "binary", "detected": "ZIP"},
             "variables": {"available": false, "reason": "not found"}}
            """);

        assertThat(QueueEntryLiveTestSupport.returnedValues(result)).isEmpty();
    }

    @Test
    void aLogWithoutTheValuesPasses() {
        assertThatCode(() -> QueueEntryLiveTestSupport.assertNotLogged(
            List.of(substring("Order-0001-A"), token("acme")),
            List.of("test/node1: queue-entry helper SOAP_81", "acmeCorp", "acme_1")))
            .doesNotThrowAnyException();
    }

    @Test
    void aLoggedValueFailsWithoutQuotingIt() {
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertNotLogged(
            List.of(substring("Order-0001-A"), substring("<qty>1</qty>"), token("globex")),
            List.of("read Order-0001-A from the entry", "customer=globex;")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("2 of 3 returned values")
            .hasMessageNotContaining("Order-0001-A")
            .hasMessageNotContaining("globex");
    }

    /** A token occurrence is one not adjacent to a letter, digit or underscore. */
    @Test
    void aShortValueOccursOnlyAsAToken() {
        Checked acme = token("acme");

        for (String text : List.of("acme", "acme/node1", "[acme]", "x acme y", "\"acme\"",
            "id=acme;", "acme\n")) {
            assertThat(acme.occursIn(text)).as(text).isTrue();
        }
        for (String text : List.of("acmeCorp", "theacme", "acme_1", "acme1", "Acme", "äacme")) {
            assertThat(acme.occursIn(text)).as(text).isFalse();
        }
        assertThat(token("a.b").occursIn("xa.bx")).isFalse();
        assertThat(token("a.b").occursIn("x a.b x")).isTrue();
        assertThat(token("a.b").occursIn("x aXb x")).isFalse();
        assertThat(substring("Order-0001-A").occursIn("xOrder-0001-Ax")).isTrue();
    }

    // --- masked variables and known secrets ----------------------------------------------------

    @Test
    void theOriginalValuesOfMaskedVariablesAreTakenFromTheRawVariablesFile() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "${secret:variables.pw}");
        byte[] wfsp = wfsp("Order-0001", "s3cr3t-token-0001", "q7");

        assertThat(QueueEntryLiveTestSupport.maskedValues(result, Optional.of(wfsp)))
            .containsExactlyInAnyOrder(substring("s3cr3t-token-0001"), token("q7"));
    }

    /** Beyond the 200 cap the file holds more variables than the result items. */
    @Test
    void theRawFileIsMatchedAgainstTotalCountNotAgainstTheReturnedItems() {
        JsonNode result = JSON.readTree("""
            {"variables": {"available": true, "totalCount": 4, "returnedCount": 3,
             "truncated": true, "items": [
               {"name": "orderId", "system": false, "kind": "text", "value": "Order-0001"},
               {"name": "apiToken", "system": false, "masked": true, "kind": "text",
                "value": "${secret:variables.apiToken}"},
               {"name": "pw", "system": false, "kind": "text", "value": "plain-0001"}]}}
            """);
        byte[] wfsp = wfsp("Order-0001", "s3cr3t-token-0001", "plain-0001", "extra-0001");

        assertThat(QueueEntryLiveTestSupport.maskedValues(result, Optional.of(wfsp)))
            .containsExactly(substring("s3cr3t-token-0001"));
    }

    @Test
    void maskedValuesAbsentFromLogAndResultPass() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "${secret:variables.pw}");

        assertThatCode(() -> QueueEntryLiveTestSupport.assertMaskedValuesAbsent(result,
            Optional.of(wfsp("Order-0001", "s3cr3t-token-0001", "q7")),
            List.of("test/node1: read Order-0001", "q77 q7x xq7")))
            .doesNotThrowAnyException();
    }

    /** Any length: a short secret is still checked, as a token. */
    @Test
    void aMaskedValueInTheLogFailsWithoutQuotingItWhateverItsLength() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "${secret:variables.pw}");
        byte[] wfsp = wfsp("Order-0001", "tok5", "q7");

        for (String line : List.of("token tok5 sent", "pw=q7;")) {
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertMaskedValuesAbsent(result,
                Optional.of(wfsp), List.of(line))).as(line)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 of 2 masked variable values")
                .hasMessageContaining("the captured log")
                .hasMessageNotContaining("result")
                .hasMessageNotContaining("tok5")
                .hasMessageNotContaining("q7");
        }
    }

    @Test
    void aMaskedValueInTheResultFails() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "the token is q7");
        byte[] wfsp = wfsp("Order-0001", "s3cr3t-token-0001", "q7");

        assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertMaskedValuesAbsent(result,
            Optional.of(wfsp), List.of()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("1 of 2 masked variable values")
            .hasMessageContaining("the result")
            .hasMessageNotContaining("log")
            .hasMessageNotContaining("q7");
    }

    @Test
    void anEmptyMaskedValueHasNothingToCheck() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "${secret:variables.pw}");

        assertThat(QueueEntryLiveTestSupport.maskedValues(result,
            Optional.of(wfsp("Order-0001", "", " ")))).isEmpty();
    }

    @Test
    void withoutMaskedVariablesNoVariablesFileIsNeeded() {
        JsonNode result = JSON.readTree("""
            {"variables": {"available": true, "totalCount": 1, "items": [
               {"name": "orderId", "system": false, "kind": "text", "value": "Order-0001"}]}}
            """);

        assertThat(QueueEntryLiveTestSupport.maskedValues(result, Optional.empty())).isEmpty();
    }

    @Test
    void maskedVariablesWithoutAMatchingVariablesFileFail() {
        JsonNode result = maskedResult("${secret:variables.apiToken}", "${secret:variables.pw}");

        for (Optional<byte[]> wfsp : List.of(Optional.<byte[]>empty(),
            Optional.of("<Properties>".getBytes(StandardCharsets.UTF_8)),
            Optional.of(wfsp("Order-0001", "s3cr3t-token-0001")))) {
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.maskedValues(result, wfsp))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("masked");
        }
    }

    @Test
    void aSecretKnownToTheScrubberFailsInTheLogOrTheResultWhateverItsLength() {
        SecretScrubber scrubber = new SecretScrubber();
        scrubber.register(TestSecrets.CONFIGURED_PASSWORD);
        scrubber.register("pw");
        JsonNode clean = JSON.readTree("""
            {"data": {"text": "<order/>"}}
            """);

        assertThatCode(() -> QueueEntryLiveTestSupport.assertNoKnownSecret(scrubber, clean,
            List.of("test/node1: login ok"))).doesNotThrowAnyException();
        for (String line : List.of("password " + TestSecrets.CONFIGURED_PASSWORD, "x=pw")) {
            assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertNoKnownSecret(scrubber,
                clean, List.of("ok", line))).as(line)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 captured log line")
                .hasMessageNotContaining(TestSecrets.CONFIGURED_PASSWORD);
        }
        JsonNode leaked = JSON.readTree("""
            {"data": {"text": "<order>%s</order>"}}
            """.formatted(TestSecrets.CONFIGURED_PASSWORD));
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.assertNoKnownSecret(scrubber, leaked,
            List.of()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("result")
            .hasMessageNotContaining(TestSecrets.CONFIGURED_PASSWORD);
    }

    @Test
    void aReadWithoutAHelperRunFailsClearly() {
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.readRun(List.of()))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("no run of the queue-entry helper")
            .hasMessageContaining("error_entry");
        HelperRun first = new HelperRun();
        HelperRun last = new HelperRun();
        assertThat(QueueEntryLiveTestSupport.readRun(List.of(first, last))).isSameAs(last);
    }

    // --- recording the helper run ---------------------------------------------------------------

    /** The real adapter through the recorder: its raw output is kept before it is deleted. */
    @Test
    void theRecorderKeepsTheHelpersRawOutputButNotItsRequest() throws IOException {
        try (QueueEntryVersionProfile profile = new QueueEntryVersionProfile(temp)) {
            EffectiveNodeConfig server = profile.node("test/n81", VersionSetting.V8_1).build();
            FakeProcessLauncher helper = QueueEntryVersionProfile.helperReading();
            HelperRecorder recorder = new HelperRecorder(helper);

            profile.adapter(server, recorder).read(new QueueEntryRef(server.id(),
                "Workflow-0001", FILE, 0), InubitVersion.parse("8.1.17").orElseThrow(),
                EnumSet.allOf(EntryPart.class));

            assertThat(recorder.runs()).hasSize(1);
            HelperRun run = recorder.runs().get(0);
            String frames = run.frames().toString(StandardCharsets.UTF_8);
            assertThat(frames).contains("#QE1:", " end");
            assertThat(run.exit()).contains(0);
            assertThat(run.data()).contains(QueueEntryVersionProfile.DATA);
            assertThat(run.variables()).hasValueSatisfying(bytes -> assertThat(new String(bytes,
                StandardCharsets.UTF_8)).isEqualTo(QueueEntryVersionProfile.WFSP));
            TestSecrets.assertNoLeak(frames);
        }
    }

    @Test
    void otherProcessesAreNotRecorded() throws IOException {
        FakeProcessLauncher startCli = FakeProcessLauncher.of("ok", "", 0);
        HelperRecorder recorder = new HelperRecorder(startCli);

        recorder.launch(new LaunchSpec(List.of("StartCLI.sh", "-version"), Map.of(), temp));

        assertThat(startCli.launchCount()).isOne();
        assertThat(recorder.runs()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------

    private void assertRefusedAsInsideTheRepository(Path inside, Path repository) {
        env.put("INUBIT_LIVE_RECORD_DIR", inside.toString());
        assertThatThrownBy(() -> QueueEntryLiveTestSupport.recordDirectory(env, repository))
            .as(inside.toString())
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("INUBIT_LIVE_RECORD_DIR")
            .hasMessageContaining("outside the repository");
    }

    /** {@code link} to {@code target}; skips the test where links cannot be created. */
    private static Path symbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            throw new TestAbortedException("symbolic links cannot be created here", e);
        }
    }

    private static Checked substring(String value) {
        return new Checked(value, false);
    }

    private static Checked token(String value) {
        return new Checked(value, true);
    }

    /** A result with the variables orderId, apiToken (masked) and pw (masked), in that order. */
    private static JsonNode maskedResult(String apiTokenValue, String pwValue) {
        return JSON.readTree("""
            {"data": {"available": true, "kind": "text", "text": "<order>Order-0001</order>"},
             "variables": {"available": true, "totalCount": 3, "items": [
               {"name": "orderId", "system": false, "kind": "text", "value": "Order-0001"},
               {"name": "apiToken", "system": false, "masked": true, "kind": "text",
                "value": "%s"},
               {"name": "pw", "system": false, "masked": true, "kind": "text",
                "value": "%s"}]}}
            """.formatted(apiTokenValue, pwValue));
    }

    /** A {@code .wfsp} with orderId, apiToken, pw and extra, as far as {@code values} go. */
    private static byte[] wfsp(String... values) {
        List<String> names = List.of("orderId", "apiToken", "pw", "extra");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<Properties version=\"4.1\">");
        for (int i = 0; i < values.length; i++) {
            xml.append("<Property name=\"").append(names.get(i)).append("\">").append(values[i])
                .append("</Property>");
        }
        return xml.append("</Properties>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static QueueEntryRow row(ProcessState state, Instant since, String fileName,
        long size) {
        return new QueueEntryRow("1001", Optional.empty(), state, state.name(), since,
            "Workflow-0001", Optional.of("Module-0001"), Optional.empty(), fileName, size,
            new TreeMap<>());
    }

    private static QueueEntries entries(QueueEntryRow... rows) {
        return new QueueEntries(List.of(rows), rows.length);
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void assertOwnerOnlyDirectory(Path directory) throws IOException {
        if (posix()) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                .as(directory.toString()).isEqualTo("rwx------");
        } else {
            assertThat(PrivateFiles.forHost().check(directory)).as(directory.toString())
                .isEmpty();
        }
    }

    private static void assertOwnerOnlyFile(Path file) throws IOException {
        if (posix()) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .as(file.toString()).isEqualTo("rw-------");
        } else {
            assertThat(PrivateFiles.forHost().check(file)).as(file.toString()).isEmpty();
        }
    }

    /** A file system as {@code OsPrivateFileSystem} on a host with neither POSIX nor ACLs. */
    private static PrivateFileSystem unsupported() {
        return new PrivateFileSystem() {
            @Override
            public Path createDirectory(Path directory) {
                throw unsupportedFiles();
            }

            @Override
            public boolean createDirectories(Path directory) {
                throw unsupportedFiles();
            }

            @Override
            public Path createTempDirectory(Path parent, String prefix) {
                throw unsupportedFiles();
            }

            @Override
            public Path createFile(Path file) {
                throw unsupportedFiles();
            }

            @Override
            public void tightenOnPosix(Path path) {
            }

            @Override
            public List<String> check(Path path) {
                return List.of();
            }

            @Override
            public boolean ownedByCurrentUser(Path path) {
                return true;
            }
        };
    }

    private static UnsupportedOperationException unsupportedFiles() {
        return new UnsupportedOperationException(
            "neither POSIX permissions nor access lists are supported");
    }
}
