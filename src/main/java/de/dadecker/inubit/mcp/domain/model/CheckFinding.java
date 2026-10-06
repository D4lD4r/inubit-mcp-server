package de.dadecker.inubit.mcp.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One result of {@code check_artifacts} (data-model.md → Checks, FR-034).
 *
 * @param path     the workspace-relative file the finding is about
 * @param location an element path or {@code line:column}, if known
 * @param code     a stable identifier such as {@code EDGE_TARGET_MISSING}
 * @param message  the text for the assistant; callers keep secret values out of it and bound it
 */
public record CheckFinding(Severity severity, Check check, String path,
    Optional<String> location, String code, String message) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    /** How serious a finding is; {@link #ERROR} first. */
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    /** The check that produced a finding. */
    public enum Check {
        STRUCTURE,
        XSLT,
        XML,
        XSD
    }

    public CheckFinding {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(check, "check");
        PathChange.requireWorkspacePath(path, "path");
        location = location == null ? Optional.empty() : location;
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code must match " + CODE.pattern());
        }
        Objects.requireNonNull(message, "message");
    }
}
