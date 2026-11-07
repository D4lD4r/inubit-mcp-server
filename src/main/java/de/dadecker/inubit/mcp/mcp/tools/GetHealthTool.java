package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.HealthService;
import de.dadecker.inubit.mcp.domain.model.HealthReport;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code get_health} (contracts/mcp-tools.md §2, US1): one {@link HealthReport} per resolved
 * server, in config order. A server that cannot be reached is a report with
 * {@code reachable=false} and {@code error}; only an invalid or unknown target fails the call.
 */
public final class GetHealthTool implements ToolHandler {

    static final String DESCRIPTION = "Check whether the INUBIT {nodes} are reachable and ready:"
        + " maintenance mode, version, memory, threads, and blocking queue. `target` takes the id"
        + " of one {node} (`<{group}>/<{node}>`) or of one {group} (all its {nodes}); omit it for"
        + " all {nodes}. `serverUpdate` tells whether a newer release of this MCP server exists.";

    /**
     * The result: {@code {reports: HealthReport[], serverUpdate?: ServerUpdate}}; feature 016:
     * {@code serverUpdate} is the last update check, independent of {@code target}.
     */
    record Result(List<HealthReport> reports, Optional<ServerUpdate> serverUpdate) {
    }

    private final HealthService health;
    private final Supplier<Optional<ServerUpdate>> serverUpdate;

    public GetHealthTool(HealthService health) {
        this(health, Optional::empty);
    }

    /** @param serverUpdate the last update check (feature 016); empty while none succeeded */
    public GetHealthTool(HealthService health, Supplier<Optional<ServerUpdate>> serverUpdate) {
        this.health = Objects.requireNonNull(health, "health");
        this.serverUpdate = Objects.requireNonNull(serverUpdate, "serverUpdate");
    }

    @Override
    public String name() {
        return "get_health";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Check INUBIT health", true);
    }

    /** The arguments are validated by the SDK against {@code get_health.input.json}. */
    @Override
    public Object handle(Map<String, Object> arguments) {
        Optional<String> target = Optional.ofNullable(arguments.get("target"))
            .map(String::valueOf);
        boolean includeSystemInfo = Boolean.TRUE.equals(arguments.get("includeSystemInfo"));
        return new Result(health.check(target, includeSystemInfo), serverUpdate.get());
    }
}
