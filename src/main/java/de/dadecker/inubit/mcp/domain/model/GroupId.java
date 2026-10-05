package de.dadecker.inubit.mcp.domain.model;

import java.util.regex.Pattern;

/** Identifier of a stage, e.g. {@code int}. */
public record GroupId(String value) {

    /** Pattern for stage and server names (data-model.md → GroupConfig / NodeConfig). */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    public GroupId {
        if (!isValidName(value)) {
            throw new IllegalArgumentException("Invalid group name " + Names.quote(value)
                + ": expected " + NAME_PATTERN.pattern());
        }
    }

    /** Returns whether {@code name} is a valid stage or server name. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
