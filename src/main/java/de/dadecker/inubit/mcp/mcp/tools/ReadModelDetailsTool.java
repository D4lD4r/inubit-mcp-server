package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.ModelDetailService;
import de.dadecker.inubit.mcp.application.ModelDetailService.Libraries;
import de.dadecker.inubit.mcp.application.ModelDetailService.Read;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail;
import de.dadecker.inubit.mcp.domain.model.ModuleDetail.Connection;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code read_model_details} (feature 022, US3, contracts/mcp-tools-delta.md): one module of a
 * diagram or the uploaded libraries of one server ({@link ModelDetailService}). The actions
 * {@code metadata} and {@code checkIsConnector} and a {@code moduleName} input are not offered
 * (owner decision 2026-11-14), so the input schema has none of them.
 *
 * <p>A masked property is shown as {@code {name, type, masked: true}}; a shown value carries
 * {@code truncated: true} only when it was cut.
 */
public final class ReadModelDetailsTool implements ToolHandler {

    static final String DESCRIPTION = "Read the model details of one INUBIT {node}. `target`"
        + " takes the id of exactly one {node} (`<{group}>/<{node}>`). `action` picks what to"
        + " read: `module` reads the one module `moduleId` (the module's numeric id) of the"
        + " diagram `diagram` over REST, with its type, parent, outgoing connections and"
        + " properties in INUBIT's order; secret values are masked: a property whose name is a"
        + " secret name, whose type is `Password` or whose value is key material is shown as"
        + " `masked` and counted in `maskedCount`, and a credential inside a value (such as the"
        + " password of a database URL) is replaced by a placeholder. An unknown module id gives"
        + " NOT_FOUND on INUBIT 8.1 and UNEXPECTED_RESPONSE with a hint on 9.0. `libraries`"
        + " lists the uploaded library files with StartCLI, `libraryType`"
        + " `driver` or `plugin`, or both without it (one StartCLI run per type, drivers first),"
        + " paged with `offset` and `limit` (at most 100); `nextOffset` gives the next page. Until"
        + " a listed library is recorded, each line of the answer is read as one file name and"
        + " `rowFormatVerified` is false. Reads only; changes nothing on INUBIT.";

    private final ModelDetailService details;

    public ReadModelDetailsTool(ModelDetailService details) {
        this.details = Objects.requireNonNull(details, "details");
    }

    @Override
    public String name() {
        return ModelDetailService.TOOL;
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read INUBIT model details", true);
    }

    /**
     * The arguments are validated by the SDK against {@code read_model_details.input.json} (one
     * server, {@code diagram} and {@code moduleId} only and always for {@code module}); an absent
     * {@code limit} is left to the service's default.
     */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        String target = args.string("target");
        String action = args.string("action");
        return switch (action) {
            case "module" -> Result.module(details.module(target, args.string("diagram"),
                args.string("moduleId")));
            case "libraries" -> Result.libraries(details.libraries(target,
                args.optionalString("libraryType"), args.integer("offset", 0),
                args.optionalInteger("limit").map(OptionalInt::of).orElse(OptionalInt.empty())));
            default -> throw new IllegalStateException("action outside the input schema: "
                + action);
        };
    }

    /**
     * {@code {node, action, source, module | page + rowFormatVerified, warnings}};
     * {@code warnings} the version gate's warnings and notes.
     */
    record Result(NodeId node, String action, Channel source, Optional<Module> module,
        Optional<Page<LibraryEntry>> page, Optional<Boolean> rowFormatVerified,
        List<String> warnings) {

        static Result module(Read<ModuleDetail> read) {
            return new Result(read.node(), "module", read.source(),
                Optional.of(Module.of(read.value())), Optional.empty(), Optional.empty(),
                read.warnings());
        }

        static Result libraries(Read<Libraries> read) {
            return new Result(read.node(), "libraries", read.source(), Optional.empty(),
                Optional.of(read.value().page()), Optional.of(read.value().rowFormatVerified()),
                read.warnings());
        }
    }

    /** The module as data-model.md "ModuleDetail" shows it. */
    record Module(String diagram, String moduleId, String name, String type,
        Optional<String> parentId, List<Connection> connections, List<Property> properties,
        int maskedCount) {

        static Module of(ModuleDetail detail) {
            return new Module(detail.diagram(), detail.moduleId(), detail.name(), detail.type(),
                detail.parentId(), detail.connections(),
                detail.properties().stream().map(Property::of).toList(), detail.maskedCount());
        }
    }

    /** {@code {name, type, value, truncated?}}, or {@code {name, type, masked: true}}. */
    record Property(String name, String type, Optional<String> value,
        Optional<Boolean> truncated, Optional<Boolean> masked) {

        static Property of(ModuleDetail.Property property) {
            return new Property(property.name(), property.type(), property.value(),
                property.truncated() ? Optional.of(true) : Optional.empty(),
                property.masked() ? Optional.of(true) : Optional.empty());
        }
    }
}
