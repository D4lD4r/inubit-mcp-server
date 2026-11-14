package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.ResourceKind;
import de.dadecker.inubit.mcp.adapter.rest.RestEndpoint;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.ModelDetailPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The 8.1 {@link ModelDetailPort} (feature 022, US3, research D-21).
 *
 * <ul>
 *   <li>{@code GET /ibis/rest/model/nodeByModel/{diagram}/{moduleId}} with credentials and
 *       {@code Accept} XML ({@link ModuleDetailXmlParser}). INUBIT 8.1.17 answers XML also when
 *       JSON is asked for (T039). Its {@code 500} is handed to the parser, which tells the
 *       recorded "could not be found" page ({@code NOT_FOUND}) from any other answer;
 *   <li>the library list is passed to {@code libraries} (in production
 *       {@code CliLibraryRunner::list}), so this REST package does not reach the StartCLI adapter.
 * </ul>
 *
 * <p>Every other HTTP status keeps the mapping of {@link InubitHttpClient}, e.g. 403
 * {@code FORBIDDEN}, 503 during maintenance {@code MAINTENANCE_MODE}; a 404 is a missing endpoint
 * ({@code UNEXPECTED_RESPONSE}): an unknown module id answers 500, not 404.
 */
public final class V81ModelDetailAdapter implements ModelDetailPort {

    private static final int SERVER_ERROR = 500;

    private final NodeId server;
    private final InubitHttpClient client;
    private final Function<String, LibraryEntry.Listing> libraries;

    public V81ModelDetailAdapter(NodeId server, InubitHttpClient client,
        Function<String, LibraryEntry.Listing> libraries) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
        this.libraries = Objects.requireNonNull(libraries, "libraries");
    }

    @Override
    public ModuleDetail module(String diagram, String moduleId) {
        RestResponse response;
        try {
            response = client.get(RestEndpoint.NODE_BY_MODEL, List.of(diagram, moduleId),
                Map.of(), MediaRange.XML, ResourceKind.ENDPOINT, SERVER_ERROR);
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
        return ModuleDetailXmlParser.parse(server, diagram, moduleId, response);
    }

    @Override
    public LibraryEntry.Listing libraries(String type) {
        return libraries.apply(type);
    }

    @Override
    public String toString() {
        return "V81ModelDetailAdapter[" + server + "]";
    }
}
