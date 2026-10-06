package de.dadecker.inubit.mcp.domain.port;

import de.dadecker.inubit.mcp.domain.model.CheckFinding;
import de.dadecker.inubit.mcp.domain.model.XsltRun;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Local stylesheet runs and XML validation for {@code check_artifacts} (feature 003, research
 * D-11, D-12). Implementations never touch an INUBIT server and read and write only inside the
 * workspace.
 */
public interface XsltPort {

    /** Runs a stylesheet of the workspace on an input file of the workspace. */
    XsltRun run(XsltRequest request);

    /**
     * Checks that {@code xml} is well-formed and, with {@code xsd}, valid against that schema.
     *
     * @return one finding per problem, with its location; empty if none
     */
    List<CheckFinding> validate(Path xml, Optional<Path> xsd);

    /**
     * One stylesheet run.
     *
     * @param params stylesheet parameters by name, an immutable copy
     * @param now    the time the date/time stand-ins return instead of their fixed instant
     */
    record XsltRequest(Path stylesheet, Path input, Map<String, String> params,
        Optional<Instant> now) {

        public XsltRequest {
            Objects.requireNonNull(stylesheet, "stylesheet");
            Objects.requireNonNull(input, "input");
            params = params == null ? Map.of() : Map.copyOf(params);
            now = now == null ? Optional.empty() : now;
        }
    }
}
