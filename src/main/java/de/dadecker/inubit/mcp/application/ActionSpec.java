package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * One action of a tool (feature 022, research D-2 "Gate per call", D-12, data-model.md →
 * ActionSpec): what its calls send, how they are guarded, and what they report.
 *
 * @param action        the action key of research D-2: the value of the tool's {@code action},
 *     {@code view} or {@code content} input, or {@code default}
 * @param capability    the tool's capability, for the capability admission of
 *     {@link VersionGate}
 * @param area          the administration area whose opt-in a change needs (D-12 step 4)
 * @param callEntries   every coverage entry a call of this action may send, over all its
 *     variants; empty for an action that only reads {@code parts}
 * @param variant       the entries one concrete call sends, chosen by its input (e.g.
 *     {@code moduleId} or {@code moduleName}); a subset of {@code callEntries}
 * @param parts         the parts of a multi-part read, each with its entries and channel; empty
 *     otherwise
 * @param bulk          whether one call changes several objects
 * @param backup        what is stored before the change (research D-15); empty for none
 * @param restartNotice whether the result says that INUBIT must be restarted (research D-23)
 */
public record ActionSpec(String tool, String action, Capability capability, Area area,
    Kind kind, Set<CoverageKey> callEntries,
    Function<Map<String, Object>, Set<CoverageKey>> variant, Set<Part> parts, boolean bulk,
    Optional<BackupKind> backup, RestartNotice restartNotice) {

    /** The area of research D-12; {@code READ} for the read tools, which need no opt-in. */
    public enum Area {
        OPERATION,
        OBJECTS,
        LIBRARIES,
        SETTINGS,
        USERS,
        RUNTIME_DATA,
        READ
    }

    /** What a call does; {@code RUNTIME_READ} is a run-time data read under its opt-in (D-12). */
    public enum Kind {
        READ,
        CHANGE,
        RUNTIME_READ
    }

    /** The backups of research D-15. */
    public enum BackupKind {
        /** StartCLI export of the workflow or module, history included. */
        OBJECT_EXPORT,
        /** {@code export --exportMetadata} of the named workflows. */
        METADATA_EXPORT,
        /** {@code export --exportRepositoryPath}. */
        REPOSITORY_EXPORT,
        /** JSON list of the objects and versions carrying a tag. */
        TAG_LIST
    }

    /** Whether a result names a needed restart: never, always, or depending on the setting. */
    public enum RestartNotice {
        NONE,
        ALWAYS,
        BY_SETTING
    }

    public ActionSpec {
        requireText(tool, "tool");
        requireText(action, "action");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(kind, "kind");
        callEntries = Set.copyOf(callEntries);
        Objects.requireNonNull(variant, "variant");
        parts = Set.copyOf(parts);
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(restartNotice, "restartNotice");
    }

    /** The entries the call with {@code input} sends (its variant). */
    public Set<CoverageKey> entries(Map<String, Object> input) {
        return variant.apply(input);
    }

    /**
     * Every entry the action declares: {@code callEntries} and the entries of every part
     * (coverage check 6).
     */
    public Set<CoverageKey> declaredEntries() {
        Set<CoverageKey> declared = new HashSet<>(callEntries);
        parts.forEach(part -> declared.addAll(part.entries()));
        return Set.copyOf(declared);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty " + name);
        }
    }
}
