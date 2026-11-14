package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.UserReadService;
import de.dadecker.inubit.mcp.application.UserReadService.UserEntry;
import de.dadecker.inubit.mcp.application.UserReadService.ViewResult;
import de.dadecker.inubit.mcp.domain.model.Channel;
import de.dadecker.inubit.mcp.domain.model.LoginEntry;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.PersonalField;
import de.dadecker.inubit.mcp.domain.model.UnavailablePart;
import de.dadecker.inubit.mcp.domain.model.UserFacts;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Block;
import de.dadecker.inubit.mcp.domain.model.UserFacts.Finger;
import de.dadecker.inubit.mcp.domain.model.UserFacts.UserGroups;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@code read_users} (feature 022, US2, contracts/mcp-tools-delta.md): the views {@code users},
 * {@code user} and {@code logins} of one server ({@link UserReadService}). The message views wait
 * for an L-17 recording (owner decision 2026-11-13), so the input schema offers none.
 *
 * <p>A user is shown flat ({@code user.login}, not {@code user.summary.login}). A listed user
 * whose group or roles could not be read stays in the listing with what was read; the missing
 * part is listed under its {@code unavailable} with the reason (FR-013). In the view {@code user}
 * an unreadable {@code finger} answer costs the roles, the e-mail and the personal fields.
 */
public final class ReadUsersTool implements ToolHandler {

    static final String DESCRIPTION = "Read the users of one INUBIT {node}. `target` takes the id"
        + " of exactly one {node} (`<{group}>/<{node}>`). `view` picks what to read: `users` lists"
        + " the users by login, each with its user group, additional user groups and roles,"
        + " without e-mail or other personal fields; `user` shows the one user named in `login`"
        + " with its user group, the parent of that user group, additional user groups, roles,"
        + " e-mail and the other personal fields INUBIT shows (e-mail and personal fields come only"
        + " for one named user); `logins` lists the users logged in with a client (the `Toolset`"
        + " connections of StartCLI `--who`, this call's own connection included), without a login"
        + " time on INUBIT 8.1; on INUBIT 9.0 `--who` lists no client connection, so `logins` is"
        + " refused there. `users` and `logins` are sorted by login and paged with `offset` and"
        + " `limit` (at most 25); `nextOffset` gives the next page. The logins come from the"
        + " INUBIT user list; the user group, roles, e-mail and personal fields are read with"
        + " StartCLI. A part that cannot be read is"
        + " listed under the user's `unavailable` with the reason, the other users and parts are"
        + " returned. An unknown login gives NOT_FOUND: list the users first. The messages of the"
        + " INUBIT message box are not offered yet. Reads only; changes nothing on INUBIT.";

    private final UserReadService users;

    public ReadUsersTool(UserReadService users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    @Override
    public String name() {
        return UserReadService.TOOL;
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Read INUBIT users", true);
    }

    /**
     * The arguments are validated by the SDK against {@code read_users.input.json} (one server,
     * {@code login} only and always for {@code user}); an absent {@code limit} is left to the
     * service's default.
     */
    @Override
    public Object handle(Map<String, Object> arguments) {
        ToolArguments args = new ToolArguments(arguments);
        String target = args.string("target");
        String view = args.string("view");
        int offset = args.integer("offset", 0);
        OptionalInt limit = args.optionalInteger("limit").map(OptionalInt::of)
            .orElse(OptionalInt.empty());
        return switch (view) {
            case "users" -> Result.users(users.users(target, offset, limit));
            case "user" -> Result.user(users.user(target, args.string("login")));
            case "logins" -> Result.logins(users.logins(target, offset, limit));
            default -> throw new IllegalStateException("view outside the input schema: " + view);
        };
    }

    /**
     * {@code {node, view, sources, page | user, warnings}}: {@code sources} sorted by fact,
     * {@code warnings} the version gate's warnings and notes.
     */
    record Result(NodeId node, String view, SortedMap<String, Channel> sources,
        Optional<Page<?>> page, Optional<Detail> user, List<String> warnings) {

        static Result users(ViewResult<Page<UserEntry>> result) {
            Page<UserEntry> page = result.value();
            Page<Summary> summaries = new Page<>(page.items().stream().map(Summary::of).toList(),
                page.offset(), page.limit(), page.total(), page.totalIsLowerBound(),
                page.truncated(), page.nextOffset());
            return new Result(result.node(), "users", new TreeMap<>(result.sources()),
                Optional.of(summaries), Optional.empty(), result.warnings());
        }

        static Result user(ViewResult<UserFacts> result) {
            return new Result(result.node(), "user", new TreeMap<>(result.sources()),
                Optional.empty(), Optional.of(Detail.of(result.value())), result.warnings());
        }

        static Result logins(ViewResult<Page<LoginEntry>> result) {
            return new Result(result.node(), "logins", new TreeMap<>(result.sources()),
                Optional.of(result.value()), Optional.empty(), result.warnings());
        }
    }

    /** One user of the view {@code users}: no e-mail or other personal field (FR-011, AS5). */
    record Summary(String login, Optional<String> group, Optional<List<String>> additionalGroups,
        Optional<List<String>> roles, List<UnavailablePart> unavailable) {

        static Summary of(UserEntry entry) {
            List<UnavailablePart> unavailable = new ArrayList<>();
            Optional<UserGroups> groups = read(entry.groups(), List.of(UnavailablePart.GROUPS),
                unavailable);
            Optional<List<String>> roles = read(entry.roles(), List.of(UnavailablePart.ROLES),
                unavailable);
            return new Summary(entry.login(), groups.map(UserGroups::group),
                additionalOf(groups), roles, unavailable);
        }
    }

    /** The one user of the view {@code user}, with its personal fields (FR-011, AS2). */
    record Detail(String login, Optional<String> group, Optional<String> superGroup,
        Optional<List<String>> additionalGroups, Optional<List<String>> roles,
        Optional<String> email, Optional<List<Personal>> personal,
        List<UnavailablePart> unavailable) {

        static Detail of(UserFacts facts) {
            List<UnavailablePart> unavailable = new ArrayList<>();
            Optional<UserGroups> groups = read(facts.groups(), List.of(UnavailablePart.GROUPS),
                unavailable);
            Optional<Finger> finger = read(facts.finger(), List.of(UnavailablePart.ROLES,
                UnavailablePart.EMAIL, UnavailablePart.PERSONAL), unavailable);
            return new Detail(facts.login(), groups.map(UserGroups::group),
                groups.map(UserGroups::superGroup), additionalOf(groups),
                finger.map(Finger::roles),
                finger.flatMap(Finger::email),
                finger.map(read -> read.personal().stream().map(Personal::of).toList()),
                unavailable);
        }
    }

    /** {@code {name, value}}, or {@code {name, masked: true}}. */
    record Personal(String name, Optional<String> value, Optional<Boolean> masked) {

        static Personal of(PersonalField field) {
            return new Personal(field.name(), field.value(),
                field.masked() ? Optional.of(true) : Optional.empty());
        }
    }

    /** The additional user groups, absent if the answer showed none or could not be read. */
    private static Optional<List<String>> additionalOf(Optional<UserGroups> groups) {
        return groups.map(UserGroups::additionalGroups).filter(names -> !names.isEmpty());
    }

    /**
     * The value of {@code block}; if it could not be read, empty, and each of {@code parts} is
     * added to {@code unavailable} with the block's reason.
     */
    private static <T> Optional<T> read(Block<T> block, List<String> parts,
        List<UnavailablePart> unavailable) {
        return switch (block) {
            case Block.Parsed<T> parsed -> Optional.of(parsed.value());
            case Block.Unavailable<T> missing -> {
                parts.forEach(part -> unavailable.add(UnavailablePart.of(part, missing.error())));
                yield Optional.empty();
            }
        };
    }
}
