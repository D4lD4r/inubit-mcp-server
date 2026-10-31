package de.dadecker.inubit.mcp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Feature 011 T019: the credential store live check is opt-in twice (a plain unit test, not
 * tagged): its own tag, excluded from the default build and from {@code -Plive}, and
 * {@code INUBIT_LIVE_STORE=1}.
 */
class CredentialStoreLiveOptInTest {

    @Test
    void withoutTheVariableTheCheckIsSkipped() {
        assertThatThrownBy(() -> CredentialStoreLiveTest.requireEnabled(Map.of()))
            .isInstanceOf(TestAbortedException.class).hasMessageContaining("INUBIT_LIVE_STORE");
        assertThatThrownBy(() -> CredentialStoreLiveTest.requireEnabled(
            Map.of("INUBIT_LIVE_STORE", "true"))).isInstanceOf(TestAbortedException.class);
    }

    @Test
    void withTheVariableTheCheckRuns() {
        assertThatCode(() -> CredentialStoreLiveTest.requireEnabled(
            Map.of("INUBIT_LIVE_STORE", "1"))).doesNotThrowAnyException();
    }

    @Test
    void theCheckHasItsOwnTag() {
        assertThat(CredentialStoreLiveTest.class.getAnnotation(Tag.class).value())
            .isEqualTo("live-store");
    }

    @Test
    void theDefaultBuildAndTheLiveProfileExcludeTheTag() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher excluded = Pattern.compile(
            "<surefire\\.excludedGroups>([^<]*)</surefire\\.excludedGroups>").matcher(pom);
        // the first occurrence is the default build's property, the next the live profile's
        assertThat(excluded.find()).isTrue();
        assertThat(excluded.group(1).split(",")).contains("live", "live-store");
        assertThat(excluded.find()).isTrue();
        assertThat(excluded.group(1).split(",")).contains("live-store");
    }
}
