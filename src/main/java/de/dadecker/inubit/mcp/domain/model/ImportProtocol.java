package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What INUBIT reports for one import (feature 004, research D-8, data-model.md →
 * ImportProtocol): one entry per row of StartCLI's {@code --returnProtocol} table and its
 * {@code Total}.
 */
public record ImportProtocol(List<Entry> entries, int total) {

    /** A workflow ({@code Diagram [<name>]}) or a module ({@code Module [<name>]}). */
    public enum Kind {
        WORKFLOW,
        MODULE
    }

    /** {@code was created.} or {@code was modified.} */
    public enum Action {
        CREATED,
        MODIFIED
    }

    /**
     * One row.
     *
     * @param type            the {@code TYPE} column, e.g. {@code INFORMATION}
     * @param diagramOrModule the {@code DIAGRAM/MODULE} column ({@code <workflow>/<module>},
     *                        {@code /<module>} or {@code <workflow>})
     * @param groupOrUser     the {@code GROUP/USER} column (the owner)
     * @param kind            empty if the description is not {@code Module|Diagram [<name>] …}
     */
    public record Entry(String type, String description, String diagramOrModule,
        String groupOrUser, Optional<Kind> kind, Optional<String> name,
        Optional<Action> action) {

        public Entry {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(diagramOrModule, "diagramOrModule");
            Objects.requireNonNull(groupOrUser, "groupOrUser");
            kind = kind == null ? Optional.empty() : kind;
            name = name == null ? Optional.empty() : name;
            action = action == null ? Optional.empty() : action;
        }
    }

    public ImportProtocol {
        entries = List.copyOf(entries);
    }

    /** The names reported as created, in protocol order. */
    public List<String> created() {
        return names(Action.CREATED);
    }

    /** The names reported as modified, in protocol order. */
    public List<String> modified() {
        return names(Action.MODIFIED);
    }

    private List<String> names(Action action) {
        return entries.stream().filter(entry -> entry.action().filter(action::equals)
            .isPresent()).map(entry -> entry.name().orElseThrow()).toList();
    }
}
