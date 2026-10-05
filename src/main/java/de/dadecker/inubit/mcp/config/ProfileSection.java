package de.dadecker.inubit.mcp.config;

import java.util.Optional;

/**
 * {@code profile} block as written in the YAML (002 contracts/configuration.md → Format). Not
 * validated here, so that {@link ConfigValidator} can report every problem at once; an absent
 * block or name is the empty name.
 */
public record ProfileSection(String name, Optional<String> description) {

    public static final ProfileSection EMPTY = new ProfileSection("", Optional.empty());

    public ProfileSection {
        name = Optionals.orDefault(name, "");
        description = Optionals.orEmpty(description);
    }
}
