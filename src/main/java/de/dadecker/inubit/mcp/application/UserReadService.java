package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.Capability;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.CoverageKey;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ListedUser;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.domain.port.UserDirectoryPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * The reads of {@code read_users} (feature 022, US2, research D-20): the views {@code users},
 * {@code user} and {@code logins} of one server. The views {@code messages} and {@code message}
 * wait for an L-17 recording (owner decision 2026-11-13).
 *
 * <ul>
 *   <li>Each view is admitted by {@link VersionGate#admitCall} with the entries of its
 *       {@link ActionSpec} ({@link #SPECS}) before anything is sent.
 *   <li>{@code users}: the logins of the REST list, sorted by login and paged ({@code offset}
 *       0–{@value #MAX_OFFSET}, {@code limit} 1–{@value #MAX_LIMIT}, default
 *       {@value #DEFAULT_LIMIT}); one StartCLI session reads the groups and roles of the page's
 *       logins. A {@link UserEntry} has no e-mail or other personal field (FR-011, AS5).
 *   <li>{@code user}: the login is checked against the REST list before a session starts; a login
 *       that is not listed gives {@code NOT_FOUND} with the listing as next step (AS3). Only this
 *       view returns the e-mail and the other personal fields of {@code finger} (FR-011, AS2).
 *   <li>A {@code user} or {@code finger} answer that could not be read costs only that user's
 *       group or roles; a failed session costs the groups and roles of every user of the call, and
 *       the logins of the list are still returned (FR-013).
 *   <li>{@code logins}: the {@code Toolset} rows of {@code --who}, sorted by login and paged as
 *       above, without a login time on 8.1 (research "Live recording T028a"). The page total is
 *       the number of these rows; {@code --nwho} counts every connected system, databases
 *       included, so it is not read and never reported as a number of users.
 *   <li>{@code sources} names the channel of each fact that was read (FR-009): {@code logins} REST;
 *       {@code groups}, {@code roles}, {@code email}, {@code personal} and {@code loggedIn} CLI.
 *       {@code accountState} is not in it: neither {@code user} nor {@code finger} shows one on 8.1
 *       (research "Live recording T028", L-13).
 * </ul>
 */
public final class UserReadService {

    public static final String TOOL = "read_users";

    public static final int MAX_OFFSET = 9999;
    /** At most one StartCLI session of 25 logins per page (research D-20). */
    public static final int MAX_LIMIT = 25;
    public static final int DEFAULT_LIMIT = 25;

    private static final CoverageKey USER_LIST = new CoverageKey(Channel.REST, "GET /user/users");
    private static final CoverageKey USER_COMMAND = new CoverageKey(Channel.CLI, "user");
    private static final CoverageKey FINGER = new CoverageKey(Channel.CLI, "finger");
    private static final CoverageKey WHO = new CoverageKey(Channel.CLI, "-who");

    public static final ActionSpec USERS = spec("users", Set.of(USER_LIST, USER_COMMAND, FINGER));
    public static final ActionSpec USER = spec("user", Set.of(USER_LIST, USER_COMMAND, FINGER));
    public static final ActionSpec LOGINS = spec("logins", Set.of(WHO));

    /** The actions of the tool, one per view (research D-2 action key {@code view}). */
    public static final List<ActionSpec> SPECS = List.of(USERS, USER, LOGINS);

    /**
     * What one view read on one server.
     *
     * @param sources  the channel of each fact read (FR-009)
     * @param warnings the version gate's warnings and notes for the server
     */
    public record ViewResult<T>(NodeId node, T value, Map<String, Channel> sources,
        List<String> warnings) {

        public ViewResult {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(value, "value");
            sources = Map.copyOf(sources);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * One user of the {@code users} view: its login from the REST list and its group and roles
     * from the session, each read or unavailable with the reason (FR-013). It has no personal
     * field by construction (FR-011, AS5).
     *
     * @param group the user's group ({@code user <login>})
     * @param roles the user's roles ({@code finger <login>})
     */
    public record UserEntry(String login, Block<String> group, Block<List<String>> roles) {

        public UserEntry {
            Objects.requireNonNull(login, "login");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(roles, "roles");
        }

        static UserEntry of(UserFacts facts) {
            return new UserEntry(facts.login(), map(facts.groups(), UserGroups::group),
                map(facts.finger(), Finger::roles));
        }
    }

    private final TargetResolver targets;
    private final VersionGate gate;
    private final Function<NodeId, UserDirectoryPort> directories;

    /** @param directories the user-directory port of a server */
    public UserReadService(TargetResolver targets, VersionGate gate,
        Function<NodeId, UserDirectoryPort> directories) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.directories = Objects.requireNonNull(directories, "directories");
    }

    /**
     * A page of the users of {@code target}, sorted by login (AS1, AS5).
     *
     * @throws ToolErrorException {@code INVALID_INPUT} (offset, limit, a stage or unknown target
     *     syntax), {@code TARGET_UNKNOWN}, the gate's refusal, or the error of the REST list
     */
    public ViewResult<Page<UserEntry>> users(String target, int offset, OptionalInt limit) {
        int size = checkPage(offset, limit);
        NodeId node = targets.resolveSingleServer(target);
        List<String> warnings = admit(node, USERS);
        UserDirectoryPort port = directories.apply(node);
        List<String> sorted = port.listUsers().stream().map(ListedUser::login).sorted().toList();
        Page<String> logins = page(sorted, offset, size);
        List<UserEntry> entries = session(port, logins.items()).stream().map(UserEntry::of)
            .toList();
        Map<String, Channel> sources = new HashMap<>();
        sources.put("logins", Channel.REST);
        if (entries.stream().anyMatch(entry -> entry.group() instanceof Block.Parsed)) {
            sources.put("groups", Channel.CLI);
        }
        if (entries.stream().anyMatch(entry -> entry.roles() instanceof Block.Parsed)) {
            sources.put("roles", Channel.CLI);
        }
        return new ViewResult<>(node, new Page<>(entries, logins.offset(), logins.limit(),
            logins.total(), false, false, logins.nextOffset()), sources, warnings);
    }

    /**
     * The user {@code login} of {@code target} with its personal fields (AS2).
     *
     * @throws ToolErrorException {@code NOT_FOUND} if the REST list has no such login (AS3);
     *     otherwise as {@link #users}
     */
    public ViewResult<UserFacts> user(String target, String login) {
        Objects.requireNonNull(login, "login");
        NodeId node = targets.resolveSingleServer(target);
        List<String> warnings = admit(node, USER);
        UserDirectoryPort port = directories.apply(node);
        if (port.listUsers().stream().noneMatch(listed -> listed.login().equals(login))) {
            throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "No user " + Names.quote(login) + " on " + node,
                "The login is misspelled, or no user of " + node + " has it",
                "List the users with view users and use a login of the list").withNode(node));
        }
        UserFacts user = session(port, List.of(login)).get(0);
        Map<String, Channel> sources = new HashMap<>();
        sources.put("logins", Channel.REST);
        if (user.groups() instanceof Block.Parsed) {
            sources.put("groups", Channel.CLI);
        }
        if (user.finger() instanceof Block.Parsed<Finger> finger) {
            sources.put("roles", Channel.CLI);
            if (finger.value().email().isPresent()) {
                sources.put("email", Channel.CLI);
            }
            if (!finger.value().personal().isEmpty()) {
                sources.put("personal", Channel.CLI);
            }
        }
        return new ViewResult<>(node, user, sources, warnings);
    }

    /**
     * A page of the logged-in users of {@code target}, sorted by login (AS4).
     *
     * @throws ToolErrorException as {@link #users}, or the error of {@code --who}
     */
    public ViewResult<Page<LoginEntry>> logins(String target, int offset, OptionalInt limit) {
        int size = checkPage(offset, limit);
        NodeId node = targets.resolveSingleServer(target);
        List<String> warnings = admit(node, LOGINS);
        List<LoginEntry> sorted = directories.apply(node).loggedIn().stream()
            .sorted(Comparator.comparing(LoginEntry::login)).toList();
        return new ViewResult<>(node, page(sorted, offset, size), Map.of("loggedIn", Channel.CLI),
            warnings);
    }

    private List<String> admit(NodeId node, ActionSpec spec) {
        VersionGate.Admission admission = gate.admitCall(TOOL, node, spec, spec.callEntries());
        List<String> warnings = new ArrayList<>(admission.warnings());
        warnings.addAll(admission.notes());
        return warnings;
    }

    /** The session's facts of {@code logins}; a failed session makes every block unavailable. */
    private static List<UserFacts> session(UserDirectoryPort port, List<String> logins) {
        try {
            return port.readUsers(logins);
        } catch (ToolErrorException e) {
            return logins.stream().map(login -> new UserFacts(login,
                new Block.Unavailable<UserGroups>(e.error()),
                new Block.Unavailable<Finger>(e.error()))).toList();
        }
    }

    /** The page size; {@code INVALID_INPUT} for an offset or limit outside its range. */
    private static int checkPage(int offset, OptionalInt limit) {
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "Invalid offset " + offset + ": expected 0–" + MAX_OFFSET,
                "offset is the position of the first entry of the page",
                "Use an offset from 0 to " + MAX_OFFSET + ", such as the nextOffset of the last"
                    + " page"));
        }
        int size = limit.orElse(DEFAULT_LIMIT);
        if (size < 1 || size > MAX_LIMIT) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "Invalid limit " + size + ": expected 1–" + MAX_LIMIT,
                "limit is the number of entries of one page",
                "Use a limit from 1 to " + MAX_LIMIT + ", or leave it out for " + DEFAULT_LIMIT));
        }
        return size;
    }

    private static <T> Page<T> page(List<T> sorted, int offset, int limit) {
        int from = Math.min(offset, sorted.size());
        int to = Math.min(from + limit, sorted.size());
        return new Page<>(sorted.subList(from, to), offset, limit, OptionalLong.of(sorted.size()),
            false, false, to < sorted.size() ? OptionalInt.of(to) : OptionalInt.empty());
    }

    private static <T, R> Block<R> map(Block<T> block, Function<T, R> value) {
        return switch (block) {
            case Block.Parsed<T> parsed -> new Block.Parsed<>(value.apply(parsed.value()));
            case Block.Unavailable<T> unavailable -> new Block.Unavailable<>(unavailable.error());
        };
    }

    private static ActionSpec spec(String view, Set<CoverageKey> entries) {
        return new ActionSpec(TOOL, view, Capability.USER_DIRECTORY, ActionSpec.Area.READ,
            ActionSpec.Kind.READ, entries, input -> entries, Set.of(), false, Optional.empty(),
            ActionSpec.RestartNotice.NONE);
    }
}
