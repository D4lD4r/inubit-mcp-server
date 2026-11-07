package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SemVerTest {

    @Test
    void parsesARelease() {
        SemVer v = SemVer.parse("0.7.1").orElseThrow();
        assertThat(v.major()).isZero();
        assertThat(v.minor()).isEqualTo(7);
        assertThat(v.patch()).isEqualTo(1);
        assertThat(v.preRelease()).isEmpty();
        assertThat(v).hasToString("0.7.1");
    }

    @Test
    void parsesPreReleaseAndIgnoresBuildMetadata() {
        SemVer v = SemVer.parse("1.2.3-rc.1+build.5").orElseThrow();
        assertThat(v.preRelease()).containsExactly("rc", "1");
        assertThat(v).hasToString("1.2.3-rc.1");
        assertThat(SemVer.parse("1.2.3+build").orElseThrow()).isEqualTo(SemVer.parse("1.2.3").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1", "1.2", "1.2.3.4", "v1.2.3", "01.2.3", "1.02.3", "1.2.03",
        "1.2.3-", "1.2.3-01", "1.2.3-a..b", "1.2.3+", "1.2.3-a_b", " 1.2.3", "a.b.c",
        "99999999999.0.0"})
    void rejectsInvalidText(String text) {
        assertThat(SemVer.parse(text)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(SemVer.parse(null)).isEmpty();
    }

    @Test
    void ordersBySemVerPrecedence() {
        List<String> ascending = List.of("0.7.1", "0.7.2", "0.8.0-SNAPSHOT", "0.8.0-alpha",
            "0.8.0-alpha.1", "0.8.0-alpha.beta", "0.8.0-beta", "0.8.0-beta.2", "0.8.0-beta.11",
            "0.8.0-rc.1", "0.8.0", "0.10.0", "1.0.0");
        for (int i = 0; i + 1 < ascending.size(); i++) {
            SemVer lower = SemVer.parse(ascending.get(i)).orElseThrow();
            SemVer higher = SemVer.parse(ascending.get(i + 1)).orElseThrow();
            assertThat(lower).as(ascending.get(i) + " < " + ascending.get(i + 1))
                .isLessThan(higher);
            assertThat(higher).isGreaterThan(lower);
        }
    }

    @Test
    void equalVersionsCompareEqual() {
        assertThat(SemVer.parse("1.0.0-rc.1").orElseThrow())
            .isEqualByComparingTo(SemVer.parse("1.0.0-rc.1+x").orElseThrow());
    }

    @Test
    void snapshotIsBelowItsRelease() {
        assertThat(SemVer.parse("0.8.0-SNAPSHOT").orElseThrow())
            .isLessThan(SemVer.parse("0.8.0").orElseThrow());
    }
}
