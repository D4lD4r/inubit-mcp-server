package de.dadecker.inubit.mcp.infra;

import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Prints Logback's own <em>warnings and errors</em> (e.g. a broken appender) to stderr, one line
 * each, scrubbed. Registered in {@code logback.xml}: with any status listener registered, Logback
 * no longer prints configuration problems to {@code System.out}, which is the MCP protocol
 * stream (research R-16). Unlike Logback's {@code OnErrorConsoleStatusListener}, INFO statuses
 * are not printed, so a normal start produces no status noise.
 */
public final class StderrStatusListener extends ContextAwareBase
    implements StatusListener, LifeCycle {

    private final Supplier<PrintStream> stream;
    private volatile boolean started;

    public StderrStatusListener() {
        this(() -> System.err);
    }

    StderrStatusListener(Supplier<PrintStream> stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public void addStatusEvent(Status status) {
        if (started) {
            print(status);
        }
    }

    /** Starts and prints the warnings and errors that were recorded before. */
    @Override
    public void start() {
        if (getContext() != null) {
            getContext().getStatusManager().getCopyOfStatusList().forEach(this::print);
        }
        started = true;
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    private void print(Status status) {
        if (status.getEffectiveLevel() < Status.WARN) {
            return;
        }
        StringBuilder line = new StringBuilder("logback ")
            .append(status.getEffectiveLevel() >= Status.ERROR ? "ERROR" : "WARN")
            .append(": ").append(status.getMessage());
        for (Throwable t = status.getThrowable(); t != null; t = t.getCause()) {
            line.append(" (").append(t.getClass().getName());
            if (t.getMessage() != null) {
                line.append(": ").append(t.getMessage());
            }
            line.append(')');
        }
        stream.get().println(SecretScrubber.global().scrub(line.toString())
            .replaceAll("\\s*\\R\\s*", " "));
    }
}
