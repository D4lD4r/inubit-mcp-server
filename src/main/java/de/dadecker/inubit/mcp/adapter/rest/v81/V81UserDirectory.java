package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The 8.1 {@link UserDirectoryPort}: {@code GET /ibis/rest/user/users?type=processEngineUser}
 * (research D-21; recorded read-only, {@code fixtures/v8_1/rest/user_users.xml}) through the
 * node's authenticated REST client, so that its credential guard applies. The answer is a
 * {@code UserList} of {@code User} elements; only their {@code id} attribute is used — names and
 * e-mail addresses are never read into the result.
 */
public final class V81UserDirectory implements UserDirectoryPort {

    public static final String PATH = "/ibis/rest/user/users";
    private static final Map<String, String> QUERY = Map.of("type", "processEngineUser");

    private final NodeId node;
    private final InubitHttpClient client;

    public V81UserDirectory(NodeId node, InubitHttpClient client) {
        this.node = Objects.requireNonNull(node, "node");
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * @throws de.dadecker.inubit.mcp.domain.model.ToolErrorException the REST client's errors,
     *     {@code UNEXPECTED_RESPONSE} if the answer is not a {@code UserList}
     */
    @Override
    public Set<String> users() {
        Document document = InventoryXml.parse(node, client.get(PATH, QUERY).body());
        Element root = document.getDocumentElement();
        if (!XmlSupport.localName(root).equals("UserList")) {
            throw InventoryXml.unexpected(node, "The user list of " + node
                + " is not a UserList");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Element user : XmlSupport.children(root, "User")) {
            XmlSupport.attribute(user, "id").map(String::strip).filter(id -> !id.isEmpty())
                .ifPresent(ids::add);
        }
        return Collections.unmodifiableSet(ids);
    }
}
