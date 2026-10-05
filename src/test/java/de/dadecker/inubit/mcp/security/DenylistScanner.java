package de.dadecker.inubit.mcp.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Scanner behind {@link NoCustomerIdentifiersTest} (research D-11, docs/release-checks.md).
 *
 * <p>The denylist is plain UTF-8 text with one Java regular expression per line; lines starting
 * with {@code #} and empty lines are ignored. Patterns are numbered from 1 in file order. Every
 * text file and every text entry of a ZIP or JAR archive is scanned line by line; binary content
 * (a NUL byte) is skipped. A finding names the file (or {@code archive!entry}), the line and the
 * pattern number, never the matched text. A file or entry name that matches is reported by its
 * position only ({@code <name of scanned file #n>}, {@code <name of entry #k>}), and the findings
 * in its content use that redacted location too.
 */
final class DenylistScanner {

    static final String ENV_DENYLIST = "INUBIT_MCP_DENYLIST";
    static final String LOCAL_DENYLIST = ".denylist";

    /** Local, git-ignored files that name customer values on purpose. */
    private static final Set<String> LOCAL_FILES =
        Set.of(LOCAL_DENYLIST, ".neutralize-map", ".synthetic-map");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target");
    private static final int BINARY_PROBE = 8000;

    private final List<Pattern> patterns;

    private DenylistScanner(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /** One match: {@code path:line pattern #n}; {@code line == 0} means the file name matched. */
    record Finding(String location, int line, int pattern) {

        @Override
        public String toString() {
            return line == 0
                ? location + " pattern #" + pattern
                : location + ":" + line + " pattern #" + pattern;
        }
    }

    /**
     * The file named by {@code INUBIT_MCP_DENYLIST}, else {@code <root>/.denylist}, else none.
     *
     * @throws IllegalStateException if the variable is set but names no file: a mistyped path
     *     must not silently fall back to another denylist or skip the check
     */
    static Optional<Path> locateDenylist(Map<String, String> environment, Path root) {
        String configured = environment.get(ENV_DENYLIST);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.strip());
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                    ENV_DENYLIST + " is set but names no file: " + path);
            }
            return Optional.of(path);
        }
        Path local = root.resolve(LOCAL_DENYLIST);
        return Files.isRegularFile(local) ? Optional.of(local) : Optional.empty();
    }

    static DenylistScanner fromFile(Path denylist) throws IOException {
        return parse(Files.readString(denylist, StandardCharsets.UTF_8));
    }

    static DenylistScanner parse(String denylist) {
        List<Pattern> patterns = new ArrayList<>();
        for (String raw : denylist.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(line));
            } catch (PatternSyntaxException e) {
                // The pattern text is a customer value: report its number only.
                throw new IllegalArgumentException(
                    "invalid regular expression in denylist pattern #" + (patterns.size() + 1),
                    null);
            }
        }
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("the denylist contains no patterns");
        }
        return new DenylistScanner(patterns);
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
                .filter(relative -> !LOCAL_FILES.contains(relative.toString()))
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
     * JAR files are scanned entry by entry. When a file or entry name matches a pattern, it is
     * reported by its position only, and so are the findings in its content.
     */
    List<Finding> scan(Path root, List<String> files) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            String name = files.get(index);
            String location = redactedIfMatching(name,
                "<name of scanned file #" + (index + 1) + ">", "", findings);
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
        boolean matched = false;
        for (int p = 0; p < patterns.size(); p++) {
            if (patterns.get(p).matcher(name).find()) {
                findings.add(new Finding(prefix + redacted, 0, p + 1));
                matched = true;
            }
        }
        return matched ? prefix + redacted : prefix + name;
    }

    private void scanZip(String location, byte[] content, List<Finding> findings)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            int entryIndex = 0;
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entryIndex++;
                String entryLocation = redactedIfMatching(entry.getName(),
                    "<name of entry #" + entryIndex + ">", location + "!", findings);
                if (!entry.isDirectory()) {
                    scanText(entryLocation, readEntry(zip), findings);
                }
            }
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
        String[] lines = new String(content, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (int p = 0; p < patterns.size(); p++) {
                if (patterns.get(p).matcher(lines[i]).find()) {
                    findings.add(new Finding(location, i + 1, p + 1));
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
