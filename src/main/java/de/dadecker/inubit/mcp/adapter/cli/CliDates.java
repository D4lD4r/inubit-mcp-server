package de.dadecker.inubit.mcp.adapter.cli;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * The zone-less timestamps StartCLI shows, local time {@code Europe/Berlin} without offset: the
 * German form {@code dd.MM.uuuu HH:mm:ss} (8.1 exports {@code DateTime} of
 * {@code versionHistory.xml} and {@code LastUpdate} of {@code module.xml}, spike S-6b; German 6.1
 * {@code ps} listings) and the ISO local form {@code uuuu-MM-ddTHH:mm:ss} (English 6.1 {@code ps}
 * listings, research D-10). In the hour that occurs twice when daylight saving time ends, the
 * earlier instant is taken; a time in the hour skipped when it starts is shifted forward by the
 * gap.
 */
public final class CliDates {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter GERMAN = DateTimeFormatter
        .ofPattern("dd.MM.uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private CliDates() {
    }

    /** The instant of {@code text}; absent if it is not a valid timestamp of either form. */
    public static Optional<Instant> parse(String text) {
        String value = text.strip();
        DateTimeFormatter format = value.indexOf('T') >= 0 ? ISO_LOCAL : GERMAN;
        try {
            return Optional.of(LocalDateTime.parse(value, format).atZone(ZONE).toInstant());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
