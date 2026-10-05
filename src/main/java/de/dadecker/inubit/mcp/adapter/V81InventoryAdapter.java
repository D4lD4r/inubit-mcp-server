package de.dadecker.inubit.mcp.adapter;

import de.dadecker.inubit.mcp.adapter.cli.CliExportRunner;
import de.dadecker.inubit.mcp.adapter.cli.v81.ModuleIndexParser;
import de.dadecker.inubit.mcp.adapter.cli.v81.VersionHistoryParser;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.v81.DiagramExportParser;
import de.dadecker.inubit.mcp.adapter.rest.v81.ModelDetailParser;
import de.dadecker.inubit.mcp.adapter.rest.v81.ModelListParser;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.InventoryPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The 8.1 {@link InventoryPort}, combining REST and CLI sources (research R-11):
 *
 * <ul>
 *   <li>Diagram list: {@code GET /ibis/rest/model/models?user=<owner>}.
 *   <li>Diagram nodes: {@code GET /ibis/rest/model/modelByName/<name>?user=<owner>} (the name
 *       percent-encoded as one path segment; BPD names contain spaces).
 *   <li>Diagram metadata: {@code GET /ibis/rest/model/export/<name>}, a ZIP parsed in memory
 *       ({@link DiagramExportParser}).
 *   <li>Version history and module index: StartCLI exports ({@link CliExportRunner}), parsed by
 *       {@link VersionHistoryParser} and {@link ModuleIndexParser}. Before an export that can
 *       run, unconfirmed credentials are confirmed with {@code GET /ibis/rest/system/info}.
 * </ul>
 *
 * <p>REST failures come from {@link InubitHttpClient} ({@code 404} → {@code NOT_FOUND}, …), CLI
 * failures from {@link CliExportRunner}; all carry the server id.
 */
public final class V81InventoryAdapter implements InventoryPort {

    private static final String MODELS = "/ibis/rest/model/models";
    private static final String MODEL_BY_NAME = "/ibis/rest/model/modelByName/";
    private static final String EXPORT = "/ibis/rest/model/export/";
    private static final String SYSTEM_INFO = "/ibis/rest/system/info";

    private final NodeId server;
    private final InubitHttpClient client;
    private final CliExportRunner exports;

    public V81InventoryAdapter(NodeId server, InubitHttpClient client, CliExportRunner exports) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.exports = Objects.requireNonNull(exports, "exports");
    }

    @Override
    public List<InventoryItem> listDiagrams(String owner) {
        byte[] body = client.get(MODELS, Map.of("user", owner)).body();
        return ModelListParser.parse(server, owner, body);
    }

    @Override
    public DiagramDetail diagramDetail(String owner, String name) {
        byte[] body = client.get(MODEL_BY_NAME + InubitHttpClient.encodePathSegment(name),
            Map.of("user", owner)).body();
        return ModelDetailParser.parse(server, body);
    }

    @Override
    public DiagramMetadata diagramMetadata(String name) {
        byte[] body = client.get(EXPORT + InubitHttpClient.encodePathSegment(name), Map.of())
            .body();
        return DiagramExportParser.parse(server, body);
    }

    @Override
    public VersionHistory versionHistory(String owner, String type, String group) {
        exports.checkHistoryExport(owner, type, group);
        confirmCredentials();
        return VersionHistoryParser.parse(server, exports.exportHistory(owner, type, group));
    }

    @Override
    public List<ModuleEntry> listModules(String owner) {
        exports.checkModuleExport(owner);
        confirmCredentials();
        return ModuleIndexParser.parse(server, owner, exports.exportModules(owner));
    }

    /**
     * Review I3: while the server's credentials are unconfirmed, the credential guard lets only
     * one authenticated call run at a time. A StartCLI export (10–15 s) would hold that single
     * permit for its whole run and block every REST call of the server, so a cheap authenticated
     * {@code GET /ibis/rest/system/info} confirms the credentials first (and a rejected login
     * stops the export before StartCLI starts). Nothing is sent once they are confirmed.
     * HTTP 403 counts as accepted (US3 re-review N1): the login succeeded and the guard is
     * confirmed, only the account may not read the system information, which the export does
     * not need.
     */
    private void confirmCredentials() {
        if (!client.credentialGuard().confirmed()) {
            client.get(SYSTEM_INFO, Map.of(), 403);
        }
    }

    @Override
    public String toString() {
        return "V81InventoryAdapter[" + server + "]";
    }
}
