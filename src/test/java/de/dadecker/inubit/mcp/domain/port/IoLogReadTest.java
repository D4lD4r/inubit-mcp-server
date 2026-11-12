package de.dadecker.inubit.mcp.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.dadecker.inubit.mcp.domain.model.node.IoLogEntry;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.port.IoLogPort.IoLogRead;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Feature 024 (T015, research D-24): the result of one I/O message read. */
class IoLogReadTest {

    private static final IoLogEntry ENTRY =
        IoLogEntry.parse("4711", "4711@@@Orders(Module-0001)@@@Input.1");
    private static final IoLogListing LISTING = new IoLogListing("node1", List.of(ENTRY));

    @Test
    void closingAMessageDeletesItsPrivateDirectory() {
        AtomicInteger deletions = new AtomicInteger();
        IoLogRead read = new IoLogRead.Message(LISTING, ENTRY, Path.of("message.bin"), 0,
            deletions::incrementAndGet);

        read.close();

        assertThat(deletions).hasValue(1);
    }

    @Test
    void aMessageSizeIsNeverNegative() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IoLogRead.Message(LISTING,
            ENTRY, Path.of("message.bin"), -1, () -> { })).withMessageContaining("size");
    }
}
