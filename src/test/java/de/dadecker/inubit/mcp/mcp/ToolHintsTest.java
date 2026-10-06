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
}
