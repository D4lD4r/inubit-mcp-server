package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Parses the 8.1 {@code GET /user/users?type=processEngineUser} answer into the listed logins
 * (feature 022, US2, research D-20). The layout is that of the recording of an 8.1.17 server
 * (T028, fixture {@code admin-users.xml}, research "Live recording T028"): a root
 * {@code UserList} with one empty {@code <User id email type firstName lastName>} per user,
 * unsorted. Elements are matched by local name in any namespace, so the recorded default
 * namespace and the {@code ns2:UserList} form of feature 004 read the same.
 *
 * <p>Only the {@code id} and {@code type} attributes of the direct {@code User} children of the
 * root are read. {@code email}, {@code firstName}, {@code lastName}, any other attribute and any
 * other element are never read into an object (FR-011, SC-005): the document is streamed with the
 * secure StAX reader of {@link XmlSupport} (a DTD or entity is refused, nothing external is
 * resolved) instead of being held as a DOM tree. The users keep the document order; sorting and
 * paging are the caller's.
 *
 * <p>A document that is not well-formed, declares a DTD, has another root or a user without an
 * {@code id} is {@code UNEXPECTED_RESPONSE}; no error echoes the content.
 */
final class UserListXmlParser {

    static final String ROOT = "UserList";
    static final String USER = "User";
    private static final String ID = "id";
    private static final String TYPE = "type";

    private UserListXmlParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     not a well-formed {@code UserList} without a DTD, or a user has no {@code id}
     */
    static List<ListedUser> parse(NodeId server, byte[] body) {
        try {
            XMLStreamReader reader = XmlSupport.newStreamReader(new ByteArrayInputStream(body));
            try {
                return users(server, reader);
            } finally {
                reader.close();
            }
        } catch (ToolErrorException e) {
            throw e;
        } catch (XMLStreamException | RuntimeException e) {
            // the StAX contract does not rule out unchecked exceptions on hostile input; the
            // parser's own message is never used, as it may quote content
            throw unexpected(server, "is not acceptable XML (not well-formed, or it declares a"
                + " DTD)");
        }
    }

    private static List<ListedUser> users(NodeId server, XMLStreamReader reader)
        throws XMLStreamException {
        List<ListedUser> users = new ArrayList<>();
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (depth == 1 && !ROOT.equals(reader.getLocalName())) {
                    throw unexpected(server, "is not a <" + ROOT + "> document");
                }
                if (depth == 2 && USER.equals(reader.getLocalName())) {
                    users.add(user(server, reader));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return List.copyOf(users);
    }

    /** Reads {@code id} and {@code type} of the current {@code User} start tag, nothing else. */
    private static ListedUser user(NodeId server, XMLStreamReader reader) {
        String login = Optional.ofNullable(reader.getAttributeValue(null, ID))
            .filter(id -> !id.isBlank())
            .orElseThrow(() -> unexpected(server, "has a user without an id"));
        String type = Optional.ofNullable(reader.getAttributeValue(null, TYPE)).orElse("");
        return new ListedUser(login, type);
    }

    private static ToolErrorException unexpected(NodeId server, String problem) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT user list of " + server + " " + problem,
            "The endpoint answered with an unexpected document (another INUBIT version, a proxy"
                + " page, or a user list format this version does not know)",
            "Check the INUBIT version line and the answer of GET /user/users on the server")
            .withNode(server));
    }
}
