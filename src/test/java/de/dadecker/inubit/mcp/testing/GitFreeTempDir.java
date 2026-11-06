package de.dadecker.inubit.mcp.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * A {@code @TempDir} factory for tests whose target folders pass the folder rules of
 * {@code export_archives} (feature 015, research R-9): the directory lies outside every git
 * working tree. A build that sets {@code java.io.tmpdir} below
 * the checkout (the isolated temporary directory of a local build) would otherwise put every
 * test folder inside a git tree, which the rules refuse; the factory then falls back to the
 * operating system's temporary directory ({@code TMPDIR}, {@code TEMP}, {@code TMP}).
 */
public final class GitFreeTempDir implements TempDirFactory {

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext,
        ExtensionContext extensionContext) throws IOException {
        List<String> candidates = new ArrayList<>();
        candidates.add(System.getProperty("java.io.tmpdir"));
        for (String variable : List.of("TMPDIR", "TEMP", "TMP")) {
            candidates.add(System.getenv(variable));
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path base = Path.of(candidate);
            if (Files.isDirectory(base) && !insideGitTree(base.toRealPath())) {
                return Files.createTempDirectory(base, "junit-folder-rules-");
            }
        }
        throw new IllegalStateException("No temporary directory outside a git working tree among "
            + candidates);
    }

    private static boolean insideGitTree(Path directory) {
        for (Path dir = directory; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }
}
