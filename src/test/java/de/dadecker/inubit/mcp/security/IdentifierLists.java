package de.dadecker.inubit.mcp.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The local identifier lists behind {@link NoCustomerIdentifiersTest} (docs/release-checks.md).
 * They name customer values, so they are never committed.
 *
 * <ul>
 *   <li><b>denylist</b>: one Java regular expression per line;</li>
 *   <li><b>neutralize-map</b> and <b>synthetic-map</b>: one rule per line,
 *       {@code <python-regex><TAB><replacement>} (the format of {@code tools/neutralize.py}); only
 *       the regular expression is used, translated by {@link PythonRegex}.</li>
 * </ul>
 *
 * <p>Lines starting with {@code #} and empty lines are ignored; rules are numbered from 1 in file
 * order, like {@code tools/neutralize.py} does. Each list is looked up in this order (first match
 * wins): its environment variable, its file in the repository root, its file in the default
 * directory ({@code ~/.config/inubit-mcp/}, on Windows {@code %APPDATA%\inubit-mcp\}). An optional
 * allowlist of exact values that are not reported is looked up the same way.
 *
 * <p>Errors name the list, the rule number and the line number, never the rule text.
 */
final class IdentifierLists {

    static final String ENV_ALLOWLIST = "INUBIT_MCP_IDENTIFIER_ALLOWLIST";
    static final String LOCAL_ALLOWLIST = ".identifier-allowlist";
    static final String DEFAULT_ALLOWLIST = "identifier-allowlist";
    static final String DEFAULT_DIRECTORY_NAME = "inubit-mcp";

    /** The three lists, their names and where they are looked up. */
    enum Kind {
        DENYLIST("denylist", "INUBIT_MCP_DENYLIST", ".denylist", "denylist.txt"),
        NEUTRALIZE_MAP("neutralize-map", "INUBIT_MCP_NEUTRALIZE_MAP", ".neutralize-map",
            "neutralize-map"),
        SYNTHETIC_MAP("synthetic-map", "INUBIT_MCP_SYNTHETIC_MAP", ".synthetic-map",
            "synthetic-map");

        private final String label;
        private final String environmentVariable;
        private final String repositoryFile;
        private final String defaultFile;

        Kind(String label, String environmentVariable, String repositoryFile,
                String defaultFile) {
            this.label = label;
            this.environmentVariable = environmentVariable;
            this.repositoryFile = repositoryFile;
            this.defaultFile = defaultFile;
        }

        String label() {
            return label;
        }

        String environmentVariable() {
            return environmentVariable;
        }

        String repositoryFile() {
            return repositoryFile;
        }

        String defaultFile() {
            return defaultFile;
        }

        boolean isMap() {
            return this != DENYLIST;
        }
    }

    /** One forbidden pattern: rule {@code number} of {@code list}, written on {@code line}. */
    record Rule(Kind list, int number, int line, Pattern pattern,
            Optional<LiteralIndex.Literal> literal) {
    }

    /** The rules of one list. */
    record Parsed(Kind kind, List<Rule> rules) {
    }

    /** Local files that name customer values on purpose; never scanned, never committed. */
    static final Set<String> LOCAL_FILES = Set.of(Kind.DENYLIST.repositoryFile(),
        Kind.NEUTRALIZE_MAP.repositoryFile(), Kind.SYNTHETIC_MAP.repositoryFile(),
        LOCAL_ALLOWLIST);

    private IdentifierLists() {
    }

    // --- location -----------------------------------------------------------------------------

    /**
     * The default directory: {@code %APPDATA%\inubit-mcp} on Windows, else
     * {@code $HOME/.config/inubit-mcp} ({@code user.home} when {@code HOME} is unset). {@code
     * HOME} wins over {@code user.home} so that a build with another {@code HOME} (CI, tests) does
     * not pick up the lists of the account it runs under.
     */
    static Path defaultDirectory(Map<String, String> environment, String osName,
            String userHome) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            String appData = environment.get("APPDATA");
            return appData != null && !appData.isBlank()
                ? Path.of(appData.strip(), DEFAULT_DIRECTORY_NAME)
                : Path.of(userHome, "AppData", "Roaming", DEFAULT_DIRECTORY_NAME);
        }
        String home = environment.get("HOME");
        return Path.of(home != null && !home.isBlank() ? home.strip() : userHome, ".config",
            DEFAULT_DIRECTORY_NAME);
    }

    /**
     * The file of one list: the file named by its environment variable, else its file in
     * {@code root}, else its file in {@code defaultDirectory}, else none.
     *
     * @throws IllegalStateException if the variable is set but names no file: a mistyped path
     *     must not silently fall back to another list or skip the check
     */
    static Optional<Path> locate(Kind kind, Map<String, String> environment, Path root,
            Path defaultDirectory) {
        return locate(kind.environmentVariable(), kind.repositoryFile(), kind.defaultFile(),
            environment, root, defaultDirectory);
    }

    private static Optional<Path> locate(String variable, String repositoryFile,
            String defaultFile, Map<String, String> environment, Path root,
            Path defaultDirectory) {
        String configured = environment.get(variable);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.strip());
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(variable + " is set but names no file: " + path);
            }
            return Optional.of(path);
        }
        for (Path candidate : List.of(root.resolve(repositoryFile),
                defaultDirectory.resolve(defaultFile))) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Locates all three lists and the allowlist. */
    static Located locateAll(Map<String, String> environment, Path root, Path defaultDirectory) {
        Map<Kind, Optional<Path>> lists = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            lists.put(kind, locate(kind, environment, root, defaultDirectory));
        }
        Optional<Path> allowlist = locate(ENV_ALLOWLIST, LOCAL_ALLOWLIST, DEFAULT_ALLOWLIST,
            environment, root, defaultDirectory);
        return new Located(lists, allowlist, root, defaultDirectory);
    }

    /** The result of {@link #locateAll}: which lists exist (paths only, never contents). */
    record Located(Map<Kind, Optional<Path>> lists, Optional<Path> allowlist, Path root,
            Path defaultDirectory) {

        boolean anyList() {
            return lists.values().stream().anyMatch(Optional::isPresent);
        }

        List<Kind> missing() {
            return lists.entrySet().stream().filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey).toList();
        }

        /** Why the check is skipped: every missing list and where it was looked for. */
        String skipMessage() {
            return "no identifier list found, release check skipped (looked for "
                + missing().stream().map(kind -> kind.label() + ": $"
                    + kind.environmentVariable() + ", " + root.resolve(kind.repositoryFile())
                    + ", " + defaultDirectory.resolve(kind.defaultFile()))
                    .collect(Collectors.joining("; "))
                + "; see docs/release-checks.md)";
        }

        /** Rule counts per list and the missing lists; no rule text. */
        String summary(IdentifierScanner scanner) {
            List<String> parts = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                int count = scanner.ruleCount(kind);
                parts.add(lists.get(kind).isPresent()
                    ? kind.label() + ": " + count + (count == 1 ? " rule" : " rules")
                    : kind.label() + ": missing");
            }
            parts.add("allowlist: " + (allowlist.isPresent()
                ? scanner.allowedCount() + " value(s)" : "none"));
            return "identifier lists (" + String.join(", ", parts) + ")";
        }

        /** Reads and parses the lists that exist. */
        IdentifierScanner load() throws IOException {
            List<Parsed> parsed = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                Optional<Path> file = lists.get(kind);
                if (file.isPresent()) {
                    parsed.add(parse(kind, Files.readString(file.get(), StandardCharsets.UTF_8)));
                }
            }
            Set<String> allowed = allowlist.isPresent()
                ? parseAllowlist(Files.readString(allowlist.get(), StandardCharsets.UTF_8))
                : Set.of();
            return IdentifierScanner.of(parsed.toArray(Parsed[]::new)).allowing(allowed);
        }
    }

    // --- parsing ------------------------------------------------------------------------------

    /** Parses one list; the error messages name rule and line numbers, never the rule text. */
    static Parsed parse(Kind kind, String text) {
        List<Rule> rules = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = stripCarriageReturn(lines[index]);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int number = rules.size() + 1;
            String where = kind.label() + " rule " + number + " (line " + (index + 1) + ")";
            String regex = line;
            if (kind.isMap()) {
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    throw new IllegalArgumentException(
                        where + ": expected <regex><TAB><replacement>");
                }
                regex = line.substring(0, tab);
            }
            Pattern pattern;
            try {
                pattern = kind.isMap() ? PythonRegex.compile(regex) : Pattern.compile(regex);
            } catch (IllegalArgumentException e) {
                // The rule text is a customer value: report its position only (the message of a
                // PatternSyntaxException quotes the pattern, PythonRegex's messages do not).
                String reason = kind.isMap() ? e.getMessage() : "invalid regular expression";
                throw new IllegalArgumentException(where + ": " + reason, null);
            }
            rules.add(new Rule(kind, number, index + 1, pattern,
                LiteralIndex.requiredLiteral(pattern.pattern())));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("the " + kind.label() + " contains no rules");
        }
        return new Parsed(kind, List.copyOf(rules));
    }

    /** The allowlist: one exact value per line (surrounding blanks removed). */
    static Set<String> parseAllowlist(String text) {
        Set<String> values = new HashSet<>();
        for (String raw : text.split("\n", -1)) {
            String value = raw.strip();
            if (!value.isEmpty() && !value.startsWith("#")) {
                values.add(value);
            }
        }
        return Set.copyOf(values);
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
