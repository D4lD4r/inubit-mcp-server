package de.dadecker.inubit.mcp.application.knowledge;

import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.BOUND;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.OLDER;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.all;
import static de.dadecker.inubit.mcp.application.knowledge.KnowledgeFixture.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Publisher;
import de.dadecker.inubit.mcp.application.knowledge.NoteSelector.ByName;
import de.dadecker.inubit.mcp.application.knowledge.Record.Mode;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.testing.FakeConfiguredSecrets;
import de.dadecker.inubit.mcp.testing.PrivateFiles;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Feature 019 T030: {@code KnowledgeService.record} (research D-10 to D-13) - the order of the
 * checks, the new text, history and commit point, the audit and the log line of every outcome.
 */
class KnowledgeServiceRecordTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:15:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000030");
    private static final String HELD = "S3cond-held-value";
    private static final String CLIENT = "claude-code/2.0";
    private static final String WRITER = "'assistant (" + CLIENT + ")'";

    @TempDir
    Path temp;

    private KnowledgeFixture fixture;
    private final RecordingAuditPort port = new RecordingAuditPort();
    private final FakeConfiguredSecrets secrets = FakeConfiguredSecrets.held(HELD);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private KnowledgeDirectory directory;
    private KnowledgeService service;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new KnowledgeFixture(temp);
        directory = new KnowledgeDirectory(fixture.root, PrivateFiles.forHost(), clock);
        service = service(directory);
        logs.start();
        logger().addAppender(logs);
        logger().setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logs);
        logger().setLevel(null);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(KnowledgeService.class);
    }

    private KnowledgeService service(KnowledgeDirectory on) {
        return new KnowledgeService(on, secrets, fixture.refs, BOUND,
            new KnowledgeService.Writing(PrivateFiles.forHost(), clock,
                new KnowledgeAudit.Dependencies(port, clock, "default", () -> ID)));
    }

    private static Record create(String name, String title, List<String> topics,
        String content) {
        return new Record(Optional.of(name), Optional.empty(), Mode.CREATE, Optional.of(title),
            Optional.of(topics), content, Optional.empty(), Optional.of(CLIENT));
    }

    private static Record append(String name, String section) {
        return new Record(Optional.of(name), Optional.empty(), Mode.APPEND, Optional.empty(),
            Optional.empty(), section, Optional.empty(), Optional.of(CLIENT));
    }

    private static Record replace(String name, String content, String token) {
        return new Record(Optional.of(name), Optional.empty(), Mode.REPLACE, Optional.empty(),
            Optional.empty(), content, Optional.of(token), Optional.of(CLIENT));
    }

    private static Record byRef(String ref, Mode mode, String content) {
        return new Record(Optional.empty(), Optional.of(ref), mode, Optional.empty(),
            Optional.empty(), content, Optional.empty(), Optional.of(CLIENT));
    }

    private static String block(String title, String topics, String source) {
        return "---\ntitle: '" + title + "'\ntopics: [" + topics + "]\nsource: " + source
            + "\nlastWriter: " + WRITER + "\nlastChange: '" + NOW + "'\n---\n";
    }

    private String token(String name) {
        return service.read(new ByName(name), 0).versionToken();
    }

    private String text(String relative) throws IOException {
        return Files.readString(fixture.root.resolve(relative));
    }

    private List<Path> versions(String noteFile) throws IOException {
        Path directory = fixture.root.resolve(".history").resolve(noteFile);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.sorted().toList();
        }
    }

    private List<AuditOutcome> outcomes() {
        return port.records.stream().map(AuditRecord::outcome).toList();
    }

    private List<String> recordLines() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(line -> line.startsWith("knowledge.record")).toList();
    }

    // US2 AS1: create, audit without content

    @Test
    void createWritesTheBlockAndTheContentAndAuditsWithoutContent() throws IOException {
        Recorded recorded = service.record(create("partners/acme", "ACME",
            List.of("edi", "o'brien"), "Uses EDIFACT.\n"));

        String expected = block("ACME", "'edi', 'o''brien'", WRITER) + "\nUses EDIFACT.\n";
        assertThat(text("partners/acme.md")).isEqualTo(expected);
        assertThat(recorded.name()).isEqualTo("partners/acme");
        assertThat(recorded.mode()).isEqualTo(Mode.CREATE);
        assertThat(recorded.sizeBefore()).isZero();
        assertThat(recorded.sizeAfter())
            .isEqualTo(expected.getBytes(StandardCharsets.UTF_8).length);
        assertThat(recorded.historyVersions()).isZero();
        assertThat(recorded.auditId()).isEqualTo(ID.toString());
        assertThat(recorded.warnings()).isEmpty();
        assertThat(recorded.versionToken()).isEqualTo(token("partners/acme"));
        assertThat(recorded.lastChange()).isEqualTo(
            service.read(new ByName("partners/acme"), 0).lastChange());
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.EXECUTED);
        assertThat(port.records).allSatisfy(audit -> {
            assertThat(audit.auditId()).isEqualTo(ID);
            assertThat(audit.toString()).doesNotContain("EDIFACT", "ACME", "brien");
        });
        assertThat(port.records.getLast().inputs()).containsExactly(
            entry("name", "partners/acme"), entry("mode", "create"), entry("sizeBefore", "0"),
            entry("sizeAfter", String.valueOf(recorded.sizeAfter())));
        assertThat(recordLines()).singleElement().asString()
            .contains("name=partners/acme", "mode=create", "sizeBefore=0",
                "sizeAfter=" + recorded.sizeAfter(), "outcome=EXECUTED")
            .doesNotContain("EDIFACT");
    }

    private static java.util.Map.Entry<String, String> entry(String key, String value) {
        return java.util.Map.entry(key, value);
    }

    @Test
    void createMakesAnAbsentDirectoryAndWritesWithoutAClientName() throws IOException {
        Path root = temp.resolve("absent/knowledge");
        KnowledgeService fresh = service(new KnowledgeDirectory(root, PrivateFiles.forHost(),
            clock));

        fresh.record(new Record(Optional.of("a"), Optional.empty(), Mode.CREATE,
            Optional.of("A"), Optional.empty(), "x", Optional.empty(), Optional.empty()));

        assertThat(Files.readString(root.resolve("a.md"))).contains(
            "source: 'assistant (unknown client)'", "topics: []");
    }

    @Test
    void theClientNameIsMaskedAndShortened() throws IOException {
        service.record(new Record(Optional.of("a"), Optional.empty(), Mode.CREATE,
            Optional.of("A"), Optional.empty(), "x", Optional.empty(),
            Optional.of(HELD + "x".repeat(100))));

        String text = text("a.md");
        assertThat(text).doesNotContain(HELD).contains("source: 'assistant (***xxx");
        String source = text.lines().filter(line -> line.startsWith("source: ")).findFirst()
            .orElseThrow();
        assertThat(source).isEqualTo("source: 'assistant (***" + "x".repeat(57) + ")'");
    }

    @Test
    void createOfANameThatExistsInAnotherCaseIsAConflict() throws IOException {
        fixture.note("Störung.md", "kept", OLDER);

        ToolError conflict = error(ErrorCode.CONFLICT, () -> service.record(
            create("STÖRUNG", "T", List.of(), "new")));

        assertThat(conflict.message()).contains("Störung");
        assertThat(text("Störung.md")).isEqualTo("kept");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        assertThat(port.records.getLast().reason().orElseThrow()).startsWith("CONFLICT");
        assertThat(recordLines()).singleElement().asString()
            .contains("name=STÖRUNG", "outcome=REFUSED", "CONFLICT");
    }

    @Test
    void createWhereADirectoryOrAnotherCaseOfTheSuffixOccupiesThePathIsRefused()
        throws IOException {
        Files.createDirectories(fixture.root.resolve("folder.md"));
        Files.writeString(fixture.root.resolve("upper.MD"), "kept");

        ToolError folder = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("folder", "T", List.of(), "new")));
        ToolError upper = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("upper", "T", List.of(), "new")));

        assertThat(folder.message()).contains("folder.md", "already exists");
        assertThat(upper.message()).contains("upper.MD", "only in case");
        assertThat(text("upper.MD")).isEqualTo("kept");
        try (Stream<Path> files = Files.list(fixture.root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrder("folder.md", "upper.MD", ".lock");
        }
    }

    // US2 AS2: append on the current content, history, sizes

    @Test
    void appendAddsTheSectionAndABlockAndKeepsThePreviousVersion() throws IOException {
        fixture.note("a.md", "line one", OLDER);

        Recorded recorded = service.record(append("a", "section\n"));

        String expected = block("a", "", "'by hand'") + "line one\n\nsection\n";
        assertThat(text("a.md")).isEqualTo(expected);
        assertThat(recorded.sizeBefore()).isEqualTo(8);
        assertThat(recorded.sizeAfter())
            .isEqualTo(expected.getBytes(StandardCharsets.UTF_8).length);
        assertThat(recorded.historyVersions()).isEqualTo(1);
        assertThat(versions("a.md")).singleElement()
            .satisfies(version -> assertThat(Files.readString(version)).isEqualTo("line one"));
        assertThat(port.records.getLast().inputs()).containsEntry("sizeBefore", "8")
            .containsEntry("sizeAfter", String.valueOf(recorded.sizeAfter()));
    }

    @Test
    void appendKeepsTitleSourceAndUnknownLinesAndTakesGivenTopics() throws IOException {
        fixture.note("a.md", "---\ntitle: Old\ntopics: [x]\nsource: by hand\n# mine\n"
            + "owner: me\n---\nbody\n", OLDER);

        service.record(new Record(Optional.of("a"), Optional.empty(), Mode.APPEND,
            Optional.empty(), Optional.of(List.of("y")), "more", Optional.empty(),
            Optional.of(CLIENT)));

        assertThat(text("a.md")).isEqualTo("---\ntitle: 'Old'\ntopics: ['y']\n"
            + "source: 'by hand'\nlastWriter: " + WRITER + "\nlastChange: '" + NOW + "'\n"
            + "# mine\nowner: me\n---\nbody\n\nmore");
    }

    // US2 AS3: replace with the token, CONFLICT when stale

    @Test
    void replaceWithTheCurrentTokenReplacesTheContent() throws IOException {
        fixture.note("a.md", "---\ntitle: Kept\nsource: by hand\n---\nold\n", OLDER);

        Recorded recorded = service.record(replace("a", "new\n", token("a")));

        assertThat(text("a.md")).isEqualTo(block("Kept", "", "'by hand'") + "new\n");
        assertThat(recorded.versionToken()).isEqualTo(token("a"));
        assertThat(recorded.historyVersions()).isEqualTo(1);
    }

    @Test
    void replaceWithAStaleTokenIsAConflictNamingTheCurrentLastChange() throws IOException {
        fixture.note("a.md", "old", OLDER);
        String stale = token("a");
        fixture.note("a.md", "changed by hand", OLDER.plusSeconds(60));

        ToolError conflict = error(ErrorCode.CONFLICT, () -> service.record(
            replace("a", "new", stale)));

        assertThat(conflict.message()).contains(OLDER.plusSeconds(60).toString())
            .doesNotContain(stale);
        assertThat(text("a.md")).isEqualTo("changed by hand");
        assertThat(versions("a.md")).isEmpty();
    }

    // US2 AS4, D-10 step 3: secrets in the new text

    @Test
    void aSecretInTheNewTextIsRefusedWithOneLinePerFindingAndNoValue() {
        ToolError refused = error(ErrorCode.INVALID_INPUT, () -> service.record(
            create("a", "uses " + HELD, List.of("t"),
                "intro\npassword: hunter22\nkey " + HELD + "\n")));

        assertThat(refused.message()).contains("configured credential in title",
            "password assignment in content line 2 (key password)",
            "configured credential in content line 3")
            .doesNotContain(HELD, "hunter22");
        assertThat(fixture.root.resolve("a.md")).doesNotExist();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(port.records.getFirst().inputs()).containsEntry("name", "a");
        assertThat(port.records.getFirst().toString()).doesNotContain(HELD, "hunter22");
    }

    @Test
    void aSecretInTheNameIsRefusedAndTheAuditHidesTheName() {
        ToolError refused = error(ErrorCode.INVALID_INPUT, () -> service.record(
            create("x/" + HELD + "..", "T", List.of(), "body")));

        assertThat(refused.message()).contains("configured credential in name")
            .doesNotContain(HELD);
        assertThat(port.records).singleElement().satisfies(audit -> {
            assertThat(audit.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(audit.inputs()).containsEntry("name", "***");
        });
        assertThat(recordLines()).singleElement().asString().contains("name=***")
            .doesNotContain(HELD);
    }

    // US2 AS5: replace of a note with a secret

    @Test
    void replaceOfANoteHoldingASecretIsRefusedBeforeTheToken() throws IOException {
        fixture.note("a.md", "password: hunter22\n", OLDER);

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            replace("a", "clean", "v1.AAAAAAAAAAAAAAAAAAAAAAAA")));

        assertThat(all(refused)).contains("by hand").doesNotContain("hunter22");
        assertThat(text("a.md")).isEqualTo("password: hunter22\n");
    }

    @Test
    void appendToANoteHoldingASecretKeepsItAndTheResultTokenIsMasked() throws IOException {
        fixture.note("a.md", "password: hunter22\n", OLDER);

        Recorded recorded = service.record(append("a", "more"));

        assertThat(text("a.md")).contains("password: hunter22\n\nmore");
        assertThat(recorded.versionToken()).isEqualTo(token("a"));
    }

    // D-10 step 1: combination rules and the early content bound

    @Test
    void everyCombinationRuleIsInvalidInputBeforeTheCredentialsAreRead() {
        String token = "v1.AAAAAAAAAAAAAAAAAAAAAAAA";
        List<Record> calls = List.of(
            new Record(Optional.of("a"), Optional.empty(), Mode.CREATE, Optional.empty(),
                Optional.empty(), "x", Optional.empty(), Optional.empty()),
            new Record(Optional.empty(), Optional.empty(), Mode.APPEND, Optional.empty(),
                Optional.empty(), "x", Optional.empty(), Optional.empty()),
            new Record(Optional.of("a"), Optional.of("r"), Mode.APPEND, Optional.empty(),
                Optional.empty(), "x", Optional.empty(), Optional.empty()),
            new Record(Optional.empty(), Optional.of("r"), Mode.CREATE, Optional.of("T"),
                Optional.empty(), "x", Optional.empty(), Optional.empty()),
            new Record(Optional.of("a"), Optional.empty(), Mode.REPLACE, Optional.empty(),
                Optional.empty(), "x", Optional.empty(), Optional.empty()),
            new Record(Optional.of("a"), Optional.empty(), Mode.APPEND, Optional.empty(),
                Optional.empty(), "x", Optional.of(token), Optional.empty()),
            new Record(Optional.of("a"), Optional.empty(), Mode.CREATE, Optional.of("T"),
                Optional.empty(), "x", Optional.of(token), Optional.empty()),
            create("a", "T", List.of(), ""),
            create("a", "", List.of(), "x"),
            create("a", "😀".repeat(201), List.of(), "x"),
            create("a", "two\nlines", List.of(), "x"),
            create("a", "T", java.util.Collections.nCopies(21, "t"), "x"),
            create("a", "T", List.of("a,b"), "x"),
            create("a", "T", List.of(""), "x"),
            create("a", "T", List.of("x".repeat(61)), "x"),
            create("a", "T", List.of("Straße", "STRASSE"), "x"),
            create("a/".repeat(500) + "a", "T", List.of(), "x"),
            replace("a", "x", "v2.AAAA"));
        List<String> messages = new ArrayList<>();
        for (Record call : calls) {
            messages.add(error(ErrorCode.INVALID_INPUT, () -> service.record(call)).message());
        }

        assertThat(messages).doesNotHaveDuplicates();
        assertThat(secrets.prepared()).isEmpty();
        assertThat(port.records).hasSize(calls.size()).allSatisfy(audit -> {
            assertThat(audit.outcome()).isEqualTo(AuditOutcome.REFUSED);
            assertThat(audit.inputs()).containsEntry("name", "***");
        });
        assertThat(fixture.root).isEmptyDirectory();
    }

    @Test
    void contentOverTheNoteBoundIsRefusedBeforeTheCredentialsAreRead() {
        String content = "ä".repeat(KnowledgeLimits.NOTE_BYTES / 2 + 1);

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("a", "T", List.of(), content)));

        assertThat(refused.message()).contains("content", "256 KiB");
        assertThat(secrets.prepared()).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
    }

    @Test
    void credentialsNotReadYetRefuseTheWrite() throws IOException {
        KnowledgeService waiting = new KnowledgeService(directory,
            FakeConfiguredSecrets.waiting("dev", "credential store"), fixture.refs, BOUND,
            new KnowledgeService.Writing(PrivateFiles.forHost(), clock,
                new KnowledgeAudit.Dependencies(port, clock, "default", () -> ID)));

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> waiting.record(
            create("a", "T", List.of(), "x")));

        assertThat(refused.message()).contains("have not been read yet");
        assertThat(outcomes()).containsExactly(AuditOutcome.REFUSED);
        assertThat(port.records.getFirst().inputs()).containsEntry("name", "***");
        assertThat(fixture.root).isEmptyDirectory();
    }

    // D-10 step 4: the target

    @Test
    void aNameOutsideTheRecordRuleNamesTheRuleButNotTheName() {
        ToolError refused = error(ErrorCode.INVALID_INPUT, () -> service.record(
            create("partners/acme.md", "T", List.of(), "x")));

        assertThat(refused.message()).contains("segment 2", "ends in .md")
            .doesNotContain("acme");
        assertThat(port.records).singleElement()
            .satisfies(audit -> assertThat(audit.inputs())
                .containsEntry("name", "partners/acme.md"));
    }

    @Test
    void aRefWritesTheNoteItResolvesToAndThePendingRecordNamesIt() throws IOException {
        fixture.note("a.md", "x", OLDER);
        String ref = refOf("a");

        service.record(byRef(ref, Mode.APPEND, "more"));

        assertThat(text("a.md")).endsWith("x\n\nmore");
        assertThat(port.records.getFirst().outcome()).isEqualTo(AuditOutcome.PENDING);
        assertThat(port.records.getFirst().inputs()).containsEntry("name", "a")
            .doesNotContainValue(ref);
    }

    private String refOf(String name) {
        return service.search(new Search(Optional.empty(), Optional.empty(), 50, 0)).hits()
            .stream().filter(hit -> hit.name().equals(name)).findFirst().orElseThrow().ref();
    }

    @Test
    void anUnknownRefIsNotFound() {
        ToolError missing = error(ErrorCode.NOT_FOUND, () -> service.record(
            byRef("unknown", Mode.APPEND, "x")));

        assertThat(missing.message()).contains("list the notes again");
        assertThat(port.records).singleElement().satisfies(audit -> assertThat(
            audit.inputs()).containsEntry("name", "***"));
    }

    @Test
    void aRefToANoteTheToolCannotWriteIsRefused() throws IOException {
        fixture.note("trailing..md", "x", OLDER);
        fixture.note("has " + HELD + ".md", "x", OLDER);

        ToolError rule = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            byRef(refOf("trailing."), Mode.APPEND, "more")));
        ToolError secret = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            byRef(refOf("has ***"), Mode.APPEND, "more")));

        assertThat(rule.message()).contains("cannot be written by the tool", "writable");
        assertThat(all(secret)).contains("writable").doesNotContain(HELD);
        assertThat(port.records).extracting(audit -> audit.inputs().get("name"))
            .containsExactly("trailing.", "has ***");
        assertThat(text("trailing..md")).isEqualTo("x");
    }

    @Test
    void aRefWhoseNoteMovedBeforeTheLockIsNotFound() throws IOException {
        fixture.note("a.md", "x", OLDER);
        String ref = refOf("a");
        Files.move(fixture.root.resolve("a.md"), fixture.root.resolve("b.md"));

        ToolError missing = error(ErrorCode.NOT_FOUND, () -> service.record(
            byRef(ref, Mode.APPEND, "more")));

        assertThat(missing.message()).contains("list the notes again");
    }

    // D-10 step 7: checks under the lock

    @Test
    void appendToAMissingNoteIsNotFoundWithSimilarNames() throws IOException {
        fixture.note("acme.md", "x", OLDER);

        ToolError missing = error(ErrorCode.NOT_FOUND, () -> service.record(
            append("acmee", "x")));

        assertThat(missing.message()).contains("'acme'");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
    }

    @Test
    void appendByANameTwoFilesShareIsAConflictUseRef() throws IOException {
        String nfd = Normalizer.normalize("Störung", Normalizer.Form.NFD);
        fixture.note("Störung.md", "one", OLDER);
        try {
            fixture.note(nfd + ".md", "two", OLDER);
        } catch (IOException e) {
            assumeTrue(false, "the file system refuses the second form");
        }
        try (Stream<Path> files = Files.list(fixture.root)) {
            assumeTrue(files.count() == 2, "the file system keeps both forms apart");
        }

        ToolError conflict = error(ErrorCode.CONFLICT, () -> service.record(
            append("Störung", "x")));

        assertThat(all(conflict)).contains("use ref");
    }

    @Test
    void anOversizedUnreadableOrMalformedNoteIsRefused() throws IOException {
        Path big = fixture.note("big.md", "", OLDER);
        try (RandomAccessFile file = new RandomAccessFile(big.toFile(), "rw")) {
            file.setLength(KnowledgeLimits.READ_BYTES + 1L);
        }
        Path latin = fixture.root.resolve("latin.md");
        Files.write(latin, new byte[] {'a', (byte) 0xE4});
        fixture.note("broken.md", "---\ntitle: open\nbody without end\n", OLDER);

        ToolError tooLarge = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            append("big", "x")));
        ToolError unreadable = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            append("latin", "x")));
        ToolError malformed = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            append("broken", "x")));

        assertThat(tooLarge.message()).contains("4 MiB");
        assertThat(unreadable.message()).contains("UTF-8");
        assertThat(all(malformed)).contains("metadata block", "by hand");
        assertThat(versions("big.md")).isEmpty();
        assertThat(versions("broken.md")).isEmpty();
    }

    @Test
    void aBlockThatWouldExceedTheBoundsIsRefused() throws IOException {
        StringBuilder block = new StringBuilder("---\n");
        for (int i = 0; i < 59; i++) {
            block.append("k").append(i).append(": v\n");
        }
        fixture.note("a.md", block + "---\nbody\n", OLDER);

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            append("a", "x")));

        assertThat(refused.message()).contains("64 lines or 8 KiB", "by hand");
        assertThat(versions("a.md")).isEmpty();
    }

    @Test
    void aNoteOverTheNoteBoundAfterTheWriteIsRefused() throws IOException {
        fixture.note("a.md", "x".repeat(KnowledgeLimits.NOTE_BYTES - 100), OLDER);

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            append("a", "y".repeat(200))));

        assertThat(refused.message()).contains("256 KiB");
        assertThat(versions("a.md")).isEmpty();
        assertThat(port.records.getLast().inputs()).containsEntry("sizeBefore",
            String.valueOf(KnowledgeLimits.NOTE_BYTES - 100));
    }

    @Test
    void theKnowledgeOverTheTotalBoundAfterTheWriteIsRefused() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(
            fixture.root.resolve("data.bin").toFile(), "rw")) {
            file.setLength(KnowledgeLimits.TOTAL_BYTES - 10);
        }

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("a", "T", List.of(), "x")));

        assertThat(refused.message()).contains("100 MiB");
        assertThat(fixture.root.resolve("a.md")).doesNotExist();
    }

    @Test
    void aDirectoryTooLargeToMeasureFailsClosed() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(fixture.root.resolve("f" + i + ".txt"), "x");
        }
        KnowledgeService small = service(new KnowledgeDirectory(fixture.root,
            PrivateFiles.forHost(), clock, KnowledgeLimits.WALK_DEPTH,
            KnowledgeLimits.WALK_ENTRIES, 3));

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> small.record(
            create("a", "T", List.of(), "x")));

        assertThat(refused.message()).contains("too large to measure");
        assertThat(fixture.root.resolve("a.md")).doesNotExist();
    }

    // US2 AS10, D-12: links

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aSymbolicLinkIsNeverWrittenThrough() throws IOException {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path target = Files.writeString(outside.resolve("target.md"), "outside");
        Files.createSymbolicLink(fixture.root.resolve("old.md"), target);
        Files.createSymbolicLink(fixture.root.resolve("linked"), outside);

        ToolError append = error(ErrorCode.NOT_FOUND, () -> service.record(
            append("old", "x")));
        ToolError create = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("old", "T", List.of(), "x")));
        ToolError below = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            create("linked/new", "T", List.of(), "x")));

        assertThat(append.message()).contains("old");
        assertThat(create.message()).contains("old.md");
        assertThat(below.message()).contains("linked", "symbolic link");
        assertThat(Files.readString(target)).isEqualTo("outside");
        try (Stream<Path> files = Files.list(outside)) {
            assertThat(files).containsExactly(target);
        }
    }

    // D-12: the race between the check and publication

    @Test
    void aFileThatAppearsAtTheTargetBeforePublicationIsAConflictAndStaysUnchanged()
        throws IOException {
        Path target = fixture.root.resolve("a.md");
        directory.beforePublish(() -> {
            try {
                Files.writeString(target, "by hand meanwhile");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        ToolError conflict = error(ErrorCode.CONFLICT, () -> service.record(
            create("a", "T", List.of(), "x")));

        assertThat(conflict.message()).contains("a");
        assertThat(Files.readString(target)).isEqualTo("by hand meanwhile");
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.REFUSED);
        try (Stream<Path> files = Files.list(fixture.root)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                .containsExactlyInAnyOrder("a.md", ".lock");
        }
    }

    // D-10, FR-014: before and after the commit point

    @Test
    void aFailedPublicationLeavesNoteAndHistoryAsTheyWere() throws IOException {
        fixture.note("a.md", "old", OLDER);
        directory.publisher(new Publisher() {
            @Override
            public void link(Path target, Path temporary) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new IOException("disk full");
            }
        });

        ToolError failed = error(ErrorCode.INTERNAL, () -> service.record(append("a", "x")));

        assertThat(all(failed)).contains("as they were").doesNotContain("disk full");
        assertThat(text("a.md")).isEqualTo("old");
        assertThat(versions("a.md")).isEmpty();
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING, AuditOutcome.FAILED);
        assertThat(recordLines()).singleElement().asString().contains("outcome=FAILED");
    }

    @Test
    void aFileSystemWithoutAtomicMovesIsRefusedAndTheVersionUndone() throws IOException {
        fixture.note("a.md", "old", OLDER);
        directory.publisher(new Publisher() {
            @Override
            public void link(Path target, Path temporary) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void move(Path temporary, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(),
                    target.toString(), "no");
            }
        });

        ToolError refused = error(ErrorCode.PRECONDITION_FAILED, () -> service.record(
            replace("a", "x", token("a"))));

        assertThat(refused.message()).contains("atomically");
        assertThat(text("a.md")).isEqualTo("old");
        assertThat(versions("a.md")).isEmpty();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFailedPruneAfterPublicationIsASuccessWithAWarning() throws IOException {
        fixture.note("a.md", "v0", OLDER);
        for (int i = 1; i <= 10; i++) {
            service.record(append("a", "v" + i));
        }
        assertThat(versions("a.md")).hasSize(10);
        Path history = fixture.root.resolve(".history/a.md");
        directory.beforePublish(() -> {
            try {
                Files.setPosixFilePermissions(history,
                    PosixFilePermissions.fromString("r-x------"));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        try {
            Recorded recorded = service.record(append("a", "v11"));

            assertThat(text("a.md")).endsWith("v11");
            assertThat(recorded.historyVersions()).isEqualTo(11);
            assertThat(recorded.warnings()).singleElement().asString()
                .contains("history", "11 versions");
            assertThat(port.records.getLast().outcome()).isEqualTo(AuditOutcome.EXECUTED);
        } finally {
            Files.setPosixFilePermissions(history, PosixFilePermissions.fromString("rwx------"));
        }
        directory.beforePublish(() -> { });
        assertThat(service.record(append("a", "v12")).historyVersions()).isEqualTo(10);
    }

    // D-13: the audit before and after the write

    @Test
    void aPendingRecordThatCannotBeWrittenWritesNothing() throws IOException {
        fixture.note("a.md", "old", OLDER);
        port.failing.add(AuditOutcome.PENDING);

        ToolError failed = error(ErrorCode.INTERNAL, () -> service.record(append("a", "x")));

        assertThat(failed.message()).contains("audit record", "nothing was written");
        assertThat(text("a.md")).isEqualTo("old");
        assertThat(fixture.root.resolve(".history")).doesNotExist();
        assertThat(fixture.root.resolve(".lock")).doesNotExist();
        assertThat(recordLines()).singleElement().asString().contains("outcome=FAILED");
    }

    @Test
    void aFinalRecordThatCannotBeWrittenKeepsTheNoteAndWarns() throws IOException {
        port.failing.add(AuditOutcome.EXECUTED);

        Recorded recorded = service.record(create("a", "T", List.of(), "x"));

        assertThat(fixture.root.resolve("a.md")).exists();
        assertThat(recorded.warnings()).singleElement().asString()
            .contains("final audit record", ID.toString());
        assertThat(outcomes()).containsExactly(AuditOutcome.PENDING);
    }

    @Test
    void aRefusalWhoseRecordCannotBeWrittenIsInternal() {
        port.failing.add(AuditOutcome.REFUSED);

        ToolError failed = error(ErrorCode.INTERNAL, () -> service.record(
            create("a", "T", List.of(), "")));

        assertThat(failed.message()).contains("INVALID_INPUT");
    }

    @Test
    void aReadOnlyServiceCannotRecord() {
        KnowledgeService readOnly = fixture.service(secrets);

        assertThatThrownBy(() -> readOnly.record(create("a", "T", List.of(), "x")))
            .isInstanceOf(IllegalStateException.class);
    }
}
