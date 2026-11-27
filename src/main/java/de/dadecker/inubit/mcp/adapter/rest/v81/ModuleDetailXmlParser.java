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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/**
 * Parses the answer of {@code GET /model/nodeByModel/{diagram}/{moduleId}} into a
 * {@link ModuleDetail} (feature 022, US3, research D-21). The forms are those of the 8.1.17
 * recording (T039, fixtures {@code admin-model-node-by-model*}, research "Live recording T039"):
 *
 * <ul>
 *   <li>{@code 200}: XML, also when JSON was asked for. The root {@code ns4:Node} has
 *       {@code name}, {@code type}, {@code id} and an optional {@code parentId}; its children are
 *       one {@code ns4:Connection id= targetId=} per outgoing connection and
 *       {@code ns2:Properties} with {@code ns2:Property name= type=} and the value as text. A
 *       {@code Map} or {@code List} property has {@code ns2:Property name= type=} children
 *       instead, its entries (structure probe of a system diagram element {@code sdExtSystem},
 *       2026-11-27: {@code ResourceSettings} Map, {@code ResourcesLinked} List of {@code Module}).
 *       Elements are matched by local name; other elements are ignored. The document is read
 *       with the hardening of {@link XmlSupport} (no DTD, nothing external).
 *   <li>{@code 500} {@code text/html}: the status page whose {@code @Start@…@End@} message says
 *       {@code The module with the id &apos;<id>&apos; in the model &apos;<wf>&apos;could not be
 *       found!} is {@code NOT_FOUND}.
 *   <li>{@code 500} with the {@code ApiError} message {@code Error while getting node by model}:
 *       INUBIT 9.0.10's answer to an unknown module id (T039a), which names no module and cannot
 *       be told apart from another failure of the endpoint. It is {@code UNEXPECTED_RESPONSE}
 *       with the hint that the module id probably does not exist in the diagram (owner decision
 *       2026-11-14).
 * </ul>
 *
 * <p>A property is masked by {@link SecretRules} (D-19): by its name, by its type (so
 * {@code type="Password"} is masked also when its value is empty) and by a key-material value. In
 * a value shown, {@link SecretTextMasker} masks a credential inside the text (e.g.
 * {@code ;password=…} or {@code ?password=…} in a JDBC URL) with its {@code ${secret:…}}
 * placeholder; such a property is not masked as a whole and does not count in
 * {@code maskedCount}. The value is masked before it is cut to {@link ItemBounds#MAX_ITEM_CHARS}.
 * Each entry of a container is masked like a property and counts on its own; a container whose
 * name or type is secret is masked as a whole and counts once.
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
    /** The generic message of INUBIT 9.0.10's {@code 500}, also for an unknown module id. */
    private static final Pattern NODE_BY_MODEL_ERROR = Pattern.compile(
        "<message>Error while getting node by model</message>");
    /** Raw prefix of the body that becomes the excerpt. */
    private static final int EXCERPT_WINDOW = 4096;
    /** Masked beyond the window, so that no secret crossing the cut survives in part. */
    private static final int EXCERPT_OVERLAP = 512;
    private static final String QNAME = "(?:[A-Za-z_][A-Za-z0-9_.-]*+:)?+";
    /** A {@code Property} start, end or empty-element tag. */
    private static final Pattern PROPERTY_TAG = Pattern.compile("<(/?)" + QNAME + PROPERTY
        + "\\b[^<>]*+>");
    /** Containers nested deeper than this are not a recorded form. */
    static final int MAX_DEPTH = 16;
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
     *     excerpt) for the 9.0 generic error (with the hint above), any other answer, a document
     *     that is not the {@code Node} above, or a {@code Node} of another module id
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
        if (response.status() == 500
            && NODE_BY_MODEL_ERROR.matcher(response.bodyText()).find()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                "Module " + moduleId + " in diagram '" + diagram + "' could not be read on "
                    + server + ": INUBIT answered with status 500 \"Error while getting node by"
                    + " model\"",
                "INUBIT answers an unknown module id with this generic error, which it also"
                    + " gives for other failures; the module id probably does not exist in the"
                    + " diagram",
                "Check the diagram name and the module id; if both are right, see the excerpt and"
                    + " the INUBIT system log")
                .withNode(server).withExcerpt(excerpt(response)));
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
                properties.add(property(server, response, property, 0));
            }
        }
        int masked = (int) properties.stream().mapToLong(Property::maskedCount).sum();
        return new ModuleDetail(diagram, moduleId, name, type,
            XmlSupport.attribute(root, PARENT_ID), connections, properties, masked);
    }

    private static Property property(NodeId server, RestResponse response, Element property,
        int depth) {
        String name = required(server, response, property, NAME);
        String type = required(server, response, property, TYPE);
        if (hasChildElement(property)) {
            return container(server, response, property, name, type, depth);
        }
        String value = property.getTextContent();
        if (SecretRules.masks(name, Optional.of(type), false, value)) {
            return Property.masked(name, type);
        }
        String shown = SecretTextMasker.mask(value);
        return Property.shown(name, type, RowValues.cut(shown, ItemBounds.MAX_ITEM_CHARS),
            shown.length() > ItemBounds.MAX_ITEM_CHARS);
    }

    /**
     * A {@code Map} or {@code List} property: masked as a whole by a secret name (so nothing
     * below it is shown), else one entry per {@code Property} child, each read like a property.
     * Any other child element, text next to the entries (comments are ignored) or nesting
     * deeper than {@link #MAX_DEPTH} levels is not a recorded form.
     */
    private static Property container(NodeId server, RestResponse response, Element property,
        String name, String type, int depth) {
        if (depth >= MAX_DEPTH) {
            throw unexpected(server, response, "has properties nested deeper than " + MAX_DEPTH
                + " levels");
        }
        List<Property> entries = new ArrayList<>();
        for (Node child = property.getFirstChild(); child != null;
            child = child.getNextSibling()) {
            if (child instanceof Element entry && XmlSupport.localName(entry).equals(PROPERTY)) {
                entries.add(property(server, response, entry, depth + 1));
            } else if (child instanceof Element
                || child instanceof Text text && !text.getData().isBlank()) {
                throw unexpected(server, response, "has a property whose value is neither text"
                    + " nor a list of properties");
            }
        }
        if (SecretRules.masks(name, Optional.of(type), false, "")) {
            return Property.masked(name, type);
        }
        return Property.entries(name, type, entries);
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
            maskPropertyContent(raw)));
        String text = masked.substring(0, Math.min(masked.length(), EXCERPT_WINDOW));
        if (html(response)) {
            text = HTML_TAG.matcher(text).replaceAll(" ");
        }
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Keeps the start tag of every outermost {@code Property} element and replaces its whole
     * content, nested properties, text and other elements included, by the mask token up to its
     * matching end tag (or the end of the text, when it is cut inside).
     */
    static String maskPropertyContent(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        Matcher tag = PROPERTY_TAG.matcher(raw);
        int keepFrom = 0;
        int depth = 0;
        while (tag.find()) {
            boolean end = !tag.group(1).isEmpty();
            boolean empty = tag.group().endsWith("/>");
            if (depth == 0) {
                if (!end && !empty) {
                    out.append(raw, keepFrom, tag.end()).append(Masked.MASK_TOKEN);
                    keepFrom = tag.end();
                    depth = 1;
                }
            } else if (end) {
                depth--;
                if (depth == 0) {
                    keepFrom = tag.start();
                }
            } else if (!empty) {
                depth++;
            }
        }
        if (depth == 0) {
            out.append(raw, keepFrom, raw.length());
        }
        return out.toString();
    }
}
