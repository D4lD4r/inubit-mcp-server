package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.WritePermit;
import java.util.Optional;

/**
 * The tags of one INUBIT server for {@code manage_tags} (feature 022, US4, research D-18), over
 * StartCLI: the active tag of a user. Tag removal and deletion are excluded after the live check
 * T055 (owner decision IMC-7), so this port has no form for them. Failures are thrown as
 * {@link de.dadecker.inubit.mcp.domain.model.ToolErrorException} with the server id.
 *
 * <p>Every change takes the {@link WritePermit} of the call's admission for this port's node and
 * is handed off under the node's {@link WriteLock}; after a version change since the admission it
 * is refused with {@code UNSUPPORTED_VERSION} and nothing is sent. A permit for another node is an
 * {@link IllegalArgumentException}; nothing is sent. The active tag is read in a RECONSTRUCTED
 * form (no user with an active tag is recorded), and a change counts as done when StartCLI exits
 * with 0 and no {@code n-NOK} message, as for {@code tag --tagMove}.
 */
public interface TagAdministrationPort {

    /**
     * The active tag of {@code user}, read with StartCLI {@code user '<user>'}; empty if the
     * answer names none.
     */
    Optional<String> activeTag(String user);

    /** Sets the active tag of {@code user} ({@code tag --tagSetActive --tagUser}). */
    void setActiveTag(String user, String tag, WritePermit permit);

    /** Removes the active tag of {@code user} ({@code tag --tagRemoveActive --tagUser}). */
    void removeActiveTag(String user, WritePermit permit);
}
