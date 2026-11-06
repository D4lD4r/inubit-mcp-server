package de.dadecker.inubit.mcp.application;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.port.NetworkPathProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules a target folder of {@code export_archives} must meet (feature 015, research R-9,
 * R-10, FR-008, FR-010). The rule names in the messages are those of
 * contracts/mcp-tools-delta.md "Folder rules", which {@code docs/tools.md} and
 * {@code SECURITY.md} repeat.
 *
 * <p>{@link #check(String)} applies, in this order:
 * <ol>
 *   <li>with the Windows flag, from the raw string before anything else (so the rule is the same
 *       on a non-Windows host, where these strings are relative paths): a UNC root
 *       ({@code \\server\share}, {@code \\?\UNC\…}) and a drive root {@code X:\} whose letter the
 *       {@link NetworkPathProbe} reports remote are network paths;</li>
 *   <li>the path is absolute and has no {@code .} or {@code ..} element;</li>
 *   <li>the folder, or (missing) its nearest existing ancestor, is a writable directory;</li>
 *   <li>the folder is not and does not lie inside one of the server's directories — the
 *       {@code .inubit-mcp} directory below the home directory, the configured
 *       {@code workspace} and {@code auditDirectory}, and the configuration roots;</li>
 *   <li>the folder does not lie inside a git working tree (a {@code .git} entry, file or
 *       directory, in the folder or an ancestor);</li>
 *   <li>a folder below a known cloud-sync root is accepted with a warning.</li>
 * </ol>
 * Rules 4 to 6 are checked on the path as given and on the path with symbolic links resolved
 * (for a missing folder: its nearest existing ancestor resolved, joined with the missing rest),
 * against each root as given and resolved the same way, so that a link in either direction, also
 * a forbidden root that is itself a link, is caught.
 */
public final class TargetFolderRules {

    private static final String CLOUD_WARNING = "the archives (with clear-text secrets) will be"
        + " synchronized to the cloud";
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final List<String> ONE_DRIVE_VARIABLES =
        List.of("OneDrive", "OneDriveCommercial", "OneDriveConsumer");

    /**
     * An accepted target folder.
     *
     * @param path the absolute target folder as given (normalized)
     * @param exists whether it existed at check time
     * @param nearestExisting the folder itself or its nearest existing ancestor, a writable
     *     directory
     * @param warnings the cloud-sync warning, if any
     */
    public record CheckedFolder(Path path, boolean exists, Path nearestExisting,
        List<String> warnings) {

        public CheckedFolder {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(nearestExisting, "nearestExisting");
            warnings = List.copyOf(warnings);
        }
    }

    private record Root(String label, Path path) {
    }

    private final List<Root> forbidden;
    private final List<Path> cloudRoots;
    private final NetworkPathProbe probe;
    private final boolean windows;

    /**
     * @param home the user's home directory
     * @param workspace the configured {@code workspace}
     * @param auditDirectory the configured {@code auditDirectory}
     * @param configurationRoots the default configuration directory first, then the folder of the
     *     configuration file in use if it differs
     * @param environment the process environment (for the OneDrive folders)
     * @param probe tells whether a Windows drive letter is a network drive
     * @param windows whether the server runs on Windows
     */
    public TargetFolderRules(Path home, Path workspace, Path auditDirectory,
        List<Path> configurationRoots, Map<String, String> environment, NetworkPathProbe probe,
        boolean windows) {
        Objects.requireNonNull(home, "home");
        List<Root> roots = new ArrayList<>();
        roots.add(new Root("~/.inubit-mcp", home.resolve(".inubit-mcp")));
        roots.add(new Root("the configured workspace",
            Objects.requireNonNull(workspace, "workspace")));
        roots.add(new Root("the configured auditDirectory",
            Objects.requireNonNull(auditDirectory, "auditDirectory")));
        for (int i = 0; i < configurationRoots.size(); i++) {
            roots.add(new Root(i == 0 ? "the configuration directory"
                : "the folder of the configuration file in use", configurationRoots.get(i)));
        }
        this.forbidden = List.copyOf(roots);
        List<Path> cloud = new ArrayList<>(List.of(
            home.resolve("Library").resolve("CloudStorage"),
            home.resolve("Library").resolve("Mobile Documents")));
        for (String variable : ONE_DRIVE_VARIABLES) {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) {
                parse(value).filter(Path::isAbsolute).ifPresent(cloud::add);
            }
        }
        this.cloudRoots = List.copyOf(cloud);
        this.probe = Objects.requireNonNull(probe, "probe");
        this.windows = windows;
    }

    /**
     * Checks {@code targetFolder}.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} naming the rule and the folder
     */
    public CheckedFolder check(String targetFolder) {
        if (windows) {
            checkNetwork(targetFolder);
        }
        Path given = parse(targetFolder).filter(Path::isAbsolute).orElseThrow(() -> refused(
            targetFolder, "is not an absolute path",
            "targetFolder must be the absolute path of a local folder",
            "Pass the absolute path of the folder for the archives"));
        for (Path element : given) {
            String name = element.toString();
            if (name.equals(".") || name.equals("..")) {
                throw refused(targetFolder, "has a '.' or '..' element",
                    "The path is not in its plain form",
                    "Pass the absolute path without '.' and '..' elements");
            }
        }
        Path path = given.normalize();

        Path nearest = nearestExisting(path);
        boolean exists = nearest.equals(path);
        if (!Files.isDirectory(nearest) || !Files.isWritable(nearest)) {
            throw notWritable(targetFolder, exists, nearest);
        }
        Path resolved;
        try {
            resolved = nearest.toRealPath().resolve(nearest.relativize(path));
        } catch (IOException e) {
            throw notWritable(targetFolder, exists, nearest);
        }
        List<Path> forms = List.of(path, resolved);

        for (Root root : forbidden) {
            for (Path rootForm : forms(root.path())) {
                if (forms.stream().anyMatch(form -> form.startsWith(rootForm))) {
                    throw refused(targetFolder, "is or lies inside one of the server's"
                            + " directories: " + root.label() + " " + root.path(),
                        "The server's own directories hold its configuration, state and audit"
                            + " records; archives with clear-text secrets must not be placed"
                            + " there",
                        "Choose a folder outside the server's directories");
                }
            }
        }
        for (Path form : forms) {
            for (Path dir = form; dir != null; dir = dir.getParent()) {
                if (Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                    throw refused(targetFolder, "is or lies inside a git working tree (a .git"
                            + " entry in " + dir + ")",
                        "Archives with clear-text secrets in a git working tree are easily"
                            + " committed",
                        "Choose a folder outside every git working tree");
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        cloudRoot(forms).ifPresent(root -> warnings.add(shown("The target folder " + path
            + " lies below the cloud-sync folder " + root + ": " + CLOUD_WARNING)));
        return new CheckedFolder(path, exists, nearest, warnings);
    }

    private void checkNetwork(String raw) {
        if (raw.length() >= 2 && separator(raw.charAt(0)) && separator(raw.charAt(1))) {
            throw refused(raw, "is a network path (a UNC path)",
                "Archives with clear-text secrets must stay on a local disk",
                "Choose a folder on a local disk");
        }
        if (raw.length() >= 3 && isAsciiLetter(raw.charAt(0)) && raw.charAt(1) == ':'
            && separator(raw.charAt(2))) {
            char letter = Character.toUpperCase(raw.charAt(0));
            if (probe.remoteDrive(letter)) {
                throw refused(raw, "is a network path (" + letter + ": is a mapped network"
                        + " drive)",
                    "Archives with clear-text secrets must stay on a local disk",
                    "Choose a folder on a local disk");
            }
        }
    }

    private Optional<Path> cloudRoot(List<Path> forms) {
        for (Path root : cloudRoots) {
            for (Path rootForm : forms(root)) {
                if (forms.stream().anyMatch(form -> form.startsWith(rootForm))) {
                    return Optional.of(root);
                }
            }
        }
        return Optional.empty();
    }

    /** {@code root} as given and with symbolic links resolved as far as it exists. */
    private List<Path> forms(Path root) {
        Path given = root.toAbsolutePath().normalize();
        Path nearest = given;
        while (nearest != null && !Files.exists(nearest)) {
            nearest = nearest.getParent();
        }
        if (nearest == null) {
            return List.of(given);
        }
        try {
            Path resolved = nearest.toRealPath().resolve(nearest.relativize(given));
            return resolved.equals(given) ? List.of(given) : List.of(given, resolved);
        } catch (IOException e) {
            return List.of(given);
        }
    }

    /**
     * The folder itself or its nearest ancestor that exists; a dangling symbolic link counts as
     * existing (and then is no directory).
     */
    private static Path nearestExisting(Path path) {
        Path current = path;
        while (current.getParent() != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        return current;
    }

    private static ToolErrorException notWritable(String raw, boolean exists, Path nearest) {
        return refused(raw, exists ? "exists and is not a writable directory"
                : "does not exist and its nearest existing ancestor " + nearest
                    + " is not a writable directory",
            "The path names a file, or the folder's permissions do not allow writing",
            "Choose a writable folder, or a new folder below a writable one");
    }

    private static ToolErrorException refused(String raw, String rule, String likelyCause,
        String nextStep) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            shown("The target folder '" + raw + "' " + rule), likelyCause, nextStep));
    }

    /** {@code text} with each control character as {@code ?} (the folder is caller input). */
    private static String shown(String text) {
        return CONTROL.matcher(text).replaceAll("?");
    }

    private static Optional<Path> parse(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static boolean separator(char c) {
        return c == '\\' || c == '/';
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }
}
