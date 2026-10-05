package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The display names of the two target levels of a profile (002 data-model.md → Terminology), e.g.
 * "Umgebung"/"Umgebungen" and "Knoten"/"Knoten", written as they appear inside an English sentence. They are display text only: keys, field names
 * and tool names stay neutral ({@code group}/{@code node}). Tool descriptions, schema
 * descriptions and messages are templates that {@link #render} turns into display text.
 */
public record Terminology(
    String groupSingular,
    String groupPlural,
    String nodeSingular,
    String nodePlural) {

    /** One display name: 1–32 chars, letters, digits, space, {@code _} or {@code -}. */
    public static final Pattern TERM_PATTERN = Pattern.compile("^\\p{L}[\\p{L}\\p{N} _-]{0,31}$");

    /** A placeholder: letters in braces, e.g. {@code {group}} (research D-4). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\p{Alpha}+)\\}");

    private static final String KNOWN = "{group}, {groups}, {node}, {nodes}, {Group}, {Groups},"
        + " {Node}, {Nodes}";

    /**
     * The terms of a profile without a {@code terminology} section: English nouns as they appear
     * inside a sentence (002 review R2).
     */
    public static final Terminology DEFAULT = new Terminology("group", "groups", "node", "nodes");

    public Terminology {
        requireTerm(groupSingular, "group singular");
        requireTerm(groupPlural, "group plural");
        requireTerm(nodeSingular, "node singular");
        requireTerm(nodePlural, "node plural");
        if (sameTerm(groupSingular, nodeSingular)) {
            throw new IllegalArgumentException("The group and node singular names must differ"
                + " (case-insensitive): " + Names.quote(groupSingular));
        }
        if (sameTerm(groupPlural, nodePlural)) {
            throw new IllegalArgumentException("The group and node plural names must differ"
                + " (case-insensitive): " + Names.quote(groupPlural));
        }
    }

    /**
     * Replaces the term placeholders of {@code template} (research D-4, 002 review R1):
     * {@code {group}}, {@code {groups}}, {@code {node}}, {@code {nodes}} become the configured
     * names exactly; {@code {Group}}, {@code {Groups}}, {@code {Node}}, {@code {Nodes}} upper-case
     * only the first code point (for the start of a sentence or a title). Templates never put
     * "a"/"an" directly before a placeholder and never inflect one ({@code TemplateGrammarTest}),
     * so they read correctly in any language. Braces around anything other than letters (e.g.
     * {@code {0,31}}) are plain text.
     *
     * @throws IllegalArgumentException for any other placeholder, e.g. {@code {stage}}; the
     *     profile name ({@code {profile}}) is rendered by {@link ProfileInfo#render}
     */
    public String render(String template) {
        return render(template, Map.of());
    }

    /** As {@link #render(String)}, with additional placeholder values (exact names). */
    String render(String template, Map<String, String> extra) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = extra.containsKey(name) ? extra.get(name) : term(name);
            if (value == null) {
                throw new IllegalArgumentException("Unknown placeholder {" + name + "} in "
                    + Names.quote(template) + "; known are " + KNOWN
                    + (extra.isEmpty() ? "" : " and " + extra.keySet().stream()
                        .map(key -> "{" + key + "}").sorted().toList()));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The placeholder names in {@code template}, in order, e.g. {@code [group, node]}. */
    public static List<String> placeholders(String template) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    private String term(String placeholder) {
        return switch (placeholder) {
            case "group" -> groupSingular;
            case "groups" -> groupPlural;
            case "node" -> nodeSingular;
            case "nodes" -> nodePlural;
            case "Group" -> capitalised(groupSingular);
            case "Groups" -> capitalised(groupPlural);
            case "Node" -> capitalised(nodeSingular);
            case "Nodes" -> capitalised(nodePlural);
            default -> null;
        };
    }

    private static String capitalised(String term) {
        int first = term.codePointAt(0);
        return new StringBuilder(term.length())
            .appendCodePoint(Character.toTitleCase(first))
            .append(term, Character.charCount(first), term.length())
            .toString();
    }

    /** Returns whether {@code term} is a valid display name. */
    public static boolean isValidTerm(String term) {
        return term != null && TERM_PATTERN.matcher(term).matches();
    }

    /** Returns whether two display names are the same, ignoring case. */
    public static boolean sameTerm(String first, String second) {
        return first.equalsIgnoreCase(second);
    }

    private static void requireTerm(String term, String what) {
        Objects.requireNonNull(term, what);
        if (!isValidTerm(term)) {
            throw new IllegalArgumentException("Invalid " + what + " name " + Names.quote(term)
                + ": expected " + TERM_PATTERN.pattern());
        }
    }
}
