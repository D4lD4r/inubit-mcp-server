package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.domain.port.ArtifactPort;
import java.util.Objects;

/**
 * The 8.1 {@link ArtifactPort}: StartCLI exports through {@link CliExportRunner} (research D-8).
 * Each request is checked first (blank names, quoting, CLI, credentials); only an export that can
 * run confirms unconfirmed credentials with the server's REST login (feature 002, review I3: a
 * StartCLI run must not hold the single permit of the credential guard), then StartCLI runs.
 */
public final class V81ArtifactAdapter implements ArtifactPort {

    private final CliExportRunner exports;
    private final Runnable confirmCredentials;

    /**
     * @param exports the StartCLI exports of the server
     * @param confirmCredentials confirms unconfirmed credentials (a no-op once confirmed)
     */
    public V81ArtifactAdapter(CliExportRunner exports, Runnable confirmCredentials) {
        this.exports = Objects.requireNonNull(exports, "exports");
        this.confirmCredentials = Objects.requireNonNull(confirmCredentials,
            "confirmCredentials");
    }

    @Override
    public byte[] exportWorkflowGroup(String owner, String diagramGroup) {
        exports.checkWorkflowGroupExport(owner, diagramGroup);
        confirmCredentials.run();
        return exports.exportWorkflowGroup(owner, diagramGroup);
    }

    @Override
    public byte[] exportModule(String owner, String pluginType, String name) {
        exports.checkModuleExport(owner, pluginType, name);
        confirmCredentials.run();
        return exports.exportModule(owner, pluginType, name);
    }

    @Override
    public String toString() {
        return "V81ArtifactAdapter[" + exports + "]";
    }
}
