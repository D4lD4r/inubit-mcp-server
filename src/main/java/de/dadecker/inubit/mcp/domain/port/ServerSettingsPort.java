package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.CliSettingsSnapshot;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the settings of one node for {@code get_server_settings} (feature 022, US1): the license
 * and the configuration over REST, the other parts over StartCLI (research D-7). Thread-safe.
 */
public interface ServerSettingsPort {

    /**
     * The license facts of {@code GET /license}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set, e.g.
     *     {@code FORBIDDEN} naming the role "System Administrator", {@code MAINTENANCE_MODE}, or
     *     {@code UNEXPECTED_RESPONSE} for an answer without a readable expiry date
     */
    LicenseFacts license();

    /**
     * The section names of {@code GET /configurations} if {@code section} is empty, otherwise the
     * leaves of that section, bounded by the call's result limits.
     *
     * @param maxValues the most leaves returned (the call's {@code ResultLimiter.maxItems()}, at
     *     least 1)
     * @param maxChars  the most chars the returned leaves take together (the call's
     *     {@code ResultLimiter.maxChars()})
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException as {@link #license()}, and
     *     {@code NOT_FOUND} for a section the configuration does not have
     */
    ConfigurationView configuration(Optional<String> section, int maxValues, int maxChars);

    /**
     * Reads the StartCLI parts in one session.
     *
     * @param parts {@code threads}, {@code privilegedWorkflows}, {@code dbConnectionTolerance}
     *     and/or {@code serverTime} ({@link de.dadecker.inubit.mcp.domain.model.UnavailablePart}
     *     names); none starts nothing
     * @return each asked part, or the part in {@code unavailable} with the reason (FR-013)
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException with the node set, for a
     *     failure of the whole session (e.g. {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED},
     *     {@code TIMEOUT})
     * @throws IllegalArgumentException for a part that is not read over StartCLI
     */
    CliSettingsSnapshot cliSettings(Set<String> parts);
}
