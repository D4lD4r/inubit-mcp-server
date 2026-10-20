package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParameterNamesTest {

    private static final String LONGEST = "/" + "a".repeat(1010);
    private static final String TOO_LONG = "/" + "a".repeat(1011);

    @ParameterizedTest
    @ValueSource(strings = {"/a", "/inubit/prod/username", "/inubit/prod/node1/password",
        "/A-z_0.9/x-y", "/inubit/truststore-password"})
    void acceptsFullHierarchicalNames(String name) {
        assertThat(ParameterNames.isValidName(name)).isTrue();
    }

    @Test
    void acceptsTheLongestName() {
        assertThat(ParameterNames.isValidName(LONGEST)).isTrue();
        assertThat(ParameterNames.isValidName(TOO_LONG)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "inubit/prod/password", "password", "/inubit//password",
        "//inubit", "/inubit/prod/", "/inubit/prod/password:3", "/inubit/prod/password:label",
        "arn:aws:ssm:eu-central-1:000000000000:parameter/inubit/password",
        "/inubit/pass word", "/inubit/pass\nword", "/inubit/päss", "/inubit/*"})
    void refusesOtherNames(String name) {
        assertThat(ParameterNames.isValidName(name)).isFalse();
        assertThatThrownBy(() -> new ParameterNames(Optional.of(name), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNull() {
        assertThat(ParameterNames.isValidName(null)).isFalse();
    }

    @Test
    void everyKindIsCheckedAndNamedInTheErrorWithoutTheValue() {
        Optional<String> bad = Optional.of("test-password-1");
        Optional<String> none = Optional.empty();

        assertThatThrownBy(() -> new ParameterNames(bad, none, none, none, none))
            .hasMessageContaining("username").hasMessageNotContaining("test-password-1");
        assertThatThrownBy(() -> new ParameterNames(none, bad, none, none, none))
            .hasMessageContaining("password").hasMessageNotContaining("test-password-1");
        assertThatThrownBy(() -> new ParameterNames(none, none, bad, none, none))
            .hasMessageContaining("trustStorePassword")
            .hasMessageNotContaining("test-password-1");
        assertThatThrownBy(() -> new ParameterNames(none, none, none, bad, none))
            .hasMessageContaining("e2eUsername").hasMessageNotContaining("test-password-1");
        assertThatThrownBy(() -> new ParameterNames(none, none, none, none, bad))
            .hasMessageContaining("e2ePassword").hasMessageNotContaining("test-password-1");
    }

    @Test
    void everyKindIsOptional() {
        ParameterNames names = new ParameterNames(null, null, null, null, null);

        assertThat(names.username()).isEmpty();
        assertThat(names.password()).isEmpty();
        assertThat(names.trustStorePassword()).isEmpty();
        assertThat(names.e2eUsername()).isEmpty();
        assertThat(names.e2ePassword()).isEmpty();
    }

    @Test
    void toStringShowsTheNames() {
        ParameterNames names = new ParameterNames(Optional.of("/inubit/prod/username"),
            Optional.of("/inubit/prod/password"), Optional.of("/inubit/prod/ts"),
            Optional.of("/inubit/prod/e2e-username"), Optional.of("/inubit/prod/e2e-password"));

        assertThat(names.toString()).contains("/inubit/prod/username", "/inubit/prod/password",
            "/inubit/prod/ts", "/inubit/prod/e2e-username", "/inubit/prod/e2e-password");
    }
}
