package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import de.dadecker.inubit.mcp.adapter.cli.ProcessContainment;
import org.junit.jupiter.api.Test;

/** Feature 013 (T012, research R-7): macOS and Linux need no job for StartCLI's processes. */
class NoJobTest {

    @Test
    void isAlwaysBound() {
        assertThat(new NoJob().state()).isEqualTo(new ProcessContainment.Bound());
    }
}
