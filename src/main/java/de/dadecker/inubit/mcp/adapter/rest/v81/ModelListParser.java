package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.InventoryItem;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Parses the 8.1 diagram list of {@code GET /ibis/rest/model/models?user=<owner>} (research
 * R-11, S-6; fixture {@code model_models_owner.xml}): a {@code ModelList} of
 * {@code <Model name type version group>} elements, matched by local name in any namespace.
 *
 * <p>Each model becomes a diagram {@link InventoryItem} of {@code owner} (the list carries no
 * owner; INUBIT filters by the {@code user} parameter). A missing {@code type} or {@code group}
 * becomes empty text; the {@code Comment} child and the {@code version} attribute (always
 * {@code head} in the list) are not used.
 */
public final class ModelListParser {

    private ModelListParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a {@code ModelList} or a model has no name
     */
    public static List<InventoryItem> parse(NodeId server, String owner, byte[] body) {
        Document document = InventoryXml.parse(server, body);
        Element root = document.getDocumentElement();
        if (!XmlSupport.localName(root).equals("ModelList")) {
            throw InventoryXml.unexpected(server, "The diagram list of " + server
                + " is not a ModelList");
        }
        List<InventoryItem> items = new ArrayList<>();
        for (Element model : XmlSupport.children(root, "Model")) {
            String name = XmlSupport.attribute(model, "name").filter(n -> !n.isBlank())
                .orElseThrow(() -> InventoryXml.unexpected(server,
                    "A model in the diagram list of " + server + " has no name"));
            items.add(InventoryItem.diagram(server, name,
                XmlSupport.attribute(model, "type").orElse(""),
                XmlSupport.attribute(model, "group").orElse(""), owner));
        }
        return List.copyOf(items);
    }
}
