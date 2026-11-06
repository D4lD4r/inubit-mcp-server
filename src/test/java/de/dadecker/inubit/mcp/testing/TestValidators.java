package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.config.ConfigValidator;
import de.dadecker.inubit.mcp.config.WorkspaceDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Configuration validators for tests, writing the workspace with the host's private files. */
public final class TestValidators {

    private TestValidators() {
    }

    /** A validator of the real file system and process environment; {@code windows} as given. */
    public static ConfigValidator ofSystem(boolean windows) {
        return of(Files::exists, System.getenv(), windows);
    }

    public static ConfigValidator of(Predicate<Path> exists, Map<String, String> environment,
        boolean windows) {
        return new ConfigValidator(exists, environment, windows,
            Path.of(System.getProperty("java.io.tmpdir")), source -> List.of(),
            workspace -> WorkspaceDirectory.prepare(workspace, PrivateFiles.forHost()));
    }
}
