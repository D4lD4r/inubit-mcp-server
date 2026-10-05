package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ModuleRef;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses the 8.1 model of {@code GET /ibis/rest/model/modelByName/<name>?user=<owner>} (research
 * R-11; fixture {@code modelByName_sample.xml}): the root {@code Model} with {@code name},
 * {@code type} and {@code version}, and its {@code Node} children ({@code name}, {@code type},
 * {@code id}), the modules of the diagram. Nodes without a name are skipped; a missing node
 * {@code type} or {@code id} becomes empty text.
 */
public final class ModelDetailParser {

    private ModelDetailParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a {@code Model} with a name
     */
    public static DiagramDetail parse(NodeId server, byte[] body) {
        Document document = InventoryXml.parse(server, body);
        Element root = document.getDocumentElement();
        if (!XmlSupport.localName(root).equals("Model")) {
            throw InventoryXml.unexpected(server, "The diagram of " + server
                + " is not a Model");
        }
        String name = XmlSupport.attribute(root, "name").filter(n -> !n.isBlank())
            .orElseThrow(() -> InventoryXml.unexpected(server, "The diagram of " + server
                + " has no name"));
        List<ModuleRef> modules = new ArrayList<>();
        for (Element node : XmlSupport.descendants(root, "Node")) {
            Optional<String> nodeName = XmlSupport.attribute(node, "name")
                .filter(n -> !n.isBlank());
            nodeName.ifPresent(n -> modules.add(new ModuleRef(n,
                XmlSupport.attribute(node, "type").orElse(""),
                XmlSupport.attribute(node, "id").orElse(""))));
        }
        return new DiagramDetail(name, nonBlank(XmlSupport.attribute(root, "type")),
            nonBlank(XmlSupport.attribute(root, "version")), modules);
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }
}
