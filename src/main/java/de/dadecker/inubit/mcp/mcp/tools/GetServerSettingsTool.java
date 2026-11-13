package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ServerSettingsService.NodeSettings;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.ConfigurationView;
import de.dadecker.inubit.mcp.domain.model.DbConnectionTolerance;
import de.dadecker.inubit.mcp.domain.model.LicenseFacts;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.NodeResult;
import de.dadecker.inubit.mcp.domain.model.PrivilegedWorkflows;
import de.dadecker.inubit.mcp.domain.model.ServerSettings;
import de.dadecker.inubit.mcp.domain.model.ServerUpdate;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * {@code get_server_settings} (feature 022, US1, contracts/mcp-tools-delta.md): the settings of
 * every server of a target ({@code ServerSettingsService}), one entry per server in config order
 * (AS5). A server with settings carries {@code sources}, {@code unavailable} and the version
 * gate's {@code warnings} (as {@code get_health}'s reports); a server that could not be read at
 * all carries only its {@code error}.
 */
public final class GetServerSettingsTool implements ToolHandler {

    static final String DESCRIPTION = "Read the settings of the INUBIT {nodes}: license (expiry"
        + " date, licensed threads, licensee), workflow threads, privileged workflows, DB"
        + " connection tolerance and the current time, and on request the configuration."
        + " `target` takes the id of one {node} (`<{group}>/<{node}>`) or of one {group} (one"
        + " entry per {node}). `parts` picks what to read (default: all but `configuration`)."
        + " Without `configurationSection` the configuration is listed by section names; with"
        + " it, that section's values are shown, secrets masked. Version, memory share, blocking"
        + " queue size and maintenance mode are always included, from the same source as"
        + " get_health. License and configuration need the INUBIT role \"System Administrator\";"
        + " a part that cannot be read is listed under `unavailable` with the reason, the other"
        + " parts are returned. Workflow threads and privileged workflows need the INUBIT root"
        + " user and are listed under `unavailable`. Reads only; changes nothing on INUBIT.";

    /** The read of {@code ServerSettingsService::read}. */
    @FunctionalInterface
    public interface Read {
        List<NodeResult<NodeSettings>> read(String target, Set<String> parts,
            Optional<String> section);
    }

    private final Read settings;
    private final Supplier<Optional<ServerUpdate>> serverUpdate;

    /** @param serverUpdate the last update check (feature 016); empty while none succeeded */
    public GetServerSettingsTool(Read settings, Supplier<Optional<ServerUpdate>> serverUpdate) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.serverUpdate = Objects.requireNonNull(serverUpdate, "serverUpdate");
    }

    @Override
    public String name() {
        return "get_server_settings";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read INUBIT settings", true);
    }

    /**
     * The arguments are validated by the SDK against {@code get_server_settings.input.json};
     * absent {@code parts} are left to the service's default.
     */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        return Result.of(settings.read(args.string("target"), Set.copyOf(args.strings("parts")),
            args.optionalString("configurationSection")), serverUpdate.get());
    }

    /** {@code {settings: [Entry], serverUpdate?}}. */
    record Result(List<Entry> settings, Optional<ServerUpdate> serverUpdate) {

        static Result of(List<NodeResult<NodeSettings>> results,
            Optional<ServerUpdate> serverUpdate) {
            return new Result(results.stream().map(Entry::of).toList(), serverUpdate);
        }
    }

    /**
     * The JSON shape of one server: the {@link ServerSettings} facts with {@code sources} sorted
     * by field, {@code unavailable} and {@code warnings}; or only {@code node} and {@code error}.
     */
    record Entry(NodeId node, Optional<LicenseFacts> license, Optional<Long> workflowThreads,
        Optional<PrivilegedWorkflows> privilegedWorkflows,
        Optional<DbConnectionTolerance> dbConnectionTolerance, Optional<Long> blockingQueueSize,
        Optional<String> version, Optional<Double> memoryUsedPercent,
        Optional<Boolean> maintenanceMode, Optional<String> serverTime,
        Optional<Configuration> configuration, Optional<SortedMap<String, Channel>> sources,
        Optional<List<UnavailablePart>> unavailable, Optional<List<String>> warnings,
        Optional<ToolError> error) {

        static Entry of(NodeResult<NodeSettings> result) {
            if (result.payload().isEmpty()) {
                return new Entry(result.node(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), result.error());
            }
            NodeSettings payload = result.payload().get();
            ServerSettings facts = payload.settings();
            return new Entry(facts.node(), facts.license(), facts.workflowThreads(),
                facts.privilegedWorkflows(), facts.dbConnectionTolerance(),
                facts.blockingQueueSize(), facts.version(), facts.memoryUsedPercent(),
                facts.maintenanceMode(), facts.serverTime(),
                facts.configuration().map(Configuration::of),
                Optional.of(new TreeMap<>(facts.sources())), Optional.of(facts.unavailable()),
                Optional.of(payload.warnings()), result.error());
        }
    }

    /**
     * {@code {sections, maskedCount}} without a section; otherwise {@code {section, values,
     * maskedCount, truncated}}.
     */
    record Configuration(Optional<List<String>> sections, Optional<String> section,
        Optional<List<Value>> values, int maskedCount, Optional<Boolean> truncated) {

        static Configuration of(ConfigurationView view) {
            if (view.section().isEmpty()) {
                return new Configuration(Optional.of(view.sections()), Optional.empty(),
                    Optional.empty(), view.maskedCount(), Optional.empty());
            }
            return new Configuration(Optional.empty(), view.section(),
                Optional.of(view.values().stream().map(Value::of).toList()), view.maskedCount(),
                Optional.of(view.truncated()));
        }
    }

    /** {@code {path, value}}, or {@code {path, masked: true}}. */
    record Value(String path, Optional<String> value, Optional<Boolean> masked) {

        static Value of(ConfigurationView.Leaf leaf) {
            return new Value(leaf.path(), leaf.value(),
                leaf.masked() ? Optional.of(true) : Optional.empty());
        }
    }
}
