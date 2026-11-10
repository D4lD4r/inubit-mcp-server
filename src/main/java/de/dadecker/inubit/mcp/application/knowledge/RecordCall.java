package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Occupied;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.WriteRefusal;
import de.dadecker.inubit.mcp.application.knowledge.Record.Mode;
import de.dadecker.inubit.mcp.application.knowledge.SecretFinder.Finding;
import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One call of {@link KnowledgeService#record}: the steps of research D-10 in order, the audit of
 * D-13 and one log line.
 */
final class RecordCall {

    static final int TITLE_CODE_POINTS = 200;
    static final int TOPICS = 20;
    static final int TOPIC_CODE_POINTS = 60;
    static final int CLIENT_CODE_POINTS = 60;
    static final int KEY_CODE_POINTS = 40;
    static final int MAX_FINDINGS = 20;
    static final int NAME_CODE_POINTS = NoteName.MAX_SEGMENTS * NoteName.MAX_SEGMENT_CODE_POINTS
        + NoteName.MAX_SEGMENTS - 1;

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private static final Pattern TOKEN = Pattern.compile("v1\\.[A-Za-z0-9_-]{24}");
    private static final String NOT_ONE_LINE = "\\p{Cc}|[\\u2028\\u2029\\u0085]";
    private static final Pattern BREAK_OR_CONTROL = Pattern.compile(NOT_ONE_LINE);
    private static final Pattern TOPIC_SEPARATOR = Pattern.compile("[,\\[\\]]");
    private static final String SCHEMA_NEXT = "Correct the call as the tool description says,"
        + " then call again";
    private static final String UNKNOWN = "-";

    private final KnowledgeService service;
    private final KnowledgeService.Writing writing;
    private final Record call;
    private final SecretFinder finder;
    private final KnowledgeDirectory directory;
    private final KnowledgeAudit audit;
    private String logName = KnowledgeAudit.HIDDEN;
    private String sizeBefore = UNKNOWN;
    private String sizeAfter = UNKNOWN;
    private String outcome = "FAILED";
    private boolean failed;

    RecordCall(KnowledgeService service, KnowledgeService.Writing writing, Record call) {
        this.service = service;
        this.writing = writing;
        this.call = call;
        this.finder = service.finder();
        this.directory = service.directory();
        this.audit = new KnowledgeAudit(writing.audit(), call.mode().value(), call.mcpClient());
    }

    Recorded run() {
        try {
            Target target = beforeTheWrite();
            audit.pending();
            try {
                Recorded recorded = write(target);
                outcome = AuditOutcome.EXECUTED.name();
                return recorded;
            } catch (ToolErrorException e) {
                ToolError error = e.error();
                AuditOutcome last = failed ? AuditOutcome.FAILED : AuditOutcome.REFUSED;
                audit.outcome(last, error.code() + ": " + error.message());
                outcome = failed ? "FAILED" : "REFUSED " + error.code();
                throw e;
            } catch (RuntimeException e) {
                audit.outcome(AuditOutcome.FAILED, e.getClass().getSimpleName());
                throw e;
            }
        } finally {
            LOG.info("knowledge.record name={} mode={} sizeBefore={} sizeAfter={} outcome={}",
                logName, call.mode().value(), sizeBefore, sizeAfter, outcome);
        }
    }

    /** What step 4 found: the name to write and, for a {@code ref}, the path it resolved to. */
    private record Target(NoteName name, Optional<Path> relative) {
    }

    /** Steps 1 to 4: every refusal is one {@code REFUSED} record. */
    private Target beforeTheWrite() {
        try {
            combination();
            service.ready();
            secrets();
            return target();
        } catch (ToolErrorException e) {
            ToolErrorException audited = audit.refused(e.error());
            outcome = "REFUSED " + audited.error().code();
            throw audited;
        }
    }

    // step 1

    private void combination() {
        Mode mode = call.mode();
        if (mode == Mode.CREATE && call.title().isEmpty()) {
            throw invalid("title is required for create");
        }
        if (call.name().isEmpty() && call.ref().isEmpty()) {
            throw invalid("give the name or the ref of the note");
        }
        if (call.name().isPresent() && call.ref().isPresent()) {
            throw invalid("give the name or the ref of the note, not both");
        }
        if (mode == Mode.CREATE && call.ref().isPresent()) {
            throw invalid("ref is only for append and replace; create needs a name");
        }
        if (mode == Mode.REPLACE && call.versionToken().isEmpty()) {
            throw invalid("versionToken is required for replace");
        }
        if (mode != Mode.REPLACE && call.versionToken().isPresent()) {
            throw invalid("versionToken is only for replace, not for " + mode.value());
        }
        if (call.content().isEmpty()) {
            throw invalid("content is empty");
        }
        call.title().ifPresent(RecordCall::title);
        call.topics().ifPresent(RecordCall::topics);
        if (call.name().map(name -> codePoints(name) > NAME_CODE_POINTS).orElse(false)) {
            throw invalid("name is longer than " + NAME_CODE_POINTS + " characters");
        }
        if (call.versionToken().map(token -> !TOKEN.matcher(token).matches()).orElse(false)) {
            throw invalid("versionToken is malformed; use the versionToken of read_knowledge");
        }
        if (call.content().getBytes(StandardCharsets.UTF_8).length > KnowledgeLimits.NOTE_BYTES) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "content is larger than the bound of a note (256 KiB); nothing was written",
                "A note holds at most 256 KiB",
                "Split the text into several notes, then call again"));
        }
    }

    private static void title(String title) {
        if (title.isEmpty()) {
            throw invalid("title is empty");
        }
        if (codePoints(title) > TITLE_CODE_POINTS) {
            throw invalid("title is longer than " + TITLE_CODE_POINTS + " characters");
        }
        if (BREAK_OR_CONTROL.matcher(title).find()) {
            throw invalid("title is not one line (it holds a line break or a control character)");
        }
    }

    private static void topics(List<String> topics) {
        if (topics.size() > TOPICS) {
            throw invalid("topics holds more than " + TOPICS + " topics");
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < topics.size(); i++) {
            String topic = topics.get(i);
            String which = "topic " + (i + 1);
            if (topic.isEmpty()) {
                throw invalid(which + " is empty");
            }
            if (codePoints(topic) > TOPIC_CODE_POINTS) {
                throw invalid(which + " is longer than " + TOPIC_CODE_POINTS + " characters");
            }
            if (BREAK_OR_CONTROL.matcher(topic).find()) {
                throw invalid(which + " is not one line (it holds a line break or a control"
                    + " character)");
            }
            if (TOPIC_SEPARATOR.matcher(topic).find()) {
                throw invalid(which + " holds , [ or ]");
            }
            Integer earlier = seen.putIfAbsent(NoteName.fold(topic), i + 1);
            if (earlier != null) {
                throw invalid(which + " repeats topic " + earlier
                    + " (topics are compared ignoring case)");
            }
        }
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private static ToolErrorException invalid(String rule) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            "Invalid record_knowledge call: " + rule + "; nothing was written",
            "The call breaks a rule of record_knowledge", SCHEMA_NEXT));
    }

    // step 3

    private void secrets() {
        Set<String> lines = new LinkedHashSet<>();
        call.name().ifPresent(name -> {
            String nfc = Normalizer.normalize(name, Normalizer.Form.NFC);
            finder.findInName(nfc).forEach(finding -> lines.add(line(finding, "name")));
            if (lines.isEmpty()) {
                logName = Shown.cut(Shown.line(nfc));
                audit.name(nfc);
            }
        });
        call.title().ifPresent(title ->
            finder.find(title).forEach(finding -> lines.add(line(finding, "title"))));
        call.topics().ifPresent(topics -> topics.forEach(topic ->
            finder.find(topic).forEach(finding -> lines.add(line(finding, "topics")))));
        finder.find(call.content()).forEach(finding ->
            lines.add(line(finding, "content line " + finding.line())));
        if (lines.isEmpty()) {
            return;
        }
        List<String> shown = new ArrayList<>(lines);
        if (shown.size() > MAX_FINDINGS) {
            int more = shown.size() - MAX_FINDINGS;
            shown = new ArrayList<>(shown.subList(0, MAX_FINDINGS));
            shown.add("… and " + more + " more");
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            "The note would hold a secret; nothing was written:\n" + String.join("\n", shown),
            "Notes never hold passwords, keys, tokens or other secrets",
            "Leave the secret out (describe where it is kept instead), then call again"));
    }

    private String line(Finding finding, String where) {
        String kind = switch (finding.kind()) {
            case CONFIGURED_CREDENTIAL -> "configured credential";
            case KEY_MATERIAL -> "key material";
            case INUBIT_ENCRYPTED -> "INUBIT encrypted value";
            case ASSIGNMENT -> "password assignment";
        };
        return kind + " in " + where + finding.key()
            .map(key -> " (key " + cut(Shown.line(finder.mask(key).text()), KEY_CODE_POINTS)
                + ")")
            .orElse("");
    }

    private static String cut(String text, int codePoints) {
        return codePoints(text) <= codePoints ? text
            : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    // step 4

    private Target target() {
        if (call.name().isPresent()) {
            String name = call.name().get();
            Optional<String> problem = NoteName.writableProblem(name);
            if (problem.isPresent()) {
                throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                    "Invalid record_knowledge call: name breaks the record rule ("
                        + problem.get() + "); nothing was written",
                    "Names the tool writes have up to 4 segments of letters, digits, _ . - and"
                        + " spaces, without .md",
                    SCHEMA_NEXT));
            }
            return new Target(NoteName.of(name), Optional.empty());
        }
        NoteFile file = service.find(new NoteSelector.ByRef(call.ref().get()), service.list());
        String masked = finder.maskName(file.name().value()).text();
        logName = Shown.cut(Shown.line(masked));
        audit.name(masked);
        boolean secret = !masked.equals(file.name().value());
        if (secret || !file.name().writable()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The note " + Names.quote(Shown.cut(Shown.line(masked))) + " cannot be written"
                    + " by the tool (writable is false: "
                    + (secret ? "its name holds a secret" : "its name is outside the record rule")
                    + "); nothing was written",
                "The tool writes only notes whose name follows the record rule and holds no"
                    + " secret",
                "Rename the note by hand, or let the operator change it by hand"));
        }
        return new Target(file.name(), Optional.of(file.relative()));
    }

    // steps 6 and 7

    private Recorded write(Target target) {
        Path real;
        try {
            real = directory.writeRoot();
        } catch (IOException e) {
            throw KnowledgeService.unusable(e);
        }
        try (KnowledgeLock.Held held = new KnowledgeLock(real, writing.files())
            .acquire(KnowledgeLimits.LOCK_WAIT)) {
            return locked(target, new KnowledgeHistory(real, writing.files(), writing.clock()));
        } catch (IOException e) {
            failed = true;
            throw new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
                "The note could not be written (" + e.getClass().getSimpleName() + "); the note"
                    + " and its history are as they were",
                "Reading the knowledge directory failed unexpectedly",
                "Check the knowledge directory, then retry"));
        }
    }

    private Recorded locked(Target target, KnowledgeHistory history) throws IOException {
        Listing listing = service.list();
        Mode mode = call.mode();
        NoteName name;
        long before;
        String text;
        Optional<NoteFile> existing;
        if (mode == Mode.CREATE) {
            name = target.name();
            free(name, listing);
            before = 0;
            text = block(new NoteMetadata.Written(call.title().orElseThrow(),
                call.topics().orElse(List.of()), writer(), writer(), writing.clock().instant(),
                List.of()), name) + "\n" + call.content();
            existing = Optional.empty();
        } else {
            NoteFile file = existing(target, listing);
            name = file.name();
            Note note = directory.read(file);
            String shown = shown(name);
            if (note.state() != Note.State.OK) {
                throw precondition("The note " + Names.quote(shown) + " cannot be written: "
                    + note.problem().orElseThrow() + "; nothing was written",
                    "The tool writes only notes it can read in full",
                    "Fix or shorten the note by hand, then retry");
            }
            if (note.metadata().malformed()) {
                throw precondition("The note " + Names.quote(shown) + " has a malformed metadata"
                    + " block (" + note.metadata().problem().orElse("") + "); nothing was written",
                    "The tool keeps the metadata block of a note and cannot read this one",
                    "Fix the metadata block by hand, then retry");
            }
            before = note.file().size();
            sizeBefore = Long.toString(before);
            audit.sizeBefore(before);
            String current = note.text().orElseThrow();
            if (mode == Mode.REPLACE) {
                replaceable(note, current, shown);
            }
            occupied(directory.checkExisting(file.relative()));
            text = newText(note, name);
            existing = Optional.of(note.file());
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        bounds(name, bytes);
        return publish(name, existing, text, bytes, before, history);
    }

    private void free(NoteName name, Listing listing) throws IOException {
        for (NoteFile file : listing.files()) {
            if (file.name().fold().equals(name.fold())) {
                throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                    "A note named " + Names.quote(shown(file.name())) + " already exists (names"
                        + " are compared ignoring case and Unicode form); nothing was written",
                    "create never overwrites a note",
                    "Append to that note, replace it, or choose another name"));
            }
        }
        occupied(directory.checkFree(name));
    }

    private NoteFile existing(Target target, Listing listing) {
        if (target.relative().isEmpty()) {
            return service.find(new NoteSelector.ByName(target.name().value()), listing);
        }
        Optional<NoteFile> again = service.refs().resolve(call.ref().orElseThrow(), listing);
        if (again.isEmpty() || !again.get().relative().equals(target.relative().get())) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "The note was moved or removed; list the notes again",
                "The note changed on disk while the call waited for the lock",
                "List the notes with read_knowledge and use the ref of the new hit"));
        }
        return again.get();
    }

    private void replaceable(Note note, String current, String shown) {
        List<Finding> findings = finder.find(current);
        if (!findings.isEmpty()) {
            throw precondition("The note " + Names.quote(shown) + " holds a secret ("
                + line(findings.getFirst(), "line " + findings.getFirst().line())
                + "); replace would keep it in the history; nothing was written",
                "The tool never replaces a note that holds a secret",
                "Remove the secret by hand first, then read the note again and retry");
        }
        String token = VersionToken.of(note.file().modified(), finder.mask(current).text());
        if (!token.equals(call.versionToken().orElseThrow())) {
            Instant lastChange = note.file().modified().toInstant()
                .truncatedTo(ChronoUnit.SECONDS);
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "The note " + Names.quote(shown) + " changed since it was read (last change "
                    + lastChange + "); nothing was written",
                "The versionToken is not the one of the current note",
                "Read the note again with read_knowledge and replace with its new versionToken"));
        }
    }

    private String newText(Note note, NoteName name) {
        NoteMetadata current = note.metadata();
        NoteMetadata.Written values = new NoteMetadata.Written(
            call.title().orElseGet(() -> current.title().orElse(name.lastSegment())),
            call.topics().orElse(current.topics()),
            current.source().orElse(KnowledgeService.BY_HAND), writer(),
            writing.clock().instant(), current.unknown());
        String block = block(values, name);
        if (call.mode() == Mode.REPLACE) {
            return block + call.content();
        }
        String body = note.body();
        boolean broken = body.isEmpty() || body.endsWith("\n") || body.endsWith("\r");
        return block + body + (broken ? "" : "\n") + "\n" + call.content();
    }

    private String block(NoteMetadata.Written values, NoteName name) {
        return NoteMetadata.write(values).orElseThrow(() -> precondition("The metadata block of"
            + " the note " + Names.quote(shown(name)) + " would exceed "
            + NoteMetadata.BLOCK_LINES + " lines or 8 KiB; shorten it by hand; nothing was"
            + " written",
            "The tool writes a metadata block of at most " + NoteMetadata.BLOCK_LINES
                + " lines and 8 KiB",
            "Shorten the metadata block by hand, then retry"));
    }

    /** {@code assistant (<client>)}: masked, one line, at most 60 characters. */
    private String writer() {
        String client = call.client()
            .map(value -> cut(Shown.line(finder.mask(value).text()), CLIENT_CODE_POINTS))
            .filter(value -> !value.isEmpty()).orElse("unknown client");
        return "assistant (" + client + ")";
    }

    private void bounds(NoteName name, byte[] bytes) throws IOException {
        if (bytes.length > KnowledgeLimits.NOTE_BYTES) {
            throw precondition("The note " + Names.quote(shown(name)) + " would hold "
                + bytes.length + " bytes, more than the bound of 256 KiB; nothing was written",
                "A note holds at most 256 KiB",
                "Start a new note for the section, or shorten the note");
        }
        long total;
        try {
            total = directory.totalBytes();
        } catch (WriteRefusal e) {
            throw refusal(e);
        }
        if (total + bytes.length > KnowledgeLimits.TOTAL_BYTES) {
            throw precondition("The knowledge directory would exceed its bound of 100 MiB with"
                + " this write; nothing was written",
                "The knowledge directory (history included) holds at most 100 MiB",
                "Remove files or old history versions from the knowledge directory by hand");
        }
    }

    // the write and the commit point

    private Recorded publish(NoteName name, Optional<NoteFile> existing, String text,
        byte[] bytes, long before, KnowledgeHistory history) throws IOException {
        Path version = null;
        try {
            if (existing.isPresent()) {
                version = history.add(existing.get().file(), name);
            } else {
                history.check(name);
            }
        } catch (IOException e) {
            throw failed(e, true);
        }
        Path published;
        try {
            published = existing.isEmpty() ? directory.publishNew(name, bytes)
                : directory.publishReplace(existing.get().relative(), bytes);
        } catch (WriteRefusal e) {
            boolean undone = undo(history, version);
            if (!undone) {
                throw failed(e, false);
            }
            throw refusal(e);
        } catch (IOException | RuntimeException e) {
            throw failed(e, undo(history, version));
        }
        return committed(name, published, text, bytes, before, history);
    }

    private static boolean undo(KnowledgeHistory history, Path version) {
        if (version == null) {
            return true;
        }
        try {
            history.undo(version);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private ToolErrorException failed(Exception e, boolean undone) {
        failed = true;
        return new ToolErrorException(ToolError.of(ErrorCode.INTERNAL,
            "The note could not be written (" + e.getClass().getSimpleName() + "); "
                + (undone ? "the note and its history are as they were"
                    : "the note is as it was, but an extra version stays in its history"),
            "Writing to the knowledge directory failed unexpectedly",
            "Check the knowledge directory (free space, permissions), then retry"));
    }

    private Recorded committed(NoteName name, Path published, String text, byte[] bytes,
        long before, KnowledgeHistory history) {
        List<String> warnings = new ArrayList<>();
        int kept;
        try {
            kept = history.prune(name);
        } catch (IOException | RuntimeException e) {
            OptionalInt counted = count(history, name);
            kept = counted.orElse(KnowledgeLimits.HISTORY + 1);
            warnings.add("The note was written, but its history could not be pruned ("
                + e.getClass().getSimpleName() + "); it holds " + counted.stream()
                    .mapToObj(Integer::toString).findFirst().orElse("an unknown number of")
                + " versions until a later write prunes it");
        }
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(published, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            modified = FileTime.from(writing.clock().instant());
            warnings.add("The note was written, but its last change could not be read; read it"
                + " again for its versionToken");
        }
        sizeBefore = Long.toString(before);
        sizeAfter = Long.toString(bytes.length);
        audit.sizes(before, bytes.length);
        audit.outcome(AuditOutcome.EXECUTED, "written").ifPresent(warnings::add);
        return new Recorded(shown(name), call.mode(),
            modified.toInstant().truncatedTo(ChronoUnit.SECONDS),
            VersionToken.of(modified, finder.mask(text).text()), before, bytes.length, kept,
            audit.auditId().toString(), warnings);
    }

    private static OptionalInt count(KnowledgeHistory history, NoteName name) {
        try {
            return OptionalInt.of(history.count(name));
        } catch (IOException | RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    // errors

    private ToolErrorException refusal(WriteRefusal e) {
        return switch (e.kind()) {
            case EXISTS -> new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "A file appeared at " + path(e.occupied().orElseThrow().relative()) + " while the"
                    + " note was written; it was not replaced and nothing was written",
                "Someone else wrote a file at the note path meanwhile",
                "List the notes again, then append to the note or choose another name"));
            case OCCUPIED -> occupiedError(e.occupied().orElseThrow());
            case NOT_ATOMIC -> precondition("The file system of the knowledge directory cannot"
                + " write notes atomically (no hard link or atomic move); nothing was written",
                "The tool writes a note only in one atomic step",
                "Move the knowledge directory to a local file system");
            case TOO_LARGE_TO_MEASURE -> precondition("The knowledge directory is too large to"
                + " measure (more than " + KnowledgeLimits.SIZE_WALK_ENTRIES + " entries);"
                + " nothing was written",
                "The tool checks the bound of 100 MiB on every write",
                "Remove files from the knowledge directory by hand");
        };
    }

    private void occupied(Optional<Occupied> occupied) {
        if (occupied.isPresent()) {
            throw occupiedError(occupied.get());
        }
    }

    private ToolErrorException occupiedError(Occupied occupied) {
        return precondition("The path " + path(occupied.relative()) + " in the knowledge"
            + " directory " + occupied.what() + "; nothing was written",
            "The tool never writes through a link, over another file or directory, or to a"
                + " name that differs only in case",
            "Move the path away by hand or choose another name, then retry");
    }

    private String path(Path relative) {
        return Names.quote(Shown.name(finder, KnowledgeService.slashed(relative)));
    }

    private String shown(NoteName name) {
        return Shown.name(finder, name.value());
    }

    private static ToolErrorException precondition(String message, String cause, String next) {
        return new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED, message, cause,
            next));
    }
}
