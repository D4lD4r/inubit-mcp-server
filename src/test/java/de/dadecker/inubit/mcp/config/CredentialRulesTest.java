package de.dadecker.inubit.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.config.CredentialRules.Refusal;
import de.dadecker.inubit.mcp.config.CredentialRules.ValueRole;
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
    void eachCredentialsParametersKeyHasOneRole() {
        // T015 review r1: one key → role mapping for NodeSecrets and ParameterCheck
        assertThat(CredentialRules.roleOf("username")).isEqualTo(ValueRole.USERNAME);
        assertThat(CredentialRules.roleOf("e2eUsername")).isEqualTo(ValueRole.USERNAME);
        assertThat(CredentialRules.roleOf("password")).isEqualTo(ValueRole.PASSWORD);
        assertThat(CredentialRules.roleOf("e2ePassword")).isEqualTo(ValueRole.PASSWORD);
        assertThat(CredentialRules.roleOf("trustStorePassword"))
            .isEqualTo(ValueRole.TRUST_STORE_PASSWORD);
        assertThatThrownBy(() -> CredentialRules.roleOf("token"))
            .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void aFetchedValueIsCheckedAsAtRunTime() {
        // T015: NodeSecrets and --check-config share these checks (research R-17)
        assertThat(CredentialRules.checkValue(ValueRole.USERNAME, ParameterType.SECURE_STRING,
            "alice")).isEmpty();
        assertThat(CredentialRules.checkValue(ValueRole.PASSWORD, ParameterType.STRING,
            "abcd")).as("String is only warned").isEmpty();
        assertThat(CredentialRules.checkValue(ValueRole.TRUST_STORE_PASSWORD,
            ParameterType.SECURE_STRING, "abc")).as("no length rule").isEmpty();
        assertThat(CredentialRules.checkValue(ValueRole.USERNAME, ParameterType.STRING_LIST,
            "a,b")).hasValue(new Refusal(FailureKind.INVALID_VALUE,
                "a StringList is not accepted; store it as SecureString"));
        assertThat(CredentialRules.checkValue(ValueRole.TRUST_STORE_PASSWORD,
            ParameterType.SECURE_STRING, "")).hasValue(
                new Refusal(FailureKind.NOT_FOUND, "the value is empty"));
        assertThat(CredentialRules.checkValue(ValueRole.USERNAME, ParameterType.SECURE_STRING,
            "al:ice")).hasValue(new Refusal(FailureKind.INVALID_VALUE,
                "the username " + CredentialRules.SAFE_USERNAME_RULE));
        assertThat(CredentialRules.checkValue(ValueRole.PASSWORD, ParameterType.SECURE_STRING,
            "abc")).hasValue(new Refusal(FailureKind.INVALID_VALUE,
                "the password " + CredentialRules.SHORT_PASSWORD_RULE));
    }

    @Test
    void aRefusalNeverShowsTheValue() {
        assertThat(CredentialRules.checkValue(ValueRole.USERNAME, ParameterType.SECURE_STRING,
            "-svc:Secret-9").orElseThrow().toString()).doesNotContain("Secret-9");
    }
}
