package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the INUBIT version of a server for {@code versionLine: AUTO} (research R-10): one
 * authenticated {@code GET /ibis/rest/system/info}, then the value of
 * {@code <SystemInformation name="Version" value="…"/>} in the {@code SystemInformationList}
 * ({@link SystemInfoXmlParser}, shared with the health tool).
 */
public final class V81VersionDetector {

    public static final String SYSTEM_INFO_PATH = "/ibis/rest/system/info";

    private V81VersionDetector() {
    }

    /**
     * @throws ToolErrorException the client's error, or {@code UNEXPECTED_RESPONSE} if the
     *     response has no version
     */
    public static String detect(NodeId server, InubitHttpClient client) {
        Objects.requireNonNull(server, "server");
        byte[] body;
        try {
            body = client.get(SYSTEM_INFO_PATH, Map.of()).body();
        } catch (ToolErrorException e) {
            throw e.error().node().isPresent() ? e
                : new ToolErrorException(e.error().withNode(server));
        }
        Optional<String> version = SystemInfoXmlParser.parse(server, body).version();
        if (version.isPresent()) {
            return version.get();
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT system information of " + server + " contains no version",
            "The endpoint answered with an unexpected document (another INUBIT version or a"
                + " proxy page)",
            "Set versionLine: V8_1 for " + server + " in the configuration, or check the INUBIT server")
            .withNode(server));
    }
}
