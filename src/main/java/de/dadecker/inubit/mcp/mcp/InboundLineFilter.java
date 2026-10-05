package de.dadecker.inubit.mcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stdin the MCP SDK reads (002 US2 review): only lines the SDK can process.
 *
 * <p>The SDK's {@code StdioServerTransportProvider} stops reading at the first line it cannot
 * deserialize as a JSON-RPC message (or that is longer than its limit): it closes the session but
 * never reads to the end of stdin, so the server would not notice that the client closed it and
 * would run forever. This filter reads the lines itself and passes on only those the SDK accepts,
 * each terminated by {@code \n}:
 *
 * <ul>
 *   <li>blank lines are dropped silently;
 *   <li>a line that is no JSON-RPC message, or longer than {@link #MAX_LINE_BYTES}, is dropped
 *       with a warning that names only its length (it could contain anything);
 *   <li>{@code \n}, {@code \r} and {@code \r\n} end a line, as with
 *       {@link java.io.BufferedReader#readLine()}: a {@code \r} ends it at once, without waiting
 *       for the next byte; a {@code \n} directly after it is skipped when the next line is read.
 *       A last line without terminator is passed on terminated.
 * </ul>
 *
 * The end of the input is passed on, so that the server shuts down when stdin is closed.
 */
final class InboundLineFilter extends InputStream {

    /** The SDK's own limit (16 Mi characters); a line within it in bytes is within it. */
    static final int MAX_LINE_BYTES = 16 * 1024 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(InboundLineFilter.class);

    private final InputStream in;
    private final int maxLineBytes;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private byte[] pending = new byte[0];
    private int position;
    private boolean ended;
    /** The last line ended with {@code \r}: a directly following {@code \n} belongs to it. */
    private boolean skipLineFeed;

    InboundLineFilter(InputStream in) {
        this(in, MAX_LINE_BYTES);
    }

    /** For tests: with another line limit. */
    InboundLineFilter(InputStream in, int maxLineBytes) {
        this.in = new BufferedInputStream(Objects.requireNonNull(in, "in"));
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return pending[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int count = Math.min(length, pending.length - position);
        System.arraycopy(pending, position, buffer, offset, count);
        position += count;
        return count;
    }

    @Override
    public int available() {
        return pending.length - position;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** True if bytes are pending, reading the next accepted line if needed; false at the end. */
    private boolean fill() throws IOException {
        while (position >= pending.length) {
            if (ended) {
                return false;
            }
            Line line = readLine();
            if (line == null) {
                ended = true;
                return false;
            }
            if (line.tooLong()) {
                LOG.warn("Ignored an inbound line of more than {} bytes", maxLineBytes);
                continue;
            }
            String text = new String(line.bytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                continue;
            }
            if (!isJsonRpcMessage(text)) {
                LOG.warn("Ignored an inbound line that is no JSON-RPC message ({} characters)",
                    text.length());
                continue;
            }
            pending = (text + "\n").getBytes(StandardCharsets.UTF_8);
            position = 0;
        }
        return true;
    }

    private boolean isJsonRpcMessage(String text) {
        try {
            McpSchema.deserializeJsonRpcMessage(mapper, text);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** One line without terminator; {@code null} at the end of the input. */
    private Line readLine() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean tooLong = false;
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            if (skipLineFeed) {
                skipLineFeed = false;
                if (c == '\n') {
                    continue; // the second half of "\r\n"
                }
            }
            any = true;
            if (c == '\n') {
                return new Line(bytes.toByteArray(), tooLong);
            }
            if (c == '\r') {
                skipLineFeed = true;
                return new Line(bytes.toByteArray(), tooLong);
            }
            if (bytes.size() >= maxLineBytes) {
                tooLong = true; // the rest of the line is read, not kept
            } else {
                bytes.write(c);
            }
        }
        return any ? new Line(bytes.toByteArray(), tooLong) : null;
    }

    private record Line(byte[] bytes, boolean tooLong) {
    }
}
