package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.nodetools.NodeConfigRequest;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigResult;
import de.dadecker.inubit.mcp.domain.model.node.NodeConfigValue;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code get_node_config} (feature 024, contracts/mcp-tools-delta.md, research D-10): returns
 * allowlisted configuration values of the INUBIT node one server reaches, masked, through an
 * unsupported, undocumented INUBIT interface ({@code NodeConfigService}). Offered only for groups
 * with {@code unsupported.nodeConfigRead}; the input schema's {@code enum} is
 * {@link de.dadecker.inubit.mcp.domain.model.node.NodeConfigName} and the service repeats every
 * input check.
 */
public final class GetNodeConfigTool implements ToolHandler {

    static final String DESCRIPTION = "Returns configuration values of the INUBIT node one {node}"
        + " reaches, from a fixed list of values that matter for diagnosis and hold no secrets:"
        + " OperationMode, ClusterName, ZoneName, NodeName, LocalMaintenanceMode,"
        + " ActivateClusterManager, ClusterDebug, ContainerShutdownOnNodeFailure, RegisterMBeans,"
        + " RegisterInternalMBeans, RuntimeDataCacheXMXPercentage, socketTimeoutInMillisecs,"
        + " indexEnabled, iterationCount, DataSourceLocation, LogsDBDataSourceLocation,"
        + " RepositoryDataSourceLocation, ClusterBindAddress, noOfRetries, retryInterval. Without"
        + " names it returns all of them; per name value, type, display name, description,"
        + " category and whether it is editable or deprecated, or that the node has no such"
        + " value. Any other name is refused. UNSUPPORTED: it uses an undocumented internal"
        + " INUBIT interface without vendor support or stability guarantee, for diagnosis only;"
        + " it may stop working after any INUBIT update; INUBIT checks no right beyond the login;"
        + " available only for groups with unsupported.nodeConfigRead. Changes nothing on"
        + " INUBIT.";

    private final Function<NodeConfigRequest, NodeConfigResult> read;

    /** @param read the configuration read of the profile ({@code NodeConfigService::read}) */
    public GetNodeConfigTool(Function<NodeConfigRequest, NodeConfigResult> read) {
        this.read = Objects.requireNonNull(read, "read");
    }

    @Override
    public String name() {
        return "get_node_config";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read INUBIT node configuration values", true);
    }

    /**
     * The arguments are validated by the SDK against {@code get_node_config.input.json}; without
     * {@code names} the service gets an empty list (all names).
     */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return Result.of(read.apply(new NodeConfigRequest(args.string("target"),
            args.strings("names"))));
    }

    /** The JSON shape of contracts/mcp-tools-delta.md "Output". */
    record Result(NodeId node, String inubitNode, List<Value> values, List<String> warnings) {

        static Result of(NodeConfigResult result) {
            return new Result(result.node(), result.answeringNode(),
                result.values().stream().map(Value::of).toList(), result.warnings());
        }
    }

    /**
     * One requested name: {@code present} with the value and its attributes, {@code present:
     * false} for a value the node does not have, or only {@code error} when reading it failed;
     * {@code cut} only when the value was cut.
     */
    record Value(String name, Optional<Boolean> present, Optional<String> value,
        Optional<Boolean> cut, Optional<String> type, Optional<String> displayName,
        Optional<String> description, Optional<String> category, Optional<Boolean> editable,
        Optional<Boolean> deprecated, Optional<Failure> error) {

        static Value of(NodeConfigValue value) {
            String name = value.name().wireName();
            return switch (value) {
                case NodeConfigValue.Present present -> new Value(name, Optional.of(true),
                    Optional.of(present.value()),
                    present.cut() ? Optional.of(true) : Optional.empty(),
                    Optional.of(present.type()), Optional.of(present.displayName()),
                    Optional.of(present.description()), Optional.of(present.category()),
                    Optional.of(present.editable()), Optional.of(present.deprecated()),
                    Optional.empty());
                case NodeConfigValue.Absent absent -> new Value(name, Optional.of(false),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
                case NodeConfigValue.Failed failed -> new Value(name, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(new Failure(failed.code(), failed.message())));
            };
        }
    }

    /** Why one name could not be read. */
    record Failure(ErrorCode code, String message) {
    }
}
