package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ItemBounds;
import de.dadecker.inubit.mcp.domain.model.Masked;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Property;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.SecretRules;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Parses the answer of {@code GET /model/nodeByModel/{diagram}/{moduleId}} into a
 * {@link ModuleDetail} (feature 022, US3, research D-21). The forms are those of the 8.1.17
 * recording (T039, fixtures {@code admin-model-node-by-model*}, research "Live recording T039"):
 *
 * <ul>
 *   <li>{@code 200}: XML, also when JSON was asked for. The root {@code ns4:Node} has
 *       {@code name}, {@code type}, {@code id} and an optional {@code parentId}; its children are
 *       one {@code ns4:Connection id= targetId=} per outgoing connection and
 *       {@code ns2:Properties} with {@code ns2:Property name= type=} and the value as text.
 *       Elements are matched by local name; other elements are ignored. The document is read
 *       with the hardening of {@link XmlSupport} (no DTD, nothing external).
 *   <li>{@code 500} {@code text/html}: the status page whose {@code @Start@…@End@} message says
 *       {@code The module with the id &apos;<id>&apos; in the model &apos;<wf>&apos;could not be
 *       found!} is {@code NOT_FOUND}.
 * </ul>
 *
 * <p>A property is masked by {@link SecretRules} (D-19): by its name, by its type (so
 * {@code type="Password"} is masked also when its value is empty) and by a key-material value. In
 * a value shown, {@link SecretTextMasker} masks a credential inside the text (e.g.
 * {@code ;password=…} or {@code ?password=…} in a JDBC URL) with its {@code ${secret:…}}
 * placeholder; such a property is not masked as a whole and does not count in
 * {@code maskedCount}. The value is masked before it is cut to {@link ItemBounds#MAX_ITEM_CHARS}.
 * Any other answer, or a document
 * without these attributes, is {@code UNEXPECTED_RESPONSE} with an excerpt in which the content of
 * every {@code Property} element, known credentials and secret-named values are masked.
 */
final class ModuleDetailXmlParser {

    static final String ROOT = "Node";
    private static final String CONNECTION = "Connection";
    private static final String PROPERTIES = "Properties";
    private static final String PROPERTY = "Property";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String ID = "id";
    private static final String PARENT_ID = "parentId";
    private static final String TARGET_ID = "targetId";

    /** The recorded message of an unknown module id, with the quotes HTML-escaped. */
    private static final Pattern MODULE_NOT_FOUND = Pattern.compile(
        "@Start@The module with the id &apos;[^@<]+&apos;could not be found!@");
    /** Raw prefix of the body that becomes the excerpt. */
    private static final int EXCERPT_WINDOW = 4096;
    /** Masked beyond the window, so that no secret crossing the cut survives in part. */
    private static final int EXCERPT_OVERLAP = 512;
    private static final String QNAME = "(?:[A-Za-z_][A-Za-z0-9_.-]*+:)?+";
    /** A {@code Property} start tag and its content up to its end tag or the end of the text. */
    private static final Pattern PROPERTY_CONTENT = Pattern.compile("(<" + QNAME + PROPERTY
        + "\\b[^<>]*+>)(?s:.*?)(?=</" + QNAME + PROPERTY + "\\s*>|\\z)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ModuleDetailXmlParser() {
    }

    /**
     * @param diagram  the diagram of the request
     * @param moduleId the module id of the request
     * @param response the answer, also a {@code 500} the client was told to accept
     * @throws ToolErrorException {@code NOT_FOUND} (with the server id) for the recorded
     *     "could not be found" page; {@code UNEXPECTED_RESPONSE} (with the server id and a masked
     *     excerpt) for any other answer, a document that is not the {@code Node} above, or a
     *     {@code Node} of another module id
     */
    static ModuleDetail parse(NodeId server, String diagram, String moduleId,
        RestResponse response) {
        if (response.status() == 200) {
            return detail(server, diagram, moduleId, response);
        }
        if (response.status() == 500 && html(response)
            && MODULE_NOT_FOUND.matcher(response.bodyText()).find()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "Module " + moduleId + " was not found in diagram '" + diagram + "' on " + server,
                "INUBIT reports that the diagram has no module with this id",
                "Check the diagram name and the module id").withNode(server));
        }
        throw unexpected(server, response, "answered with status " + response.status());
    }

    private static ModuleDetail detail(NodeId server, String diagram, String moduleId,
        RestResponse response) {
        Element root;
        try {
            root = XmlSupport.parse(response.body()).getDocumentElement();
        } catch (ToolErrorException e) {
            throw unexpected(server, response, "is not acceptable XML (not well-formed, or it"
                + " declares a DTD)");
        }
        if (!XmlSupport.localName(root).equals(ROOT)) {
            throw unexpected(server, response, "is not a <" + ROOT + "> document");
        }
        String name = required(server, response, root, NAME);
        String type = required(server, response, root, TYPE);
        if (!required(server, response, root, ID).equals(moduleId)) {
            throw unexpected(server, response, "is the detail of another module");
        }
        List<Connection> connections = new ArrayList<>();
        for (Element connection : XmlSupport.children(root, CONNECTION)) {
            connections.add(new Connection(required(server, response, connection, ID),
                required(server, response, connection, TARGET_ID)));
        }
        List<Property> properties = new ArrayList<>();
        for (Element group : XmlSupport.children(root, PROPERTIES)) {
            for (Element property : XmlSupport.children(group, PROPERTY)) {
                properties.add(property(server, response, property));
            }
        }
        int masked = (int) properties.stream().filter(Property::masked).count();
        return new ModuleDetail(diagram, moduleId, name, type,
            XmlSupport.attribute(root, PARENT_ID), connections, properties, masked);
    }

    private static Property property(NodeId server, RestResponse response, Element property) {
        String name = required(server, response, property, NAME);
        String type = required(server, response, property, TYPE);
        if (hasChildElement(property)) {
            throw unexpected(server, response, "has a property whose value is not text");
        }
        String value = property.getTextContent();
        if (SecretRules.masks(name, Optional.of(type), false, value)) {
            return Property.masked(name, type);
        }
        String shown = SecretTextMasker.mask(value);
        return Property.shown(name, type, RowValues.cut(shown, ItemBounds.MAX_ITEM_CHARS),
            shown.length() > ItemBounds.MAX_ITEM_CHARS);
    }

    private static boolean hasChildElement(Element element) {
        for (Node child = element.getFirstChild(); child != null;
            child = child.getNextSibling()) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static String required(NodeId server, RestResponse response, Element element,
        String attribute) {
        return XmlSupport.attribute(element, attribute).filter(value -> !value.isBlank())
            .orElseThrow(() -> unexpected(server, response, "has a <"
                + XmlSupport.localName(element) + "> without " + attribute));
    }

    private static boolean html(RestResponse response) {
        return response.contentType()
            .filter(type -> type.toLowerCase(Locale.ROOT).contains("html")).isPresent();
    }

    private static ToolErrorException unexpected(NodeId server, RestResponse response,
        String problem) {
        return new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
            "The INUBIT module detail of " + server + " " + problem,
            "The endpoint answered with an unexpected document (another INUBIT version, a proxy"
                + " page, or a module detail format this version does not know)",
            "See the excerpt; check the INUBIT version line and the INUBIT system log")
            .withNode(server).withExcerpt(excerpt(response)));
    }

    /**
     * The first {@value #EXCERPT_WINDOW} chars of the body with the content of every
     * {@code Property} element, registered credentials and secret-named values masked (over the
     * window and an overlap, so a secret crossing the cut is masked as a whole), HTML tags
     * removed for an HTML answer and white space collapsed; ToolError cuts it further.
     */
    private static String excerpt(RestResponse response) {
        String body = response.bodyText();
        String raw = body.substring(0, Math.min(body.length(), EXCERPT_WINDOW + EXCERPT_OVERLAP));
        String masked = SecretTextMasker.mask(SecretScrubber.global().scrub(
            PROPERTY_CONTENT.matcher(raw).replaceAll("$1" + Masked.MASK_TOKEN)));
        String text = masked.substring(0, Math.min(masked.length(), EXCERPT_WINDOW));
        if (html(response)) {
            text = HTML_TAG.matcher(text).replaceAll(" ");
        }
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }
}
