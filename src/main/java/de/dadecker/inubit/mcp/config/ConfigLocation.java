package de.dadecker.inubit.mcp.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where the running server's configuration lives (feature 015, research R-9, data-model.md →
 * ConfigLocation); an archive target must stay out of both folders.
 *
 * @param defaultDirectory {@link ConfigLoader#defaultDirectory()} of the running server
 * @param source {@link LoadedConfig#source()}, the configuration file in use
 */
public record ConfigLocation(Path defaultDirectory, Path source) {

    public ConfigLocation {
        Objects.requireNonNull(defaultDirectory, "defaultDirectory");
        Objects.requireNonNull(source, "source");
    }

    /**
     * The configuration roots: the default directory first, then the folder of the configuration
     * file if it is another one (a {@code --config} file elsewhere).
     */
    public List<Path> roots() {
        Path folder = source.toAbsolutePath().getParent();
        if (folder == null || same(folder, defaultDirectory)) {
            return List.of(defaultDirectory);
        }
        return List.of(defaultDirectory, folder);
    }

    private static boolean same(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
