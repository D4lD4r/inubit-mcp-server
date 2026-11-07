package de.dadecker.inubit.mcp.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Build metadata: the project version. Inside the release JAR it comes from the JAR's own manifest
 * ({@code Implementation-Version}, feature 016 FR-010/FR-011), so it does not depend on the file
 * name. Outside a JAR (tests, IDE runs from {@code target/classes}) it comes from the
 * Maven-filtered {@code build.properties}.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/de/dadecker/inubit/mcp/build.properties";
    private static final String VERSION = resolve(manifestVersion(ownLocation()),
        BuildInfo::fromBuildProperties);

    private BuildInfo() {
    }

    /** The Maven project version, e.g. {@code 0.1.0}. */
    public static String version() {
        return VERSION;
    }

    /** The manifest version if present, else the version {@code fallback} supplies. */
    static String resolve(Optional<String> manifestVersion, Supplier<String> fallback) {
        return manifestVersion.orElseGet(fallback);
    }

    /**
     * The {@code Implementation-Version} main attribute of the JAR at {@code location}; empty for
     * a directory, a missing or unreadable file, or a missing, blank or unfiltered attribute.
     */
    static Optional<String> manifestVersion(Optional<Path> location) {
        if (location.isEmpty() || !Files.isRegularFile(location.get())) {
            return Optional.empty();
        }
        try (JarFile jar = new JarFile(location.get().toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            String value = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            return usable(value);
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    /** The version in a {@code build.properties} stream; {@code in} may be null (missing). */
    static String fromBuildProperties(InputStream in) {
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " is missing from the build");
        }
        try (in) {
            Properties properties = new Properties();
            properties.load(in);
            return usable(properties.getProperty("version"))
                .orElseThrow(() -> new IllegalStateException(RESOURCE + " was not filtered by the build"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fromBuildProperties() {
        return fromBuildProperties(BuildInfo.class.getResourceAsStream(RESOURCE));
    }

    private static Optional<String> usable(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String stripped = value.strip();
        return stripped.isEmpty() || stripped.contains("${") ? Optional.empty() : Optional.of(stripped);
    }

    private static Optional<Path> ownLocation() {
        try {
            CodeSource source = BuildInfo.class.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? Optional.empty()
                : Optional.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return Optional.empty();
        }
    }
}
