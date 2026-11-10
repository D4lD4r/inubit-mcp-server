package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.rest.XmlSupport;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.InventoryPort.DiagramMetadata;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Reads the head metadata of a diagram from the ZIP of {@code GET /ibis/rest/model/export/<name>}
 * (research R-11, S-6b; fixture {@code model_export_sample.zip}).
 *
 * <ul>
 *   <li>The download has no {@code Content-Type}; it is recognised by the ZIP signature
 *       {@code PK\3\4}.
 *   <li>The ZIP is read in memory and nothing is written to disk: besides the workflow it holds
 *       the full module configurations, which must not leave the process.
 *   <li>Only {@code workflow/workflow.xml} is read (at most {@link #MAX_ENTRY_BYTES}); every
 *       other entry is skipped unread. From it only {@code IBISWorkflow/Workflows/WorkflowGroup/
 *       Workflow/{IsActive, CheckinComment, UserOrUserGroupName}} of the first workflow are
 *       taken (direct children, so the fields of nested module subtrees do not count).
 *   <li>From the whole workflow (spec 018, FR-015): the distinct, sorted
 *       {@code ISPartner(Protocol|Parameter).<name>} references in every attribute value and
 *       text (comments not), and whether an element has {@code moduleType="PartnerManagement"}.
 *   <li>An export without {@code workflow/workflow.xml} (e.g. of a non-technical diagram) has
 *       no metadata ({@link DiagramMetadata#EMPTY}).
 * </ul>
 */
public final class DiagramExportParser {

    /** The only entry that is read. */
    public static final String WORKFLOW_ENTRY = "workflow/workflow.xml";
    /** Upper bound of the uncompressed workflow entry. */
    public static final long MAX_ENTRY_BYTES = 64L << 20;

    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    /** A reference to a partner protocol setting or additional parameter. */
    private static final Pattern PARTNER_VARIABLE =
        Pattern.compile("ISPartner(?:Protocol|Parameter)\\.[A-Za-z0-9_]+");
    private static final String PARTNER_MANAGEMENT = "PartnerManagement";

    private DiagramExportParser() {
    }

    /**
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     no ZIP, the ZIP is corrupt, or the workflow entry is too large or not the expected XML
     */
    public static DiagramMetadata parse(NodeId server, byte[] body) {
        return parse(server, body, MAX_ENTRY_BYTES);
    }

    static DiagramMetadata parse(NodeId server, byte[] body, long maxEntryBytes) {
        Optional<byte[]> workflow = workflowEntry(server, body, maxEntryBytes);
        if (workflow.isEmpty()) {
            return DiagramMetadata.EMPTY;
        }
        Element root = InventoryXml.parse(server, workflow.get()).getDocumentElement();
        Optional<Element> head = Optional.of(root)
            .filter(element -> XmlSupport.localName(element).equals("IBISWorkflow"))
            .flatMap(element -> XmlSupport.firstChild(element, "Workflows"))
            .flatMap(element -> XmlSupport.firstChild(element, "WorkflowGroup"))
            .flatMap(element -> XmlSupport.firstChild(element, "Workflow"));
        if (head.isEmpty()) {
            throw InventoryXml.unexpected(server, "The workflow of the diagram export of "
                + server + " has no IBISWorkflow/Workflows/WorkflowGroup/Workflow");
        }
        Set<String> variables = new TreeSet<>();
        boolean partnerManagement = scan(root, variables);
        return new DiagramMetadata(
            InventoryXml.childText(head.get(), "IsActive").flatMap(DiagramExportParser::bool),
            InventoryXml.childText(head.get(), "CheckinComment"),
            InventoryXml.childText(head.get(), "UserOrUserGroupName"),
            List.copyOf(variables), partnerManagement);
    }

    /**
     * Adds the partner variables of every attribute value and text below {@code node} to
     * {@code variables}; whether an element below it is a {@code PartnerManagement} node.
     */
    private static boolean scan(Node node, Set<String> variables) {
        boolean partnerManagement = false;
        Deque<Node> open = new ArrayDeque<>(List.of(node));
        while (!open.isEmpty()) {
            Node next = open.pop();
            switch (next.getNodeType()) {
                case Node.ELEMENT_NODE -> {
                    NamedNodeMap attributes = next.getAttributes();
                    for (int i = 0; i < attributes.getLength(); i++) {
                        Node attribute = attributes.item(i);
                        partnerManagement |= XmlSupport.localName(attribute).equals("moduleType")
                            && attribute.getNodeValue().equals(PARTNER_MANAGEMENT);
                        variables(attribute.getNodeValue(), variables);
                    }
                    for (Node child = next.getLastChild(); child != null;
                        child = child.getPreviousSibling()) {
                        open.push(child);
                    }
                }
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    variables(next.getNodeValue(), variables);
                default -> {
                }
            }
        }
        return partnerManagement;
    }

    private static void variables(String text, Set<String> variables) {
        Matcher matcher = PARTNER_VARIABLE.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group());
        }
    }

    /** {@code true}/{@code false} (any case); anything else is absent. */
    public static Optional<Boolean> bool(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /**
     * The {@link #WORKFLOW_ENTRY} of the export ZIP {@code body} (at most {@link #MAX_ENTRY_BYTES},
     * in memory), or empty if the export has none; also read by
     * {@link V81PartnerManagementAdapter}.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} (with the server id) if the body is
     *     no ZIP, the ZIP is corrupt, or the workflow entry is too large
     */
    static Optional<byte[]> workflowEntry(NodeId server, byte[] body) {
        return workflowEntry(server, body, MAX_ENTRY_BYTES);
    }

    private static Optional<byte[]> workflowEntry(NodeId server, byte[] body, long maxEntryBytes) {
        if (!isZip(body)) {
            throw InventoryXml.unexpected(server, "The diagram export of " + server
                + " is not a ZIP archive");
        }
        return readEntry(server, body, maxEntryBytes);
    }

    private static boolean isZip(byte[] body) {
        if (body.length < ZIP_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
            if (body[i] != ZIP_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static Optional<byte[]> readEntry(NodeId server, byte[] body, long maxBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(WORKFLOW_ENTRY)) {
                    return Optional.of(bounded(server, zip, maxBytes));
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw InventoryXml.unexpected(server, "The diagram export of " + server
                + " is not a readable ZIP archive (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static byte[] bounded(NodeId server, ZipInputStream zip, long maxBytes)
        throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = zip.read(buffer)) >= 0) {
            total += n;
            if (total > maxBytes) {
                throw InventoryXml.unexpected(server, "The workflow of the diagram export of "
                    + server + " is too large (more than " + maxBytes + " bytes)");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
