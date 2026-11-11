package de.dadecker.inubit.mcp.adapter.rest.v81;

import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader;
import de.dadecker.inubit.mcp.adapter.archive.v81.PartnerManagementReader.GroupedWorkflow;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree;
import de.dadecker.inubit.mcp.adapter.archive.v81.XmlTree.Element;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient;
import de.dadecker.inubit.mcp.adapter.rest.InubitHttpClient.MediaRange;
import de.dadecker.inubit.mcp.adapter.rest.RestResponse;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.partner.DiagramRef;
import de.dadecker.inubit.mcp.domain.model.partner.SystemDiagramPartners;
import de.dadecker.inubit.mcp.domain.port.PartnerManagementPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The 8.1 {@link PartnerManagementPort} (feature 018, research D-1, D-4): one
 * {@code GET /ibis/rest/model/export/<name>} per system diagram (as
 * {@code V81InventoryAdapter.diagramMetadata}). The ZIP is held in memory only (the client bounds
 * it by {@link InubitHttpClient#MAX_BODY_BYTES}, {@link DiagramExportParser#workflowEntry} its
 * {@code workflow/workflow.xml}), parsed by {@link XmlTree} and read and masked by
 * {@link PartnerManagementReader}; nothing is written to disk.
 *
 * <p>The export selects by name only, so the archive must hold exactly one workflow of that name,
 * and its {@code WorkflowGroupName} and {@code UserOrUserGroupName} must equal the requested
 * diagram group and owner; otherwise the diagram gives no data and is reported as unreadable, the
 * others are still read. Name, group and owner are compared exactly (case-sensitive), so the owner
 * must be passed as {@code listDiagrams} returned it. A diagram whose export answers HTTP 404
 * (deleted or renamed since it was listed) is reported as unreadable with {@code NOT_FOUND} as
 * well. An archive without a readable diagram (no or a malformed {@code workflow.xml}, a Partner
 * Management the reader cannot read, such as a communication cloud without {@code ModuleName}) is
 * reported as unreadable with {@code UNEXPECTED_RESPONSE} naming the part, never its content, and
 * the other diagrams are still read (live check T034). An answer that is not a ZIP archive is the
 * server's {@code UNEXPECTED_RESPONSE}; other request failures come from
 * {@link InubitHttpClient}, without the excerpt of the answer (T030, FR-008: the answer to a
 * Partner Management export may quote partner data). All errors carry the server id.
 */
public final class V81PartnerManagementAdapter implements PartnerManagementPort {

    static final String NOT_UNIQUE = "the export of this name returned a diagram of another group"
        + " or owner; the name is not unique";
    static final String NOT_SINGLE = "the export of this name holds several diagrams of this name"
        + " in this group; the name is not unique";
    /** The next step of an unexpected answer whose excerpt was dropped. */
    static final String NO_EXCERPT_NEXT_STEP = "Check the INUBIT system log for the time of the"
        + " call (the answer to a Partner Management export is not quoted)";
    static final String NOT_RETURNED = "the export of this name did not return the requested"
        + " diagram; the name is not unique or not found";

    private static final String EXPORT = "/ibis/rest/model/export/";

    private final NodeId server;
    private final InubitHttpClient client;

    public V81PartnerManagementAdapter(NodeId server, InubitHttpClient client) {
        this.server = Objects.requireNonNull(server, "server");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Result read(String owner, List<DiagramRef> systemDiagrams) {
        Objects.requireNonNull(owner, "owner");
        List<SystemDiagramPartners> diagrams = new ArrayList<>();
        List<UnreadableDiagram> unreadable = new ArrayList<>();
        Map<DiagramRef, List<String>> warnings = new HashMap<>();
        for (DiagramRef diagram : systemDiagrams) {
            RestResponse export = export(diagram);
            if (export.status() == 404) {
                unreadable.add(new UnreadableDiagram(diagram, ErrorCode.NOT_FOUND, "The export of "
                    + where(diagram) + " answered HTTP 404; the system diagram no longer exists"
                    + " under this name (deleted or renamed since it was listed)"));
                continue;
            }
            try {
                Element root = workflowRoot(diagram, export.body());
                Optional<String> mismatch = mismatch(root, diagram, owner);
                if (mismatch.isPresent()) {
                    throw new UnreadableExport(mismatch.get());
                }
                PartnerManagementReader.Result read = partners(diagram, root);
                diagrams.add(read.partners());
                if (!read.warnings().isEmpty()) {
                    warnings.put(diagram, read.warnings());
                }
            } catch (UnreadableExport e) {
                unreadable.add(new UnreadableDiagram(diagram, ErrorCode.UNEXPECTED_RESPONSE,
                    e.getMessage()));
            }
        }
        return new Result(diagrams, unreadable, warnings);
    }

    /** The export of {@code diagram}; a failure keeps its kind but loses the excerpt. */
    private RestResponse export(DiagramRef diagram) {
        try {
            return client.get(EXPORT + InubitHttpClient.encodePathSegment(diagram.name()),
                Map.of(), MediaRange.ANY, 404);
        } catch (ToolErrorException e) {
            ToolError error = e.error();
            if (error.excerpt().isEmpty()) {
                throw e;
            }
            // only an unexpected answer's next step points to the excerpt
            ToolError withoutExcerpt = ToolError.of(error.code(), error.message(),
                error.likelyCause(), error.code() == ErrorCode.UNEXPECTED_RESPONSE
                    ? NO_EXCERPT_NEXT_STEP : error.nextStep());
            throw new ToolErrorException(error.node().map(withoutExcerpt::withNode)
                .orElse(withoutExcerpt));
        }
    }

    /**
     * The root of the {@code workflow.xml} of {@code body}.
     *
     * @throws ToolErrorException {@code UNEXPECTED_RESPONSE} of the server if {@code body} is not
     *     a ZIP archive: the server did not answer with an export at all
     * @throws UnreadableExport if the archive has no {@code workflow.xml} or it is not well-formed
     */
    private Element workflowRoot(DiagramRef diagram, byte[] body) {
        byte[] xml = DiagramExportParser.workflowEntry(server, body).orElseThrow(() ->
            new UnreadableExport("The export of " + where(diagram) + " has no "
                + DiagramExportParser.WORKFLOW_ENTRY));
        try {
            return XmlTree.parse(xml).root();
        } catch (IllegalArgumentException e) {
            throw new UnreadableExport("The " + DiagramExportParser.WORKFLOW_ENTRY
                + " of the export of " + where(diagram) + " is not well-formed XML");
        }
    }

    /**
     * Why the export does not hold the requested diagram (D-4 collision check): no workflow has
     * its name, or one lies in another group or belongs to another owner, or several lie in the
     * requested group with the requested owner. Empty if the export holds exactly the requested
     * one.
     */
    private static Optional<String> mismatch(Element root, DiagramRef diagram, String owner) {
        List<GroupedWorkflow> named = PartnerManagementReader.workflowsNamed(root, diagram.name());
        if (named.isEmpty()) {
            return Optional.of(NOT_RETURNED);
        }
        boolean allRequested = named.stream().allMatch(workflow ->
            workflow.groupName().equals(diagram.diagramGroup())
                && workflow.workflow().childText("UserOrUserGroupName").equals(owner));
        if (!allRequested) {
            return Optional.of(NOT_UNIQUE);
        }
        return named.size() == 1 ? Optional.empty() : Optional.of(NOT_SINGLE);
    }

    /** The reader's messages name elements and the diagram, never a value. */
    private PartnerManagementReader.Result partners(DiagramRef diagram, Element root) {
        try {
            return PartnerManagementReader.read(diagram, root);
        } catch (IllegalArgumentException e) {
            throw new UnreadableExport("The Partner Management in the "
                + DiagramExportParser.WORKFLOW_ENTRY + " of the export of " + where(diagram)
                + " cannot be read: " + e.getMessage());
        }
    }

    /**
     * The export of one diagram holds no readable diagram; its message names the part, never a
     * value, and becomes the diagram's {@link UnreadableDiagram} entry.
     */
    private static final class UnreadableExport extends RuntimeException {

        UnreadableExport(String message) {
            super(message, null, false, false);
        }
    }

    private String where(DiagramRef diagram) {
        return diagram.diagramGroup() + "/" + diagram.name() + " on " + server;
    }

    @Override
    public String toString() {
        return "V81PartnerManagementAdapter[" + server + "]";
    }
}
