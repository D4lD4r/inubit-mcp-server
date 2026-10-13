package de.dadecker.inubit.mcp.domain.model;

import java.util.regex.Pattern;

/**
 * The rules for repository paths (research D-4, data-model.md): the StartCLI rule for full paths
 * {@code /Root/…} and the rule for paths relative to an owner's root {@code /Root/<owner>}.
 *
 * <p>A relative path is not normalized but refused unless it is already normal: a {@code %}, an
 * empty segment, a {@code .} or {@code ..} segment and a leading {@code /} are refused outright, a
 * deliberate, stricter reading of FR-005 (research D-4).
 */
public final class RepositoryPaths {

    /** The StartCLI rule of full repository paths ({@code CliCommand.REPOSITORY_PATH}). */
    private static final Pattern FULL =
        Pattern.compile("^/Root(/[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199})+$");

    /** The StartCLI rule of one path segment. */
    private static final Pattern SEGMENT =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");

    private RepositoryPaths() {
    }

    /** True if StartCLI can take the full path {@code fullPath} (no {@code .} or {@code ..}). */
    public static boolean passable(String fullPath) {
        if (fullPath == null || !FULL.matcher(fullPath).matches()) {
            return false;
        }
        for (String segment : fullPath.split("/")) {
            if (isDotSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if {@code relative} is a normalized path relative to an owner's root: one or more
     * segments of the StartCLI rule, none of them {@code .} or {@code ..}, no leading {@code /}.
     */
    public static boolean relativeValid(String relative) {
        if (relative == null) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || isDotSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    /** The full path {@code /Root/<owner>/<relative>}; {@code relative} must be valid. */
    public static String full(String owner, String relative) {
        if (!relativeValid(relative)) {
            throw new IllegalArgumentException("Not a valid relative repository path: " + relative);
        }
        return "/Root/" + owner + "/" + relative;
    }

    private static boolean isDotSegment(String segment) {
        return segment.equals(".") || segment.equals("..");
    }
}
