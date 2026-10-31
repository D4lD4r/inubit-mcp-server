package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The configured {@code versionLine} of a node (research D-2, data-model.md → VersionSetting):
 * {@code AUTO}, a line ({@code V6_1}, {@code V8_1}, {@code V9_X}) or a concrete version with at
 * least major.minor.patch. {@link #text()} is the configured spelling.
 */
public sealed interface VersionSetting {

    Auto AUTO = new Auto();
    Line V6_1 = new Line(6, OptionalInt.of(1));
    Line V8_1 = new Line(8, OptionalInt.of(1));
    Line V9_X = new Line(9, OptionalInt.empty());

    /** The value as configured, e.g. {@code AUTO}, {@code V9_X} or {@code 9.0.7-HF1}. */
    String text();

    /**
     * Reads a configured value: {@code AUTO}, {@code V6_1}, {@code V8_1}, {@code V9_X} or a
     * version with a patch (suffix allowed). Empty for anything else; {@code text} is not
     * trimmed.
     */
    static Optional<VersionSetting> parse(String text) {
        return switch (text) {
            case "AUTO" -> Optional.of(AUTO);
            case "V6_1" -> Optional.of(V6_1);
            case "V8_1" -> Optional.of(V8_1);
            case "V9_X" -> Optional.of(V9_X);
            default -> InubitVersion.parse(text)
                .filter(version -> version.patch().isPresent())
                .map(Pinned::new);
        };
    }

    /** The version is detected from the node. */
    record Auto() implements VersionSetting {
        @Override
        public String text() {
            return "AUTO";
        }
    }

    /** A version line; {@code minor} is absent for a whole major line such as {@code V9_X}. */
    record Line(int major, OptionalInt minor) implements VersionSetting {
        public Line {
            Objects.requireNonNull(minor, "minor");
        }

        @Override
        public String text() {
            return "V" + major + "_" + (minor.isPresent() ? minor.getAsInt() : "X");
        }
    }

    /** A concrete version; it must have a patch. */
    record Pinned(InubitVersion version) implements VersionSetting {
        public Pinned {
            Objects.requireNonNull(version, "version");
            if (version.patch().isEmpty()) {
                throw new IllegalArgumentException("A pinned version needs a patch: "
                    + Names.quote(version.raw()));
            }
        }

        @Override
        public String text() {
            return version.raw();
        }
    }
}
