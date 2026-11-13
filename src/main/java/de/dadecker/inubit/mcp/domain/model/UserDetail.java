package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One named user (data-model.md → UserDetail, US2 AS2): the {@link UserSummary} plus the
 * personal fields INUBIT reports for that user. Only this record carries personal data (FR-011),
 * and its string form names none of it.
 *
 * @param summary  login, groups, roles and account state
 * @param email    the e-mail address; empty if INUBIT reports none
 * @param personal the other fields INUBIT reports, in its order, masked by {@link SecretRules}
 *                 (FR-010)
 */
public record UserDetail(UserSummary summary, Optional<String> email,
    List<PersonalField> personal) {

    public UserDetail {
        Objects.requireNonNull(summary, "summary");
        email = email == null ? Optional.empty() : email;
        personal = List.copyOf(personal);
    }

    /** The login and the number of personal fields, never their values. */
    @Override
    public String toString() {
        return "UserDetail[" + summary + ", email " + (email.isPresent() ? "present" : "absent")
            + ", " + personal.size() + " personal field(s)]";
    }

    /**
     * One personal field: {@code {name, value}}, or {@code {name, masked: true}}.
     *
     * @param name  the field name as INUBIT reports it
     * @param value the value; empty when masked
     */
    public record PersonalField(String name, Optional<String> value) {

        public PersonalField {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
        }

        public static PersonalField shown(String name, String value) {
            return new PersonalField(name, Optional.of(value));
        }

        public static PersonalField masked(String name) {
            return new PersonalField(name, Optional.empty());
        }

        public boolean masked() {
            return value.isEmpty();
        }

        /** The name only, never the value. */
        @Override
        public String toString() {
            return "PersonalField[" + name + (masked() ? ", masked" : "") + "]";
        }
    }
}
