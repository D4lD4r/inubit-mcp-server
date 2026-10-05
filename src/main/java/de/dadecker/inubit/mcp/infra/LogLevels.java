package de.dadecker.inubit.mcp.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.Locale;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/** Applies the configured {@code logLevel} (FR-030) to the root logger. */
public final class LogLevels {

    private LogLevels() {
    }

    /** Sets the root level of the active Logback context, if Logback is the SLF4J backend. */
    public static void apply(String level) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            apply(context, level);
        }
    }

    /** Sets the root level of {@code context}; {@code level} is one of ERROR … TRACE. */
    public static void apply(LoggerContext context, String level) {
        Level parsed = Level.toLevel(level.toUpperCase(Locale.ROOT), null);
        if (parsed == null) {
            throw new IllegalArgumentException("Unknown log level: " + level);
        }
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(parsed);
    }
}
