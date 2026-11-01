package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CliHealthSnapshotTest {

    @Test
    void aCompleteSnapshotKeepsEveryPart() {
        CliHealthSnapshot snapshot = new CliHealthSnapshot(true, Optional.of("6.1.0.89"), true,
            Optional.of(false), Optional.of(new MemoryFigures(1210, 838, 2048)),
            Optional.of(new ProcessCounts(3, 0, 12, 0)), List.of());

        assertThat(snapshot.alive()).isTrue();
        assertThat(snapshot.versionText()).contains("6.1.0.89");
        assertThat(snapshot.versionIsServer()).isTrue();
        assertThat(snapshot.maintenance()).contains(false);
        assertThat(snapshot.memory()).contains(new MemoryFigures(1210, 838, 2048));
        assertThat(snapshot.counts()).contains(new ProcessCounts(3, 0, 12, 0));
        assertThat(snapshot.missing()).isEmpty();
    }

    @Test
    void theMissingPartsAreCopied() {
        List<UnavailablePart> missing = new ArrayList<>(List.of(
            new UnavailablePart(UnavailablePart.LOAD, "UNEXPECTED_RESPONSE: free -m")));
        CliHealthSnapshot snapshot = new CliHealthSnapshot(true, Optional.empty(), false,
            Optional.empty(), Optional.empty(), Optional.empty(), missing);

        missing.clear();

        assertThat(snapshot.missing()).extracting(UnavailablePart::part)
            .containsExactly(UnavailablePart.LOAD);
    }

    @Test
    void aServerVersionNeedsAVersionText() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CliHealthSnapshot(true,
            Optional.empty(), true, Optional.empty(), Optional.empty(), Optional.empty(),
            List.of()));
    }

    @Test
    void absentPartsAreEmptyOptionalsNotNull() {
        assertThatNullPointerException().isThrownBy(() -> new CliHealthSnapshot(true, null,
            false, Optional.empty(), Optional.empty(), Optional.empty(), List.of()));
        assertThatNullPointerException().isThrownBy(() -> new CliHealthSnapshot(true,
            Optional.empty(), false, null, Optional.empty(), Optional.empty(), List.of()));
        assertThatNullPointerException().isThrownBy(() -> new CliHealthSnapshot(true,
            Optional.empty(), false, Optional.empty(), null, Optional.empty(), List.of()));
        assertThatNullPointerException().isThrownBy(() -> new CliHealthSnapshot(true,
            Optional.empty(), false, Optional.empty(), Optional.empty(), null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new CliHealthSnapshot(true,
            Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(),
            null));
    }
}
