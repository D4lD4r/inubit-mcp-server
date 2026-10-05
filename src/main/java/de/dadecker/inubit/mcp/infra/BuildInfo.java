package de.dadecker.inubit.mcp.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Build metadata from the Maven-filtered {@code build.properties} (project version). */
public final class BuildInfo {

    private static final String RESOURCE = "/de/dadecker/inubit/mcp/build.properties";
    private static final String VERSION = load();

    private BuildInfo() {
    }

    /** The Maven project version, e.g. {@code 0.1.0}. */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the build");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").strip();
            if (version.isEmpty() || version.contains("${")) {
                throw new IllegalStateException(RESOURCE + " was not filtered by the build");
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
