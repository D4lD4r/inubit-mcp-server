package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** T023: read-only tools that are not idempotent (export_artifacts records a history entry). */
class ToolHintsTest {

    @Test
    void theTwoArgumentFactoryStaysIdempotent() {
        assertThat(ToolHints.readOnly("Show", true))
            .isEqualTo(new ToolHints("Show", true, false, true, true));
    }

    @Test
    void aReadOnlyToolCanBeNonIdempotent() {
        assertThat(ToolHints.readOnly("Export", true, false))
            .isEqualTo(new ToolHints("Export", true, false, false, true));
        assertThat(ToolHints.readOnly("Check", false, true))
            .isEqualTo(new ToolHints("Check", true, false, true, false));
    }

    /**
     * Feature 018 (T022): find_partners reads only and may be repeated; open world, since it talks
     * to INUBIT.
     */
    @Test
    void findPartnersIsReadOnlyIdempotentAndOpenWorld() {
        ToolHints hints = ToolHints.readOnly("Find partners in the INUBIT Partner Management",
            true, true);

        assertThat(hints).isEqualTo(new ToolHints(
            "Find partners in the INUBIT Partner Management", true, false, true, true));
    }

    /** Feature 015 (research R-1): export_archives writes local files but changes nothing in INUBIT. */
    @Test
    void localWriteIsNeitherReadOnlyNorDestructiveNorIdempotentButOpenWorld() {
        ToolHints hints = ToolHints.localWrite("Export archives");

        assertThat(hints.title()).isEqualTo("Export archives");
        assertThat(hints.readOnly()).isFalse();
        assertThat(hints.destructive()).isFalse();
        assertThat(hints.idempotent()).isFalse();
        assertThat(hints.openWorld()).isTrue();
    }
}
