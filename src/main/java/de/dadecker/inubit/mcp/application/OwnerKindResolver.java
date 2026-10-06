package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.OwnerKind;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Whether an owner is a user or a user group (feature 004, research D-21, D-25, FR-014), from
 * positive evidence only:
 *
 * <ol>
 *   <li>the profile's {@code owners.<name>} wins ({@code USER} or {@code USER_GROUP}), without
 *       asking INUBIT;
 *   <li>otherwise a name in the node's user list ({@link UserDirectoryPort}) is a {@code USER};
 *   <li>anything else is {@code PRECONDITION_FAILED}: the list holds users only, so a missing
 *       name may be a user group or a typo — the profile must say which. A failing lookup is
 *       {@code PRECONDITION_FAILED} as well, naming the cause. Nothing is sent in either case.
 * </ol>
 *
 * <p>Writes for user-group owners are refused by the single guard {@link #requireVerified}
 * until a live probe of {@code --importUserGroup} has been approved and recorded; lifting the
 * refusal is a change of that one method.
 */
public final class OwnerKindResolver {

    private final Map<String, OwnerKind> overrides;
    private final Function<NodeId, UserDirectoryPort> directories;

    /**
     * @param overrides   the profile's {@code owners} map
     * @param directories the user directory of a node
     */
    public OwnerKindResolver(Map<String, OwnerKind> overrides,
        Function<NodeId, UserDirectoryPort> directories) {
        this.overrides = Map.copyOf(overrides);
        this.directories = Objects.requireNonNull(directories, "directories");
    }

    /**
     * The kind of {@code owner} on {@code node}.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED} if it cannot be determined
     */
    public OwnerKind resolve(NodeId node, String owner) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(owner, "owner");
        OwnerKind configured = overrides.get(owner);
        if (configured != null) {
            return configured;
        }
        Set<String> users;
        try {
            users = directories.apply(node).users();
        } catch (ToolErrorException e) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "The kind of the owner " + owner + " cannot be determined: the user list of "
                    + node + " could not be read; nothing was sent",
                e.error().code() + ": " + e.error().message(),
                "Fix the cause, or set owners." + owner + ": USER or USER_GROUP in the profile"
                    + " and restart the MCP client").withNode(node));
        }
        if (users.contains(owner)) {
            return OwnerKind.USER;
        }
        throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
            "The kind of the owner " + owner + " cannot be determined on " + node
                + "; nothing was sent",
            "The owner is not in INUBIT's user list: it is a user group, or the name is wrong"
                + " (INUBIT lists users only)",
            "Check the name; for a user group set owners." + owner + ": USER_GROUP in the"
                + " profile and restart the MCP client").withNode(node));
    }

    /**
     * {@link #resolve}, then {@link #requireVerified}: the kind of an owner a development tool
     * may write for.
     *
     * @throws ToolErrorException {@code PRECONDITION_FAILED}
     */
    public OwnerKind admitForWrite(NodeId node, String owner) {
        return requireVerified(node, owner, resolve(node, owner));
    }

    /**
     * The single guard for user-group owners (research D-25): StartCLI imports for them
     * ({@code --importUserGroup}) and tags have not been probed yet.
     *
     * @return {@code kind} if writes for it are allowed
     * @throws ToolErrorException {@code PRECONDITION_FAILED} for {@code USER_GROUP}
     */
    public static OwnerKind requireVerified(NodeId node, String owner, OwnerKind kind) {
        if (kind == OwnerKind.USER_GROUP) {
            throw new ToolErrorException(ToolError.of(ErrorCode.PRECONDITION_FAILED,
                "Writes for the owner " + owner + " are refused: imports and tags for user-group"
                    + " owners are not yet verified; nothing was sent",
                "This version writes only for user owners (personal diagram groups) until"
                    + " INUBIT's behaviour for user groups has been probed on a development stage",
                "Make the change in the INUBIT Workbench, or work on a diagram group of a user"
                    + " owner").withNode(node));
        }
        return kind;
    }
}
