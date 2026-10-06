package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.PathChange.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T005: history entries and their changed paths. */
class HistoryEntryTest {

    @Test
    void changesAreAnImmutableCopy() {
        List<PathChange> changes = new ArrayList<>(List.of(
            new PathChange("dev/OWNERS/workflows/GRP-01/Workflow-0001.xml", Kind.MODIFIED)));
        HistoryEntry entry = new HistoryEntry("a1b2c3d", "export dev/node1: GRP-01 (1 files)",
            changes);

        changes.add(new PathChange("dev/OWNERS/x.xml", Kind.ADDED));

        assertThat(entry.changes()).hasSize(1);
        assertThatThrownBy(() -> entry.changes().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a1b", "A1B2C3D", "a1b2c3d!", "g1b2c3d"})
    void theCommitIsAnAbbreviatedOrFullHexId(String commit) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new HistoryEntry(commit, "m", List.of()))
            .withMessageContaining("commit");
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException().isThrownBy(() -> new HistoryEntry(null, "m", List.of()));
        assertThatNullPointerException().isThrownBy(() -> new HistoryEntry("a1b2c3d", null,
            List.of()));
        assertThatNullPointerException().isThrownBy(() -> new HistoryEntry("a1b2c3d", "m",
            null));
        assertThat(new HistoryEntry("a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", "m", List.of())
            .commit()).hasSize(40);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/etc/passwd", "dev\\OWNERS\\x.xml"})
    void pathsAreWorkspaceRelativeWithSlashes(String path) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PathChange(path, Kind.ADDED))
            .withMessageContaining("path");
    }

    @Test
    void aPathChangeNeedsPathAndKind() {
        assertThatNullPointerException().isThrownBy(() -> new PathChange(null, Kind.ADDED));
        assertThatNullPointerException().isThrownBy(() -> new PathChange("a.xml", null));
        assertThat(new PathChange("a.xml", Kind.DELETED).kind()).isEqualTo(Kind.DELETED);
    }
}
