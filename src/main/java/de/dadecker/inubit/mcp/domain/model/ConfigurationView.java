package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The server configuration as returned (data-model.md → ConfigurationView): the section names
 * when no section was asked for, otherwise the leaves of one section, masked by
 * {@link SecretRules} (FR-010) and bounded by the result limiter.
 *
 * @param sections    the section names; empty when {@code section} is set
 * @param section     the section asked for
 * @param values      the leaves of {@code section}; empty when no section was asked for
 * @param maskedCount the number of masked leaves in {@code values}
 * @param truncated   true if {@code values} holds fewer leaves than the section has
 */
public record ConfigurationView(List<String> sections, Optional<String> section,
    List<Leaf> values, int maskedCount, boolean truncated) {

    public ConfigurationView {
        sections = List.copyOf(sections);
        Objects.requireNonNull(section, "section");
        values = List.copyOf(values);
        if (section.isPresent() && !sections.isEmpty()) {
            throw new IllegalArgumentException("a view lists sections or shows one section");
        }
        if (section.isEmpty() && !values.isEmpty()) {
            throw new IllegalArgumentException("a section list has no values");
        }
        int counted = countMasked(values);
        if (maskedCount != counted) {
            throw new IllegalArgumentException(
                "maskedCount " + maskedCount + " differs from the " + counted + " masked values");
        }
    }

    /** The section names only. */
    public static ConfigurationView sections(List<String> names) {
        return new ConfigurationView(names, Optional.empty(), List.of(), 0, false);
    }

    /** The leaves of one section; {@code maskedCount} is counted from them. */
    public static ConfigurationView section(String name, List<Leaf> values, boolean truncated) {
        return new ConfigurationView(List.of(), Optional.of(name), values, countMasked(values),
            truncated);
    }

    private static int countMasked(List<Leaf> values) {
        return (int) values.stream().filter(Leaf::masked).count();
    }

    /**
     * One leaf of a section: {@code {path, value}}, or {@code {path, masked: true}}.
     *
     * @param path  the path of the leaf below the section, e.g. {@code /smtp/host}
     * @param value the value; empty when masked
     */
    public record Leaf(String path, Optional<String> value) {

        public Leaf {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        public static Leaf shown(String path, String value) {
            return new Leaf(path, Optional.of(value));
        }

        public static Leaf masked(String path) {
            return new Leaf(path, Optional.empty());
        }

        public boolean masked() {
            return value.isEmpty();
        }
    }
}
