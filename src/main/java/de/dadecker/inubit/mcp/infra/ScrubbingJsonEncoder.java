package de.dadecker.inubit.mcp.infra;

import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.nio.charset.StandardCharsets;

/**
 * Logback's {@link JsonEncoder} with secret scrubbing (Constitution II/VII, research R-12, R-16).
 *
 * <p>The event is scrubbed before encoding (message, arguments, throwable chain, MDC and key-value
 * pairs), so that secrets are found before JSON escaping changes their spelling; the encoded line
 * is scrubbed once more as a safety net. Used by {@code logback.xml} with the global
 * {@link SecretScrubber}.
 */
public class ScrubbingJsonEncoder extends JsonEncoder {

    private final SecretScrubber scrubber;

    public ScrubbingJsonEncoder() {
        this(SecretScrubber.global());
    }

    ScrubbingJsonEncoder(SecretScrubber scrubber) {
        this.scrubber = scrubber;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encoded = super.encode(new ScrubbedLoggingEvent(event, scrubber));
        String line = new String(encoded, StandardCharsets.UTF_8);
        String scrubbed = scrubber.scrub(line);
        return scrubbed.equals(line) ? encoded : scrubbed.getBytes(StandardCharsets.UTF_8);
    }
}
