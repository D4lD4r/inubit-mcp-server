package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.infra.Secret;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FetchedTest {

    private static final String VALUE = "test-password-1";
    private static final String DETAIL = "User: arn:test:000000000000:role/test-role is not authorized";

    @Test
    void aValueNeverRevealsItsSecret() {
        Fetched.Value value = new Fetched.Value(Secret.of(VALUE), ParameterType.SECURE_STRING);

        assertThat(value.toString()).doesNotContain(VALUE).contains("SECURE_STRING");
        assertThat(value.value().reveal()).isEqualTo(VALUE);
    }

    @Test
    void aFailureNamesItsKindButNotTheRawStoreMessage() {
        Fetched.Failure failure = new Fetched.Failure(FailureKind.ACCESS_DENIED, DETAIL);

        assertThat(failure.toString()).contains("ACCESS_DENIED").doesNotContain(DETAIL)
            .doesNotContain("arn:");
        assertThat(failure.logDetail()).isEqualTo(DETAIL);
    }

    @Test
    void perNameResultsNameTheParametersButRevealNoValueAndNoStoreMessage() {
        Fetched fetched = new Fetched.PerName(Map.of(
            "/test/inubit/password", new Fetched.Value(Secret.of(VALUE), ParameterType.STRING),
            "/test/inubit/username", new Fetched.Failure(FailureKind.NOT_FOUND, DETAIL)));

        assertThat(fetched.toString()).contains("/test/inubit/password")
            .contains("/test/inubit/username").doesNotContain(VALUE).doesNotContain(DETAIL);
    }

    @Test
    void aWholeCallFailureRevealsNoStoreMessage() {
        Fetched fetched = new Fetched.CallFailed(
            new Fetched.Failure(FailureKind.NO_CREDENTIALS, DETAIL));

        assertThat(fetched.toString()).contains("NO_CREDENTIALS").doesNotContain(DETAIL);
    }

    @Test
    void perNameResultsAreAnImmutableCopy() {
        Map<String, Fetched.Result> source = new HashMap<>();
        source.put("/test/a", new Fetched.Failure(FailureKind.NOT_FOUND, ""));
        Fetched.PerName fetched = new Fetched.PerName(source);
        source.put("/test/b", new Fetched.Failure(FailureKind.NOT_FOUND, ""));

        assertThat(fetched.results()).containsOnlyKeys("/test/a");
        assertThatThrownBy(() -> fetched.results().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingPartsAreRefused() {
        assertThatThrownBy(() -> new Fetched.Value(null, ParameterType.SECURE_STRING))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Fetched.Value(Secret.of(VALUE), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Fetched.Failure(null, DETAIL))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Fetched.Failure(FailureKind.UNREACHABLE, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Fetched.CallFailed(null))
            .isInstanceOf(NullPointerException.class);
    }
}
