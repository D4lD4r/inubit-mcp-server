package de.dadecker.inubit.mcp.adapter.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Feature 013 (T008): the system queries StartCLI needs on macOS and Linux. */
class PosixSystemTest {

    private final PosixSystem system = new PosixSystem();

    @Test
    void needsNoSystemDirectory() {
        assertThat(system.systemDirectory()).isEmpty();
    }

    @Test
    void decodesOutputAsUtf8AsToday() {
        assertThat(system.oemCharset()).isEqualTo(StandardCharsets.UTF_8);
    }
}
