package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.application.SimilarNames;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Listing;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.NoteFile;
import de.dadecker.inubit.mcp.application.knowledge.KnowledgeDirectory.Skipped;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NoteName;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets;
import de.dadecker.inubit.mcp.domain.port.ConfiguredSecrets.Readiness;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lists, searches and reads the notes of the profile's knowledge (feature 019, research D-9).
 * Every call walks and reads the directory again (FR-009). Nothing is shown before every
 * configured credential of the profile is known (D-7, option A), and every text is masked before
 * it is searched, cut or paged (FR-008a).
 */
public final class KnowledgeService {

    static final String BY_HAND = "by hand";
    static final String MALFORMED = "metadata block is malformed; fix it by hand";
    static final String MASKED_MALFORMED =
        "the metadata block holds a secret and is shown as content";
    static final String NAME_RULE =
        "the assistant cannot write to this note (name outside the record rule)";
    static final String NAME_SECRET =
        "the assistant cannot write to this note (secret in the name)";
    static final String SAME_NAME =
        "another file has the same name in another Unicode form; use ref";
    static final int MAX_WARNINGS = 20;
    static final int MAX_SIMILAR = 5;

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int EXCERPT_BEFORE = 100;
    private static final Pattern LINE_BREAK =
        Pattern.compile("\\r\\n|[\\n\\r\\u2028\\u2029\\u0085]");

    private final KnowledgeDirectory directory;
    private final ConfiguredSecrets secrets;
    private final SecretFinder finder;
    private final NoteRefs refs;
    private final Duration prepareBound;

    /**
     * @param prepareBound how long a call waits for the credentials of the profile (D-7)
     */
    public KnowledgeService(KnowledgeDirectory directory, ConfiguredSecrets secrets, NoteRefs refs,
        Duration prepareBound) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.finder = new SecretFinder(secrets);
        this.refs = Objects.requireNonNull(refs, "refs");
        this.prepareBound = Objects.requireNonNull(prepareBound, "prepareBound");
    }

    /**
     * One page of the notes that match {@code search}, newest first, then by name.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if the credentials are not known
     *                            yet or the directory is not usable
     */
    public SearchPage search(Search search) {
        Objects.requireNonNull(search, "search");
        ready();
        Listing listing = list();
        Set<String> shared = sharedNames(listing);
        Optional<Pattern> query = search.query()
            .map(text -> Pattern.compile(Pattern.quote(text),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        Optional<String> topic = search.topic().map(NoteName::fold);
        int end = search.offset() + search.limit();
        List<Hit> hits = new ArrayList<>();
        int total;
        if (query.isEmpty() && topic.isEmpty()) {
            total = listing.files().size();
            for (int i = search.offset(); i < Math.min(end, total); i++) {
                View view = view(directory.read(listing.files().get(i)));
                hits.add(hit(view, shared, OptionalInt.empty()));
            }
        } else {
            total = 0;
            for (NoteFile file : listing.files()) {
                Note note = directory.read(file);
                if (note.state() != Note.State.OK) {
                    continue;
                }
                View view = view(note);
                OptionalInt at = matches(view, query, topic);
                if (at.isEmpty()) {
                    continue;
                }
                if (total >= search.offset() && total < end) {
                    hits.add(hit(view, shared, at));
                }
                total++;
            }
        }
        LOG.debug("knowledge.read notes={} hits={}", listing.files().size(), total);
        OptionalInt next = end < total ? OptionalInt.of(end) : OptionalInt.empty();
        return new SearchPage(hits, search.offset(), search.limit(), total, next,
            warnings(listing));
    }

    /**
     * The page of the note {@code target} from {@code offset}, a character offset into the masked
     * body (moved back to the start of a surrogate pair).
     *
     * @throws ToolErrorException {@code NOT_FOUND}, {@code CONFLICT}, {@code INVALID_INPUT} (offset
     *                            beyond the content) or {@code PRECONDITION_FAILED}
     */
    public NotePage read(NoteSelector target, int offset) {
        Objects.requireNonNull(target, "target");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        ready();
        Listing listing = list();
        NoteFile file = find(target, listing);
        Note note = directory.read(file);
        String name = Shown.of(finder, file.name().value());
        if (note.state() != Note.State.OK) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The note " + Names.quote(name) + " cannot be read: "
                    + note.problem().orElse("unknown cause"),
                "The note is too large or not a readable UTF-8 file",
                "Shorten or repair the note by hand, or read another note"));
        }
        View view = view(note);
        String body = view.body();
        if (offset > body.length()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "offset " + offset + " is beyond the content of the note " + Names.quote(name)
                    + " (" + body.length() + " characters)",
                "The offset is not a nextOffset of this note, or the note has changed",
                "Read the note again from offset 0"));
        }
        int start = offset;
        if (start > 0 && start < body.length() && Character.isLowSurrogate(body.charAt(start))
            && Character.isHighSurrogate(body.charAt(start - 1))) {
            start--;
        }
        String content;
        OptionalInt next;
        if (body.length() - start <= KnowledgeLimits.PAGE_CHARS) {
            content = body.substring(start);
            next = OptionalInt.empty();
        } else {
            int cut = start + KnowledgeLimits.PAGE_CHARS - ItemBounds.TRUNCATION_MARKER.length();
            if (Character.isHighSurrogate(body.charAt(cut - 1))) {
                cut--;
            }
            content = body.substring(start, cut) + ItemBounds.TRUNCATION_MARKER;
            next = OptionalInt.of(cut);
        }
        LOG.debug("knowledge.read notes={} hits=1 name={}", listing.files().size(), name);
        return new NotePage(view.name(), view.title(), view.topics(), view.source(),
            view.lastWriter(), view.lastChange(), view.versionToken().orElseThrow(),
            view.masked(), body.length(), start, content, next,
            noteWarnings(view, sharedNames(listing)));
    }

    /** What a note shows: everything masked, reduced where it is a name or a value. */
    private record View(Note note, String name, String title, List<String> topics,
        String source, Optional<String> lastWriter, Instant lastChange,
        Optional<String> versionToken, int masked, String body, String matchName,
        String matchTitle, List<String> matchTopics, boolean nameSecret,
        boolean maskedMalformed) {
    }

    private View view(Note note) {
        NoteFile file = note.file();
        String rawName = file.name().value();
        String maskedName = finder.mask(rawName).text();
        boolean nameSecret = !maskedName.equals(rawName);
        String fileName = Shown.of(finder, file.name().lastSegment());
        Instant lastChange = file.modified().toInstant().truncatedTo(ChronoUnit.SECONDS);
        if (note.state() != Note.State.OK || note.text().isEmpty()) {
            return new View(note, Shown.cut(Shown.line(maskedName)), fileName, List.of(), BY_HAND,
                Optional.empty(), lastChange, Optional.empty(), 0, "", maskedName,
                fileName, List.of(), nameSecret, false);
        }
        Masked masked = finder.mask(note.text().get());
        NoteMetadata metadata = NoteMetadata.parse(masked.text());
        String body = masked.text().substring(metadata.bodyStart());
        List<String> matchTopics = metadata.topics().stream()
            .map(topic -> finder.mask(topic).text()).toList();
        String matchTitle = metadata.title().map(title -> finder.mask(title).text())
            .orElse(fileName);
        return new View(note, Shown.cut(Shown.line(maskedName)),
            metadata.title().map(title -> Shown.of(finder, title)).orElse(fileName),
            metadata.topics().stream().map(topic -> Shown.of(finder, topic)).toList(),
            metadata.source().map(source -> Shown.of(finder, source)).orElse(BY_HAND),
            metadata.lastWriter().map(writer -> Shown.of(finder, writer)), lastChange,
            Optional.of(VersionToken.of(file.modified(), masked.text())), masked.count(), body,
            maskedName, matchTitle, matchTopics, nameSecret,
            metadata.malformed() && !note.metadata().malformed());
    }

    /** The position of the first query match in the body, -1 if only elsewhere; empty if none. */
    private static OptionalInt matches(View view, Optional<Pattern> query, Optional<String> topic) {
        if (topic.isPresent()
            && view.matchTopics().stream().map(NoteName::fold).noneMatch(topic.get()::equals)) {
            return OptionalInt.empty();
        }
        if (query.isEmpty()) {
            return OptionalInt.of(-1);
        }
        Matcher body = query.get().matcher(view.body());
        if (body.find()) {
            return OptionalInt.of(body.start());
        }
        Pattern pattern = query.get();
        boolean elsewhere = pattern.matcher(view.matchName()).find()
            || pattern.matcher(view.matchTitle()).find()
            || view.matchTopics().stream().anyMatch(value -> pattern.matcher(value).find());
        return elsewhere ? OptionalInt.of(-1) : OptionalInt.empty();
    }

    private Hit hit(View view, Set<String> shared, OptionalInt match) {
        Optional<String> excerpt = view.versionToken().isEmpty() ? Optional.empty()
            : Optional.of(excerpt(view.body(), match.orElse(-1)));
        return new Hit(view.name(), refs.of(view.note().file().relative()), view.title(),
            view.topics(), view.lastChange(), view.source(), view.versionToken(), excerpt,
            view.masked(), writable(view), noteWarnings(view, shared));
    }

    /** At most 300 characters of the body from 100 before {@code match}, as one line. */
    static String excerpt(String body, int match) {
        int start = match < 0 ? 0 : Math.max(0, match - EXCERPT_BEFORE);
        if (start > 0 && Character.isLowSurrogate(body.charAt(start))
            && Character.isHighSurrogate(body.charAt(start - 1))) {
            start--;
        }
        int window = Math.min(body.length(), start + 2 * KnowledgeLimits.EXCERPT_CHARS);
        String line = LINE_BREAK.matcher(body.substring(start, window)).replaceAll(" ");
        if (line.length() <= KnowledgeLimits.EXCERPT_CHARS) {
            return line;
        }
        int end = KnowledgeLimits.EXCERPT_CHARS;
        if (Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static boolean writable(View view) {
        return view.note().state() == Note.State.OK && view.note().file().name().writable()
            && !view.nameSecret() && !view.note().metadata().malformed();
    }

    private static List<String> noteWarnings(View view, Set<String> shared) {
        List<String> warnings = new ArrayList<>();
        view.note().problem().ifPresent(warnings::add);
        if (view.note().state() == Note.State.OK && view.note().metadata().malformed()) {
            warnings.add(MALFORMED);
        } else if (view.maskedMalformed()) {
            warnings.add(MASKED_MALFORMED);
        }
        if (!view.note().file().name().writable()) {
            warnings.add(NAME_RULE);
        } else if (view.nameSecret()) {
            warnings.add(NAME_SECRET);
        }
        if (shared.contains(view.note().file().name().value())) {
            warnings.add(SAME_NAME);
        }
        return warnings;
    }

    /** Names that more than one listed file has (equal after NFC). */
    private static Set<String> sharedNames(Listing listing) {
        Map<String, Integer> counts = new HashMap<>();
        listing.files().forEach(file -> counts.merge(file.name().value(), 1, Integer::sum));
        return counts.entrySet().stream().filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private List<String> warnings(Listing listing) {
        List<String> all = new ArrayList<>(listing.warnings());
        listing.skipped().stream()
            .sorted(Comparator.comparing(skipped -> slashed(skipped.relative())))
            .forEach(skipped -> all.add(skipped(skipped)));
        if (all.size() <= MAX_WARNINGS) {
            return all;
        }
        List<String> bounded = new ArrayList<>(all.subList(0, MAX_WARNINGS));
        bounded.add("… and " + (all.size() - MAX_WARNINGS) + " more");
        return bounded;
    }

    private String skipped(Skipped skipped) {
        String name = Shown.of(finder, slashed(skipped.relative()));
        return switch (skipped.reason()) {
            case LINK -> "symbolic link " + name + " is ignored";
            case UNREADABLE -> name + " is skipped: it cannot be read";
        };
    }

    private static String slashed(Path relative) {
        List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }

    private NoteFile find(NoteSelector target, Listing listing) {
        return switch (target) {
            case NoteSelector.ByRef byRef -> refs.resolve(byRef.ref(), listing).orElseThrow(
                () -> new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                    "No listed note has this ref; list the notes again",
                    "The ref comes from an earlier run of the server, or the note was moved or"
                        + " deleted",
                    "List the notes with read_knowledge and use the ref of the new hit")));
            case NoteSelector.ByName byName -> byName(byName.name(), listing);
        };
    }

    private NoteFile byName(String given, Listing listing) {
        String wanted = Normalizer.normalize(given, Normalizer.Form.NFC);
        List<NoteFile> found = listing.files().stream()
            .filter(file -> file.name().value().equals(wanted)).toList();
        String shown = Shown.of(finder, wanted);
        if (found.size() > 1) {
            throw new ToolErrorException(ToolError.of(ErrorCode.CONFLICT,
                "The name " + Names.quote(shown) + " is ambiguous: several notes have this name"
                    + " in different Unicode forms",
                "Two files have names that are equal after Unicode normalization (NFC)",
                "List the notes with read_knowledge and read one by its ref; use ref"));
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        List<String> similar = similar(wanted, listing);
        throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
            "No note named " + Names.quote(shown) + " in the local knowledge"
                + (similar.isEmpty() ? ""
                    : "; similar names: " + similar.stream().map(Names::quote)
                        .collect(Collectors.joining(", "))),
            "The name is misspelled (names are case-sensitive and have no .md), or the note was"
                + " moved or deleted",
            "List or search the notes with read_knowledge and use the name or ref of a hit"));
    }

    /** Up to five similar names, masked and cut: equal ignoring case first, then by distance. */
    private List<String> similar(String wanted, Listing listing) {
        List<String> names = listing.files().stream().map(file -> file.name().value()).toList();
        String folded = NoteName.fold(wanted);
        Set<String> chosen = new LinkedHashSet<>();
        names.stream().filter(name -> NoteName.fold(name).equals(folded)).sorted()
            .forEach(chosen::add);
        chosen.addAll(SimilarNames.of(wanted, names, MAX_SIMILAR));
        return chosen.stream().limit(MAX_SIMILAR).map(name -> Shown.of(finder, name))
            .distinct().toList();
    }

    private Listing list() {
        try {
            return directory.list();
        } catch (IOException e) {
            String problem = switch (e) {
                case NotDirectoryException ignored -> "not a directory";
                case AccessDeniedException ignored -> "not readable";
                default -> "cannot be walked: " + e.getClass().getSimpleName();
            };
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The knowledge directory of this profile is not usable now (" + problem + ")",
                "The configured knowledge directory is a file, has no read permission, or the"
                    + " walk failed",
                "Check the knowledge directory with --check-config"));
        }
    }

    private void ready() {
        if (secrets.prepare(prepareBound) instanceof Readiness.NotReady notReady) {
            String nodes = notReady.pending().stream()
                .map(p -> p.node() + " (" + p.store() + ": " + p.state() + ")")
                .collect(Collectors.joining(", "));
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The credentials of " + nodes + " have not been read yet",
                "Notes are shown only after all of the profile's credentials are known, so that a"
                    + " password written in a note is masked",
                "Answer the credential store's access dialog (or restore access to AWS Parameter"
                    + " Store), then call again"));
        }
    }
}
