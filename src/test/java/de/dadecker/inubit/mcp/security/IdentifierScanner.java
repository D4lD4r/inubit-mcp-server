package de.dadecker.inubit.mcp.security;

import de.dadecker.inubit.mcp.security.IdentifierLists.Kind;
import de.dadecker.inubit.mcp.security.IdentifierLists.Parsed;
import de.dadecker.inubit.mcp.security.IdentifierLists.Rule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Scanner behind {@link NoCustomerIdentifiersTest} (research D-11, docs/release-checks.md): the
 * rules of the denylist and of both maps ({@link IdentifierLists}) against the repository.
 *
 * <p>Every text file and every text entry of a ZIP or JAR archive is scanned line by line; binary
 * content (a NUL byte) is skipped. A finding names the file (or {@code archive!entry}), the line,
 * the list and the rule number and shows the match {@linkplain #mask masked}, never in full. A
 * file or entry name that matches is reported by its position only ({@code <name of scanned file
 * #n>}, {@code <name of entry #k>}), and the findings in its content use that redacted location
 * too. A match whose complete text is on the allowlist is not reported.
 *
 * <p>Speed: one {@link LiteralIndex} pass per text selects the rules whose required literal
 * occurs in it; only those (and the rules without a literal) are run, line by line.
 */
final class IdentifierScanner {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target");
    private static final int BINARY_PROBE = 8000;

    private final List<Rule> rules;
    private final LiteralIndex index;
    /** For each literal of {@link #index}: the rule it belongs to. */
    private final int[] ruleOfLiteral;
    private final BitSet alwaysRun = new BitSet();
    private final Set<String> allowed;

    private IdentifierScanner(IdentifierScanner other, Set<String> allowed) {
        this.rules = other.rules;
        this.index = other.index;
        this.ruleOfLiteral = other.ruleOfLiteral;
        this.alwaysRun.or(other.alwaysRun);
        this.allowed = Set.copyOf(allowed);
    }

    private IdentifierScanner(List<Rule> rules, Set<String> allowed) {
        this.rules = List.copyOf(rules);
        this.allowed = Set.copyOf(allowed);
        List<LiteralIndex.Literal> literals = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int r = 0; r < this.rules.size(); r++) {
            Optional<LiteralIndex.Literal> literal = this.rules.get(r).literal();
            if (literal.isPresent()) {
                literals.add(literal.get());
                owners.add(r);
            } else {
                alwaysRun.set(r);
            }
        }
        this.index = new LiteralIndex(literals);
        this.ruleOfLiteral = owners.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A scanner for the given lists, in this order (the guard uses denylist, maps). */
    static IdentifierScanner of(Parsed... lists) {
        List<Rule> all = new ArrayList<>();
        for (Parsed list : lists) {
            all.addAll(list.rules());
        }
        return new IdentifierScanner(all, Set.of());
    }

    /** The same rules; matches whose complete text is one of {@code values} are not reported. */
    IdentifierScanner allowing(Set<String> values) {
        return new IdentifierScanner(this, values); // same rules and prefilter, built once
    }

    int ruleCount() {
        return rules.size();
    }

    int ruleCount(Kind kind) {
        return (int) rules.stream().filter(rule -> rule.list() == kind).count();
    }

    int allowedCount() {
        return allowed.size();
    }

    /**
     * One match: {@code location:line: list rule n: masked}; {@code line == 0} means the file or
     * entry name matched.
     */
    record Finding(String location, int line, String list, int rule, String masked) {

        @Override
        public String toString() {
            return (line == 0 ? location : location + ":" + line) + ": " + list + " rule " + rule
                + ": " + masked;
        }
    }

    /**
     * The masked form of a match: at most its first two code points (one for 3 to 5, none for
     * shorter matches), an ellipsis and its length in code points, e.g. {@code Gl…(14)}.
     */
    static String mask(String match) {
        int length = match.codePointCount(0, match.length());
        int shown = length >= 6 ? 2 : length >= 3 ? 1 : 0;
        return match.substring(0, match.offsetByCodePoints(0, shown)) + "…(" + length + ")";
    }

    /**
     * The files of the working tree that git tracks or would track: {@code git ls-files --cached
     * --others --exclude-standard} (new, not yet added files count, ignored ones do not). Falls
     * back to {@link #walkTree(Path)} when {@code root} is not a git checkout.
     */
    static List<String> repositoryFiles(Path root) throws IOException {
        Optional<List<String>> tracked = gitFiles(root);
        return tracked.isPresent() ? tracked.get() : walkTree(root);
    }

    /** Regular files below {@code root} except {@code .git/}, {@code target/}, local lists. */
    static List<String> walkTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path))
                .filter(relative -> !SKIPPED_DIRECTORIES.contains(relative.getName(0).toString()))
                .filter(relative -> !IdentifierLists.LOCAL_FILES.contains(relative.toString()))
                .map(relative -> relative.toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    private static Optional<List<String>> gitFiles(Path root) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(
                    "git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException e) {
            return Optional.empty();
        }
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git ls-files timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while listing files", e);
        }
        if (process.exitValue() != 0) {
            return Optional.empty();
        }
        List<String> files = new ArrayList<>();
        for (String name : new String(output, StandardCharsets.UTF_8).split("\0")) {
            if (!name.isEmpty()) {
                files.add(name);
            }
        }
        return Optional.of(files.stream().distinct().sorted().toList());
    }

    /** The JARs of a previous build ({@code target/*.jar}), relative to {@code root}. */
    static List<String> builtJars(Path root) throws IOException {
        Path target = root.resolve("target");
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(target)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }
    }

    /**
     * Scans the given files (paths relative to {@code root}); missing files are ignored. ZIP and
     * JAR files are scanned entry by entry. When a file or entry name matches a rule, it is
     * reported by its position only, and so are the findings in its content.
     */
    List<Finding> scan(Path root, List<String> files) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (int position = 0; position < files.size(); position++) {
            String name = files.get(position);
            String location = redactedIfMatching(name,
                "<name of scanned file #" + (position + 1) + ">", "", findings);
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            byte[] content = Files.readAllBytes(file);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
                scanZip(location, content, findings);
            } else {
                scanText(location, content, findings);
            }
        }
        return findings;
    }

    /** Reports a matching name as {@code redacted} and returns the location to use for it. */
    private String redactedIfMatching(String name, String redacted, String prefix,
            List<Finding> findings) {
        List<Finding> hits = new ArrayList<>();
        scanLines(prefix + redacted, name, new String[] {name}, true, hits);
        findings.addAll(hits);
        return hits.isEmpty() ? prefix + name : prefix + redacted;
    }

    private void scanZip(String location, byte[] content, List<Finding> findings)
            throws IOException {
        int entryIndex = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entryIndex++;
                String entryLocation = redactedIfMatching(entry.getName(),
                    "<name of entry #" + entryIndex + ">", location + "!", findings);
                if (!entry.isDirectory()) {
                    scanText(entryLocation, readEntry(zip), findings);
                }
            }
        }
        if (entryIndex == 0) {
            // Not a ZIP (or an empty one): ZipInputStream just finds no entry. Scan it as a file.
            scanText(location, content, findings);
        }
    }

    private static byte[] readEntry(ZipInputStream zip) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            zip.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void scanText(String location, byte[] content, List<Finding> findings) {
        if (isBinary(content)) {
            return;
        }
        String text = new String(content, StandardCharsets.UTF_8);
        scanLines(location, text, text.split("\n", -1), false, findings);
    }

    /**
     * Runs the candidate rules over each line. The candidates are the rules whose literal occurs
     * somewhere in the lines (one Aho-Corasick pass) plus the rules without a literal; per line a
     * candidate runs only if its literal occurs in that line.
     */
    private void scanLines(String location, String text, String[] lines, boolean name,
            List<Finding> findings) {
        BitSet candidates = (BitSet) alwaysRun.clone();
        for (int literal : index.find(text)) {
            candidates.set(ruleOfLiteral[literal]);
        }
        if (candidates.isEmpty()) {
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String folded = null;
            for (int r = candidates.nextSetBit(0); r >= 0; r = candidates.nextSetBit(r + 1)) {
                Rule rule = rules.get(r);
                if (rule.literal().isPresent()) {
                    LiteralIndex.Literal literal = rule.literal().get();
                    if (literal.folded() && folded == null) {
                        folded = LiteralIndex.fold(line);
                    }
                    if (!(literal.folded() ? folded : line).contains(literal.text())) {
                        continue;
                    }
                }
                // After an allowlisted match the search goes on at the next position, so an
                // overlapping match of the same rule is found too.
                Matcher matcher = rule.pattern().matcher(line);
                int from = 0;
                while (from <= line.length() && matcher.find(from)) {
                    if (!allowed.contains(matcher.group())) {
                        findings.add(new Finding(location, name ? 0 : i + 1, rule.list().label(),
                            rule.number(), mask(matcher.group())));
                        break;
                    }
                    from = matcher.start() + 1;
                }
            }
        }
    }

    private static boolean isBinary(byte[] content) {
        int probe = Math.min(content.length, BINARY_PROBE);
        for (int i = 0; i < probe; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
