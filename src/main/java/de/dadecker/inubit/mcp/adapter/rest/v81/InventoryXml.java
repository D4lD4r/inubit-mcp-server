package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Shared XML helpers of the 8.1 inventory parsers (REST and CLI exports). */
public final class InventoryXml {

    private InventoryXml() {
    }

    /** {@link XmlSupport#parse(byte[])} with the server id on a failure. */
    public static Document parse(NodeId server, byte[] xml) {
        try {
            return XmlSupport.parse(xml);
        } catch (ToolErrorException e) {
            throw new ToolErrorException(e.error().withNode(server));
        }
    }

    /** The trimmed text of the direct child {@code localName}; absent if missing or blank. */
    public static Optional<String> childText(Element parent, String localName) {
        return XmlSupport.firstChild(parent, localName).map(XmlSupport::text)
            .filter(text -> !text.isEmpty());
    }

    /** An {@code UNEXPECTED_RESPONSE} of {@code server} for an inventory document. */
    public static ToolErrorException unexpected(NodeId server, String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE, message,
            "INUBIT answered with a document of another structure (another INUBIT version, a"
                + " proxy page, or a changed export format)",
            "Check the INUBIT server and its version with get_health").withNode(server));
    }
}
