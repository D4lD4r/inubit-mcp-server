package de.dadecker.inubit.mcp.config;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether the update check runs (feature 016, FR-010, FR-011; contracts/configuration-delta.md):
 * only if neither {@code updateCheck.enabled: false} nor {@link #VARIABLE} turns it off.
 *
 * @param on      whether the check runs
 * @param offBy   what turned it off ({@code updateCheck.enabled: false} or the variable name)
 * @param warning a warning about an unrecognised value of the variable (never echoes the value)
 */
public record UpdateCheckSetting(boolean on, Optional<String> offBy, Optional<String> warning) {

    public static final String VARIABLE = "INUBIT_MCP_UPDATE_CHECK";

    private static final Set<String> OFF = Set.of("off", "false", "0", "no");
    private static final Set<String> ON = Set.of("on", "true", "1", "yes");

    public static UpdateCheckSetting resolve(UpdateCheckConfig config,
        Map<String, String> environment) {
        String raw = environment.get(VARIABLE);
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        Optional<String> warning = Optional.empty();
        if (OFF.contains(value)) {
            return new UpdateCheckSetting(false, Optional.of(VARIABLE), warning);
        }
        if (!value.isEmpty() && !ON.contains(value)) {
            warning = Optional.of(VARIABLE + " has an unrecognised value; use off or on (the update"
                + " check stays on)");
        }
        if (!config.on()) {
            return new UpdateCheckSetting(false, Optional.of("updateCheck.enabled: false"),
                warning);
        }
        return new UpdateCheckSetting(true, Optional.empty(), warning);
    }
}
