package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the StartCLI session of a page read gives for one login (feature 022, US2, research
 * D-20): its groups ({@code user <login>}) and its roles, e-mail and personal fields
 * ({@code finger <login>}).
 * Each of the two answers is read or unavailable with the reason on its own, so one unreadable
 * answer costs only that part of that user (FR-013).
 *
 * @param login  the login as given
 * @param groups the answer of {@code user <login>}
 * @param finger the answer of {@code finger <login>}
 */
public record UserFacts(String login, Block<UserGroups> groups, Block<Finger> finger) {

    public UserFacts {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(finger, "finger");
    }

    /** One command's answer: parsed, or unavailable with the reason. */
    public sealed interface Block<T> {

        record Parsed<T>(T value) implements Block<T> {

            public Parsed {
                Objects.requireNonNull(value, "value");
            }
        }

        record Unavailable<T>(ToolError error) implements Block<T> {

            public Unavailable {
                Objects.requireNonNull(error, "error");
            }
        }
    }

    /**
     * @param group      the user's group ({@code User group})
     * @param superGroup the parent of that group ({@code Super group}), not an additional group
     */
    public record UserGroups(String group, String superGroup) {

        public UserGroups {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(superGroup, "superGroup");
        }
    }

    /**
     * What {@code finger} shows of one user.
     *
     * @param login    the login
     * @param roles    the roles, in the order shown
     * @param email    the e-mail address; empty if the line has none
     * @param personal the other personal fields (the name), masked by {@link SecretRules}
     */
    public record Finger(String login, List<String> roles, Optional<String> email,
        List<PersonalField> personal) {

        public Finger {
            Objects.requireNonNull(login, "login");
            roles = List.copyOf(roles);
            Objects.requireNonNull(email, "email");
            personal = List.copyOf(personal);
        }

        /** The login and the roles, never a personal value. */
        @Override
        public String toString() {
            return "Finger[" + login + ", roles " + roles + ", email "
                + (email.isPresent() ? "present" : "absent") + ", " + personal.size()
                + " personal field(s)]";
        }
    }
}
