package de.dadecker.inubit.mcp.adapter.xslt;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The output of one stylesheet run: refuses every write once the run is abandoned after its
 * deadline, and refuses to grow beyond {@code maxBytes} (stage-3 review: an endless run must not
 * fill the disk or touch a later run's file).
 */
final class GuardedOutput extends FilterOutputStream {

    private final AtomicBoolean abandoned;
    private final long maxBytes;
    private long written;
    private volatile boolean exceeded;

    GuardedOutput(OutputStream out, AtomicBoolean abandoned, long maxBytes) {
        super(out);
        this.abandoned = Objects.requireNonNull(abandoned, "abandoned");
        this.maxBytes = maxBytes;
    }

    /** True if a write was refused because of the size limit. */
    boolean exceeded() {
        return exceeded;
    }

    @Override
    public void write(int b) throws IOException {
        allow(1);
        out.write(b);
        written++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        allow(length);
        out.write(bytes, offset, length);
        written += length;
    }

    private void allow(int length) throws IOException {
        if (abandoned.get()) {
            throw new IOException("the run was abandoned after its deadline");
        }
        if (written + length > maxBytes) {
            exceeded = true;
            throw new IOException("the output exceeds " + maxBytes + " bytes");
        }
    }
}
