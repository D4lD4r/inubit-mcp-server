package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerUpdateTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:15:00Z");

    @Test
    void aHigherReleaseIsAnAvailableUpdateWithManualNotice() {
        ServerUpdate update = ServerUpdate.of(v("0.7.1"), v("0.8.0"), NOW);
        assertThat(update.currentVersion()).isEqualTo("0.7.1");
        assertThat(update.latestVersion()).isEqualTo("0.8.0");
        assertThat(update.updateAvailable()).isTrue();
        assertThat(update.releaseUrl())
            .isEqualTo("https://github.com/D4lD4r/inubit-mcp-server/releases/tag/v0.8.0");
        assertThat(update.checkedAt()).isEqualTo(NOW);
        assertThat(update.notice()).hasValueSatisfying(notice -> assertThat(notice)
            .contains("0.8.0", "0.7.1", update.releaseUrl(), "manually", ".sha256"));
    }

    @Test
    void anEqualOrLowerReleaseIsNoUpdate() {
        assertThat(ServerUpdate.of(v("0.7.1"), v("0.7.1"), NOW).updateAvailable()).isFalse();
        ServerUpdate older = ServerUpdate.of(v("0.8.0-SNAPSHOT"), v("0.7.1"), NOW);
        assertThat(older.updateAvailable()).isFalse();
        assertThat(older.notice()).isEmpty();
    }

    @Test
    void theReleaseOfASnapshotsVersionIsAnUpdate() {
        assertThat(ServerUpdate.of(v("0.8.0-SNAPSHOT"), v("0.8.0"), NOW).updateAvailable()).isTrue();
    }

    @Test
    void noticeAndAvailabilityMustAgree() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ServerUpdate("1.0.0", "1.0.0",
            false, "u", NOW, Optional.of("x")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ServerUpdate("1.0.0", "2.0.0",
            true, "u", NOW, Optional.empty()));
    }

    private static SemVer v(String text) {
        return SemVer.parse(text).orElseThrow();
    }
}
