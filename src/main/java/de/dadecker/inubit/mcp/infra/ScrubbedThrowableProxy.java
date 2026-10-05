package de.dadecker.inubit.mcp.infra;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

/** A view of a throwable (and its causes and suppressed throwables) with scrubbed messages. */
final class ScrubbedThrowableProxy implements IThrowableProxy {

    private final IThrowableProxy delegate;
    private final SecretScrubber scrubber;

    private ScrubbedThrowableProxy(IThrowableProxy delegate, SecretScrubber scrubber) {
        this.delegate = delegate;
        this.scrubber = scrubber;
    }

    static IThrowableProxy wrap(IThrowableProxy proxy, SecretScrubber scrubber) {
        return proxy == null ? null : new ScrubbedThrowableProxy(proxy, scrubber);
    }

    @Override
    public String getOverridingMessage() {
        return scrubber.scrub(delegate.getOverridingMessage());
    }

    @Override
    public String getMessage() {
        return scrubber.scrub(delegate.getMessage());
    }

    @Override
    public String getClassName() {
        return delegate.getClassName();
    }

    @Override
    public StackTraceElementProxy[] getStackTraceElementProxyArray() {
        return delegate.getStackTraceElementProxyArray();
    }

    @Override
    public int getCommonFrames() {
        return delegate.getCommonFrames();
    }

    @Override
    public IThrowableProxy getCause() {
        return wrap(delegate.getCause(), scrubber);
    }

    @Override
    public IThrowableProxy[] getSuppressed() {
        IThrowableProxy[] suppressed = delegate.getSuppressed();
        if (suppressed == null) {
            return null;
        }
        IThrowableProxy[] wrapped = new IThrowableProxy[suppressed.length];
        for (int i = 0; i < suppressed.length; i++) {
            wrapped[i] = wrap(suppressed[i], scrubber);
        }
        return wrapped;
    }

    @Override
    public boolean isCyclic() {
        return delegate.isCyclic();
    }
}
