package de.dadecker.inubit.mcp.domain.model.partner;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The validated input of one {@code find_partners} call (data-model.md → PartnerQuery,
 * contracts/find_partners.md). Every rule of FR-002 is checked here, before INUBIT is contacted;
 * a violation is {@code INVALID_INPUT}. Whether {@code target} and {@code compareWith} name
 * configured ids is decided later by the {@code TargetResolver} ({@code TARGET_UNKNOWN}).
 *
 * @param target        the id of one stage or one server
 * @param compareWith   a second stage or server id; must not equal or overlap {@code target}
 * @param owner         the owner for all servers; default each server's {@code inventory.owner}
 * @param search        the search term: 1–200 code points, no ISO control characters
 * @param systemDiagram only this system diagram (name, exact, case-insensitive)
 * @param protocol      only entries of this protocol: 1–64 characters {@code [A-Za-z0-9_-]}
 * @param workflow      only entries referencing this technical workflow (name)
 * @param offset        the first partner returned, ≥ 0 (tool default 0)
 * @param limit         the most partners returned, 1–{@value #MAX_LIMIT} (tool default
 *                      {@value #DEFAULT_LIMIT})
 * @param refresh       whether the cache is bypassed (tool default false)
 * @param mcpClient     the MCP client's {@code name/version}, if sent
 */
public record PartnerQuery(String target, Optional<String> compareWith, Optional<String> owner,
    Optional<String> search, Optional<String> systemDiagram, Optional<String> protocol,
    Optional<String> workflow, int offset, int limit, boolean refresh,
    Optional<String> mcpClient) {

    /** The {@code limit} when the call gives none. */
    public static final int DEFAULT_LIMIT = 20;

    /** The largest {@code limit}. */
    public static final int MAX_LIMIT = 100;

    private static final int MAX_TEXT = 200;
    private static final int MAX_PROTOCOL = 64;

    /** The owner rule of {@code export_archives} (its input schema and the StartCLI rule). */
    private static final Pattern OWNER =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");

    /** The control characters the existing diagram and workflow name rule refuses. */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001F\\u007F]");

    private static final Pattern PROTOCOL = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public PartnerQuery {
        if (target == null || target.isBlank()) {
            throw invalid("target is required: the id of one stage or one server");
        }
        compareWith = compareWith == null ? Optional.empty() : compareWith;
        owner = owner == null ? Optional.empty() : owner;
        search = search == null ? Optional.empty() : search;
        systemDiagram = systemDiagram == null ? Optional.empty() : systemDiagram;
        protocol = protocol == null ? Optional.empty() : protocol;
        workflow = workflow == null ? Optional.empty() : workflow;
        mcpClient = mcpClient == null ? Optional.empty() : mcpClient;
        if (compareWith.filter(other -> overlaps(target, other)).isPresent()) {
            throw invalid("compareWith " + Names.quote(compareWith.get()) + " equals or overlaps"
                + " target " + Names.quote(target) + "; compare with another stage or server");
        }
        if (owner.filter(value -> !OWNER.matcher(value).matches()).isPresent()) {
            throw invalid("owner is not 1 to " + MAX_TEXT + " letters, digits, '_', '.', '-' or"
                + " spaces, starting with a letter, digit, '_' or '.'");
        }
        if (search.filter(PartnerQuery::invalidSearch).isPresent()) {
            throw invalid("search is not 1 to " + MAX_TEXT + " characters without control"
                + " characters");
        }
        if (systemDiagram.filter(PartnerQuery::invalidName).isPresent()) {
            throw invalid("systemDiagram is not 1 to " + MAX_TEXT + " characters without control"
                + " characters");
        }
        if (protocol.filter(value -> !PROTOCOL.matcher(value).matches()).isPresent()) {
            throw invalid("protocol is not 1 to " + MAX_PROTOCOL + " letters, digits, '-' or"
                + " '_'");
        }
        if (workflow.filter(PartnerQuery::invalidName).isPresent()) {
            throw invalid("workflow is not 1 to " + MAX_TEXT + " characters without control"
                + " characters");
        }
        if (offset < 0) {
            throw invalid("offset must be >= 0, got " + offset);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
        }
    }

    /** Same id, or a stage and one of its servers (ids: {@code <stage>[/<server>]}). */
    private static boolean overlaps(String one, String other) {
        return one.equals(other) || one.startsWith(other + "/") || other.startsWith(one + "/");
    }

    private static boolean invalidSearch(String value) {
        int length = value.codePointCount(0, value.length());
        return length < 1 || length > MAX_TEXT
            || value.codePoints().anyMatch(Character::isISOControl);
    }

    /** The {@code export_archives} workflow name rule, also used for system diagram names. */
    private static boolean invalidName(String value) {
        int length = value.codePointCount(0, value.length());
        return length < 1 || length > MAX_TEXT || CONTROL.matcher(value).find();
    }

    private static ToolErrorException invalid(String message) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT, message,
            "The call does not match the tool's input schema",
            "Correct the parameter as the tool's input schema describes"));
    }
}
