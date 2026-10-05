package de.dadecker.inubit.mcp.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

/** A view of a logging event whose text parts are scrubbed. */
final class ScrubbedLoggingEvent implements ILoggingEvent {

    private final ILoggingEvent delegate;
    private final SecretScrubber scrubber;

    ScrubbedLoggingEvent(ILoggingEvent delegate, SecretScrubber scrubber) {
        this.delegate = delegate;
        this.scrubber = scrubber;
    }

    @Override
    public String getThreadName() {
        return delegate.getThreadName();
    }

    @Override
    public Level getLevel() {
        return delegate.getLevel();
    }

    @Override
    public String getMessage() {
        return scrubber.scrub(delegate.getMessage());
    }

    @Override
    public Object[] getArgumentArray() {
        Object[] arguments = delegate.getArgumentArray();
        if (arguments == null) {
            return null;
        }
        Object[] scrubbed = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            scrubbed[i] = arguments[i] == null
                ? null
                : scrubber.scrub(String.valueOf(arguments[i]));
        }
        return scrubbed;
    }

    @Override
    public String getFormattedMessage() {
        return scrubber.scrub(delegate.getFormattedMessage());
    }

    @Override
    public String getLoggerName() {
        return delegate.getLoggerName();
    }

    @Override
    public LoggerContextVO getLoggerContextVO() {
        return delegate.getLoggerContextVO();
    }

    @Override
    public IThrowableProxy getThrowableProxy() {
        return ScrubbedThrowableProxy.wrap(delegate.getThrowableProxy(), scrubber);
    }

    @Override
    public StackTraceElement[] getCallerData() {
        return delegate.getCallerData();
    }

    @Override
    public boolean hasCallerData() {
        return delegate.hasCallerData();
    }

    @Override
    public List<Marker> getMarkerList() {
        return delegate.getMarkerList();
    }

    @Override
    public Map<String, String> getMDCPropertyMap() {
        return scrubValues(delegate.getMDCPropertyMap());
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<String, String> getMdc() {
        return scrubValues(delegate.getMdc());
    }

    @Override
    public long getTimeStamp() {
        return delegate.getTimeStamp();
    }

    @Override
    public int getNanoseconds() {
        return delegate.getNanoseconds();
    }

    @Override
    public long getSequenceNumber() {
        return delegate.getSequenceNumber();
    }

    @Override
    public List<KeyValuePair> getKeyValuePairs() {
        List<KeyValuePair> pairs = delegate.getKeyValuePairs();
        if (pairs == null) {
            return null;
        }
        return pairs.stream()
            .map(pair -> new KeyValuePair(pair.key,
                pair.value == null ? null : scrubber.scrub(String.valueOf(pair.value))))
            .toList();
    }

    @Override
    public void prepareForDeferredProcessing() {
        delegate.prepareForDeferredProcessing();
    }

    private Map<String, String> scrubValues(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, String> scrubbed = new LinkedHashMap<>();
        map.forEach((key, value) -> scrubbed.put(key, scrubber.scrub(value)));
        return scrubbed;
    }
}
