package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.CliTagRunner;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.TagPort;
import java.util.Objects;

/**
 * The 8.1 {@link TagPort} (feature 004, research D-16, D-25 M1): the histories come from
 * StartCLI history exports ({@link CliExportRunner#exportHistoryAllGroups} for every diagram
 * of the owner, {@link CliExportRunner#exportHistory} for one diagram group), parsed by
 * {@link VersionHistoryParser#parseHistory}; the tag commands run through
 * {@link CliTagRunner} after unconfirmed credentials are confirmed with the server's REST login
 * (as for imports, so that a StartCLI run never holds the credential guard's single permit).
 */
public final class V81TagAdapter implements TagPort {

    private final NodeId server;
    private final CliExportRunner exports;
    private final CliTagRunner tags;
    private final Runnable confirmCredentials;

    /**
     * @param confirmCredentials confirms unconfirmed credentials (a no-op once confirmed)
     */
    public V81TagAdapter(NodeId server, CliExportRunner exports, CliTagRunner tags,
        Runnable confirmCredentials) {
        this.server = Objects.requireNonNull(server, "server");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.confirmCredentials = Objects.requireNonNull(confirmCredentials,
            "confirmCredentials");
    }

    @Override
    public void checkAvailable() {
        tags.checkAvailable();
    }

    @Override
    public History history(String owner) {
        return VersionHistoryParser.parseHistory(server, exports.exportHistoryAllGroups(owner));
    }

    @Override
    public History history(String owner, String diagramGroup) {
        return VersionHistoryParser.parseHistory(server, exports.exportHistory(owner,
            "technical", diagramGroup));
    }

    @Override
    public void tag(String tag, String diagramGroup, String owner) {
        tags.checkAvailable();
        confirmCredentials.run();
        tags.tag(tag, diagramGroup, owner);
    }

    @Override
    public void deleteTag(String tag, String owner) {
        tags.checkAvailable();
        confirmCredentials.run();
        tags.deleteTag(tag, owner);
    }

    @Override
    public String toString() {
        return "V81TagAdapter[" + tags + "]";
    }
}
