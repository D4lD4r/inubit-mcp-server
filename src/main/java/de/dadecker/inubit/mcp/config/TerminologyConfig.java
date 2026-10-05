package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code terminology} block as written in the YAML: the display names of the group and node
 * levels. An omitted (or {@code null}) level takes the default names
 * ({@link Terminology#DEFAULT}); a given level, also an empty one ({@code group: {}}), needs both
 * {@code singular} and {@code plural} ({@link ConfigValidator}, 002 review G5).
 */
public record TerminologyConfig(Optional<Level> group, Optional<Level> node) {

    public static final TerminologyConfig EMPTY =
        new TerminologyConfig(Optional.empty(), Optional.empty());

    public TerminologyConfig {
        group = Optionals.orEmpty(group);
        node = Optionals.orEmpty(node);
    }

    /** {@code singular}/{@code plural} of one given level. */
    public record Level(Optional<String> singular, Optional<String> plural) {

        public Level {
            singular = Optionals.orEmpty(singular);
            plural = Optionals.orEmpty(plural);
        }
    }

    /** The configured or default group singular name ({@code ""} if a given level lacks it). */
    public String groupSingular() {
        return name(group, Level::singular, Terminology.DEFAULT.groupSingular());
    }

    /** The configured or default group plural name. */
    public String groupPlural() {
        return name(group, Level::plural, Terminology.DEFAULT.groupPlural());
    }

    /** The configured or default node singular name. */
    public String nodeSingular() {
        return name(node, Level::singular, Terminology.DEFAULT.nodeSingular());
    }

    /** The configured or default node plural name. */
    public String nodePlural() {
        return name(node, Level::plural, Terminology.DEFAULT.nodePlural());
    }

    private static String name(Optional<Level> level, Function<Level, Optional<String>> form,
        String defaultName) {
        return level.map(given -> form.apply(given).orElse("")).orElse(defaultName);
    }

    /**
     * The effective terminology, or {@link Terminology#DEFAULT} while the section is invalid
     * (for the texts of the configuration check itself, which reports the problem).
     */
    public Terminology effectiveOrDefault() {
        try {
            return effective();
        } catch (IllegalArgumentException e) {
            return Terminology.DEFAULT;
        }
    }

    /**
     * The effective terminology.
     *
     * @throws IllegalArgumentException if a name is missing or invalid; call this only after
     *     {@link ConfigValidator} reported no errors
     */
    public Terminology effective() {
        return new Terminology(groupSingular(), groupPlural(), nodeSingular(), nodePlural());
    }
}
