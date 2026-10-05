package de.dadecker.inubit.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 002 US2 review: the SDK stops reading stdin at the first line it cannot deserialize, so a blank
 * or malformed line kept the server alive after stdin was closed. The filter passes on only the
 * lines the SDK can process, each terminated by {@code \n}, and reaches the end of the input.
 */
class InboundLineFilterTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
    private static final String NOTE =
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

    private static String filtered(String input) throws IOException {
        return filtered(input, InboundLineFilter.MAX_LINE_BYTES);
    }

    private static String filtered(String input, int maxLineBytes) throws IOException {
        try (InputStream in = new InboundLineFilter(
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), maxLineBytes)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void validMessagesPassUnchanged() throws IOException {
        assertThat(filtered(INIT + "\n" + NOTE + "\n")).isEqualTo(INIT + "\n" + NOTE + "\n");
    }

    @Test
    void blankLinesAreDropped() throws IOException {
        assertThat(filtered("\n  \t\n\r\n" + INIT + "\r\n\n")).isEqualTo(INIT + "\n");
    }

    @Test
    void linesThatAreNoJsonRpcMessageAreDropped() throws IOException {
        assertThat(filtered("not json\n{\"no\":\"json-rpc\"}\n[1,2]\n" + INIT + "\n"))
            .isEqualTo(INIT + "\n");
    }

    @Test
    void anOverlongLineIsDroppedAndTheNextOneStillPasses() throws IOException {
        String longLine = "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":{\"p\":\""
            + "a".repeat(500) + "\"}}";

        assertThat(filtered(longLine + "\n" + INIT + "\n", 200)).isEqualTo(INIT + "\n");
    }

    /** A stdin that blocks until bytes are sent; {@link #end()} ends it. */
    private static final class Keyboard extends InputStream {
        private final BlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();

        void send(String text) {
            for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
                bytes.add(b & 0xFF);
            }
        }

        void end() {
            bytes.add(-1);
        }

        @Override
        public int read() throws IOException {
            try {
                return bytes.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            buffer[offset] = (byte) first;
            return 1; // one byte at a time: never waits for more than is there
        }
    }

    @Test
    void aBareCarriageReturnEndsTheLineWithoutWaitingForMoreInput() throws Exception {
        // re-review N2: like BufferedReader.readLine, "\r" alone terminates the line
        Keyboard client = new Keyboard();
        InputStream filter = new InboundLineFilter(client);
        client.send(INIT + "\r");

        byte[] buffer = new byte[256];
        Future<Integer> first = Executors.newVirtualThreadPerTaskExecutor()
            .submit(() -> filter.read(buffer, 0, buffer.length));

        int count = first.get(5, TimeUnit.SECONDS);
        assertThat(new String(buffer, 0, count, StandardCharsets.UTF_8)).isEqualTo(INIT + "\n");
        // a "\n" directly after the "\r" belongs to the same terminator
        client.send("\n" + NOTE + "\r" + INIT + "\n");
        client.end();
        assertThat(new String(filter.readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo(NOTE + "\n" + INIT + "\n");
    }

    @Test
    void aLastLineWithoutNewlineIsPassedOnTerminated() throws IOException {
        assertThat(filtered("\n" + INIT)).isEqualTo(INIT + "\n");
        assertThat(filtered("")).isEmpty();
    }

    @Test
    void multiByteCharactersSurvive() throws IOException {
        String message = "{\"jsonrpc\":\"2.0\",\"method\":\"note\","
            + "\"params\":{\"t\":\"Umgebung – Knoten ü\"}}";

        assertThat(filtered(message + "\n")).isEqualTo(message + "\n");
    }
}
