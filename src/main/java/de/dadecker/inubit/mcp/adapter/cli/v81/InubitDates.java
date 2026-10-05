package de.dadecker.inubit.mcp.adapter.cli.v81;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * The timestamps of the 8.1 CLI exports ({@code DateTime} of {@code versionHistory.xml},
 * {@code LastUpdate} of {@code module.xml}): {@code dd.MM.yyyy HH:mm:ss}, local time
 * {@code Europe/Berlin} without offset (spike S-6b). In the hour that occurs twice when daylight
 * saving time ends, the earlier instant is taken.
 */
final class InubitDates {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
        .ofPattern("dd.MM.uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private InubitDates() {
    }

    /** The instant of {@code text}; absent if it is not a valid timestamp of this format. */
    static Optional<Instant> parse(String text) {
        try {
            return Optional.of(LocalDateTime.parse(text.strip(), FORMAT).atZone(ZONE)
                .toInstant());
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
