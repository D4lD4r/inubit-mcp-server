package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.CliHealthSnapshot;
import java.time.Duration;

/**
 * Reads the health of a node reached through StartCLI only (feature 011, research D-6): one
 * StartCLI session per call. Handed out by {@link GatewayFactory#cliHealth} for a node whose
 * effective version selects it.
 */
public interface CliHealthPort {

    /**
     * Runs the health session within {@code budget}; parts the session could not read are listed
     * in {@link CliHealthSnapshot#missing()}.
     *
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException if the session cannot be
     *     run at all (e.g. {@code CLI_UNAVAILABLE}, {@code AUTH_FAILED}, {@code TLS_ERROR},
     *     {@code TIMEOUT})
     */
    CliHealthSnapshot snapshot(Duration budget);
}
