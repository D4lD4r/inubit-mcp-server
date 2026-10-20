package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"alice", "svc-user", "a-b", "user@example", "Ünïcode"})
    void acceptsSafeUsernames(String username) {
        assertThat(CredentialRules.isSafeUsername(username)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-alice", "al:ice", "al\nice", "al\tice", "al\u0000ice",
        "al\u007fice"})
    void refusesUnsafeUsernames(String username) {
        assertThat(CredentialRules.isSafeUsername(username)).isFalse();
    }

    @Test
    void passwordsNeedTheMinimumScrubbedLength() {
        assertThat(CredentialRules.MIN_SCRUBBED_LENGTH).isEqualTo(4);
        assertThat(CredentialRules.isScrubbable("abc")).isFalse();
        assertThat(CredentialRules.isScrubbable("abcd")).isTrue();
    }

    @Test
    void onlyNonEmptyValuesCount() {
        assertThat(CredentialRules.isSet(null)).isFalse();
        assertThat(CredentialRules.isSet("")).isFalse();
        assertThat(CredentialRules.isSet(" ")).isTrue();
        assertThat(CredentialRules.isSet("x")).isTrue();
    }

    @Test
    void secureStringsAreAcceptedStringsWarnedAndStringListsRefused() {
        assertThat(CredentialRules.checkType(ParameterType.SECURE_STRING))
            .isEqualTo(CredentialRules.TypeVerdict.ACCEPTED);
        assertThat(CredentialRules.checkType(ParameterType.STRING))
            .isEqualTo(CredentialRules.TypeVerdict.UNENCRYPTED);
        assertThat(CredentialRules.checkType(ParameterType.STRING_LIST))
            .isEqualTo(CredentialRules.TypeVerdict.REFUSED);
    }
}
