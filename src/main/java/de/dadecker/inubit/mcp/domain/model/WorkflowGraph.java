package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The structure of one workflow file that the checks of {@code check_artifacts} need (research
 * D-13): its nodes with their ids, edges, properties and parent references, the declared
 * variables, every variable reference and every {@code inubitrepository:} reference (the
 * repository path, e.g. {@code Root/OWNERS/xsl/common.xsl}). Built by an
 * {@link de.dadecker.inubit.mcp.domain.port.ArtifactInspectorPort}; locations are element paths
 * such as {@code WorkflowModule[ModuleId=5]/Connection}.
 */
public record WorkflowGraph(List<Node> nodes, Set<String> variables,
    List<Reference> variableReferences, List<Reference> repositoryReferences) {

    public WorkflowGraph {
        nodes = List.copyOf(nodes);
        variables = Set.copyOf(variables);
        variableReferences = List.copyOf(variableReferences);
        repositoryReferences = List.copyOf(repositoryReferences);
    }

    /**
     * One {@code WorkflowModule}.
     *
     * @param moduleType       {@code technical}, {@code Comment}, {@code PartnerManagement}, …
     * @param properties       the node's own {@code Property} values by name (top level)
     * @param parentReferences ids named by {@code ParentModule}, {@code EndLoopId} and
     *                         {@code scopeChildId}
     */
    public record Node(String moduleId, String moduleName, String moduleType, List<Edge> edges,
        Map<String, String> properties, List<Reference> parentReferences) {

        public Node {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(moduleName, "moduleName");
            Objects.requireNonNull(moduleType, "moduleType");
            edges = List.copyOf(edges);
            properties = Map.copyOf(properties);
            parentReferences = List.copyOf(parentReferences);
        }

        /** {@code WorkflowModule[ModuleId=<id>]}. */
        public String location() {
            return "WorkflowModule[ModuleId=" + moduleId + "]";
        }
    }

    /** One {@code Connection}: the target id and, if present, its {@code ConnectionId}. */
    public record Edge(String target, Optional<String> connectionId) {

        public Edge {
            Objects.requireNonNull(target, "target");
            connectionId = connectionId == null ? Optional.empty() : connectionId;
        }
    }

    /** A referenced name or id and where it is referenced. */
    public record Reference(String value, String location) {

        public Reference {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(location, "location");
        }
    }
}
