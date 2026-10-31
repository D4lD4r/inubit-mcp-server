package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import de.dadecker.inubit.mcp.infra.AuditLog;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Finds and parses the YAML configuration (contracts/configuration.md).
 *
 * <ul>
 *   <li>Location (002 research D-9): {@code --config}, then {@code --profile <name>}
 *       ({@code <name>.yaml} in the user config directory), then {@code INUBIT_MCP_CONFIG}, then
 *       {@code INUBIT_MCP_PROFILE}, then {@code config.yaml} in the user config directory. An
 *       explicitly given location that does not exist is an error; there is no fallback to a
 *       later location. A file selected by a profile name must declare that name.
 *   <li>A file in the format of feature 001 is refused with migration guidance
 *       ({@link Format001Detector}) before any other check of its keys.
 *   <li>Unknown keys are rejected, except top-level keys starting with {@code x-}.
 *   <li>Credential keys ({@link #isCredentialKey}) are stripped and reported in
 *       {@link LoadedConfig#credentialKeys()} for {@link ConfigValidator}; their values are
 *       dropped. Exempt are the {@code credentials} sections at the top, group and node level
 *       and the parameter kinds inside their {@code parameters} (feature 010, research R-4).
 *   <li>Inside a {@code credentials} section only known keys are accepted, and every parameter
 *       name must be valid; errors name the key path, never a value.
 *   <li>{@code ~} at the start of a path means the user's home directory.
 *   <li>Defaults that depend on the profile name: {@code auditDirectory} and {@code workspace}
 *       (feature 003) below {@code ~/.inubit-mcp/<profile.name>}.
 * </ul>
 */
public final class ConfigLoader {

    public static final String CONFIG_ENV = "INUBIT_MCP_CONFIG";
    /** Selects {@code <name>.yaml} in the default configuration directory (002 research D-9). */
    public static final String PROFILE_ENV = "INUBIT_MCP_PROFILE";
    /** Stands in for an invalid profile name in the default audit directory (never created). */
    static final String NO_PROFILE_AUDIT_DIRECTORY = ProfileInfo.INVALID_NAME;
    /** The file used when no location is given. */
    private static final String DEFAULT_FILE = "config.yaml";

    private static final Set<String> CREDENTIAL_KEYS = Set.of("username", "credentials");

    /** The section that says where the credentials come from (top, group and node level). */
    private static final String CREDENTIALS_SECTION = "credentials";

    /** Paths of the mappings that may hold a {@code credentials} section: top, group, node. */
    private static final Pattern SECTION_OWNER =
        Pattern.compile("(groups\\[\\d+](\\.nodes\\[\\d+])?)?");

    /** Paths of the {@code parameters} mappings inside a {@code credentials} section. */
    private static final Pattern PARAMETERS_PATH =
        Pattern.compile("(groups\\[\\d+](\\.nodes\\[\\d+])?\\.)?credentials\\.parameters");

    /** The keys of {@code credentials.parameters} (feature 010, data-model → ParameterNames). */
    private static final List<String> PARAMETER_KINDS = List.of("username", "password",
        "trustStorePassword", "e2eUsername", "e2ePassword");

    private static final String CREDENTIAL_STORE = "credentialStore";
    /** The source type of feature 010 removed in 0.7.0 (feature 011, research R-6). */
    private static final String ENVIRONMENT = "environment";
    private static final String AWS_PARAMETER_STORE = "awsParameterStore";

    private final Map<String, String> environment;
    private final Path userHome;
    private final boolean windows;
    private final YAMLMapper mapper;

    public ConfigLoader(Map<String, String> environment, Path userHome, boolean windows) {
        this.environment = Map.copyOf(environment);
        this.userHome = userHome;
        this.windows = windows;
        this.mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Jackson 3 enables this by default; an absent boolean means its documented default
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .addModule(new SimpleModule("inubit-mcp-config")
                .addDeserializer(Path.class, new HomeExpandingPathDeserializer(userHome))
                .addDeserializer(Duration.class, new IsoDurationDeserializer())
                .addDeserializer(VersionSetting.class, new VersionSettingDeserializer()))
            .build();
    }

    /** A loader for the current process environment. */
    public static ConfigLoader forSystem() {
        return new ConfigLoader(System.getenv(), Path.of(System.getProperty("user.home")),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** Returns the configuration file to use; neither {@code --config} nor {@code --profile}. */
    public Path locate() {
        return locate(null, null);
    }

    /** Returns the configuration file to use; {@code --profile} was not given. */
    public Path locate(String commandLinePath) {
        return locate(commandLinePath, null);
    }

    /**
     * Returns the configuration file to use, or fails with the searched locations (002 research
     * D-9; first match wins): {@code --config}, {@code --profile}, {@code INUBIT_MCP_CONFIG},
     * {@code INUBIT_MCP_PROFILE}, the default file. A given location that does not exist is an
     * error; later locations are then not used.
     *
     * @param commandLinePath the {@code --config} value, or {@code null}
     * @param profile         the {@code --profile} value (a valid profile name), or {@code null}
     */
    public Path locate(String commandLinePath, String profile) {
        return selection(commandLinePath, profile).file();
    }

    /** Locates and loads the configuration file; see {@link #locate()}. */
    public LoadedConfig load() {
        return load(null, null);
    }

    /** Locates and loads the configuration file; see {@link #locate(String)}. */
    public LoadedConfig load(String commandLinePath) {
        return load(commandLinePath, null);
    }

    /**
     * Locates and loads the configuration file ({@link #locate(String, String)}). A file selected
     * by a profile name ({@code --profile} or {@code INUBIT_MCP_PROFILE}) must declare that name
     * as {@code profile.name}.
     */
    public LoadedConfig load(String commandLinePath, String profile) {
        Selection selection = selection(commandLinePath, profile);
        LoadedConfig loaded = loadFile(selection.file());
        selection.profile().ifPresent(expected -> {
            String declared = loaded.config().profile().name();
            if (!declared.equals(expected)) {
                // the declared name is printed only if valid (002 US2 review P3a)
                String shown = ProfileInfo.isValidName(declared) ? "'" + declared + "'"
                    : ProfileInfo.INVALID_NAME;
                throw new ConfigException("The configuration file " + selection.file()
                    + " declares profile.name " + shown + ", but " + selection.selectedBy()
                    + " selects the profile '" + expected + "'; rename the file or fix"
                    + " profile.name");
            }
        });
        return loaded;
    }

    /**
     * The default configuration directory: {@code ~/.config/inubit-mcp}, on Windows
     * {@code %APPDATA%\inubit-mcp}.
     */
    public Path defaultDirectory() {
        if (windows) {
            String appData = environment.get("APPDATA");
            Path base = appData == null || appData.isBlank()
                ? userHome.resolve("AppData").resolve("Roaming")
                : Path.of(appData);
            return base.resolve("inubit-mcp");
        }
        return userHome.resolve(".config").resolve("inubit-mcp");
    }

    /**
     * The other profile files ({@code *.yaml}) in the {@linkplain #defaultDirectory() default
     * configuration directory}, for the best-effort checks of {@link ConfigValidator} (002 T031).
     * {@code except} (the file being checked) is skipped; a file that cannot be read or parsed is
     * skipped silently, as is everything that is not a regular file.
     */
    public List<LoadedConfig> otherProfiles(Path except) {
        Path directory = defaultDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<LoadedConfig> others = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.yaml")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file) || sameFile(file, except)) {
                    continue;
                }
                try {
                    others.add(loadFile(file));
                } catch (ConfigException e) {
                    // best effort: that file's own check reports its problems
                }
            }
        } catch (IOException | RuntimeException e) {
            // best effort: no comparison without a readable directory
        }
        others.sort(Comparator.comparing(LoadedConfig::source));
        return List.copyOf(others);
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())
                || Files.isSameFile(a, b);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The located file and, if it was selected by a profile name, that name and how it was given
     * (e.g. {@code --profile acme}).
     */
    private record Selection(Path file, Optional<String> profile, String selectedBy) {
    }

    private Selection selection(String commandLinePath, String profile) {
        if (commandLinePath != null) {
            Path path = expandHome(commandLinePath.strip(), userHome);
            if (Files.isRegularFile(path)) {
                return new Selection(path, Optional.empty(), "--config");
            }
            throw notFound("  --config " + path + " (not found; later locations are not used)");
        }
        if (profile != null) {
            return profileSelection(profile, "--profile " + profile, "  --config (not given)\n");
        }
        String fromEnv = environment.get(CONFIG_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path path = expandHome(fromEnv.strip(), userHome);
            if (Files.isRegularFile(path)) {
                return new Selection(path, Optional.empty(), CONFIG_ENV);
            }
            throw notFound("  --config (not given)\n  --profile (not given)\n  " + CONFIG_ENV + "="
                + path + " (not found; later locations are not used)");
        }
        String profileFromEnv = environment.get(PROFILE_ENV);
        String searched = "  --config (not given)\n  --profile (not given)\n  " + CONFIG_ENV
            + " (not set)\n";
        if (profileFromEnv != null && !profileFromEnv.isBlank()) {
            String name = profileFromEnv.strip();
            if (!ProfileInfo.isValidName(name)) {
                // the value is not echoed: it is no profile name and could be anything
                throw new ConfigException(PROFILE_ENV + " must be a profile name matching "
                    + ProfileInfo.NAME_RULE);
            }
            return profileSelection(name, PROFILE_ENV + "=" + name, searched);
        }
        Path defaultLocation = defaultDirectory().resolve(DEFAULT_FILE);
        if (Files.isRegularFile(defaultLocation)) {
            return new Selection(defaultLocation, Optional.empty(), "the default location");
        }
        throw notFound(searched + "  " + PROFILE_ENV + " (not set)\n  " + defaultLocation
            + " (not found)");
    }

    /** {@code <default directory>/<name>.yaml}; {@code name} is a valid profile name. */
    private Selection profileSelection(String name, String selectedBy, String searchedBefore) {
        if (!ProfileInfo.isValidName(name)) {
            // the value is not echoed: it is no profile name and could be anything
            throw new ConfigException("--profile must be a profile name matching "
                + ProfileInfo.NAME_RULE);
        }
        Path path = defaultDirectory().resolve(name + ".yaml");
        if (Files.isRegularFile(path)) {
            return new Selection(path, Optional.of(name), selectedBy);
        }
        throw notFound(searchedBefore + "  " + selectedBy + ": " + path
            + " (not found; later locations are not used)");
    }

    public LoadedConfig loadFile(Path file) {
        String yaml;
        try {
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new ConfigException("Cannot read configuration file " + file + " ("
                + e.getClass().getSimpleName() + ")");
        }
        return parse(yaml, file);
    }

    /** Parses YAML text; {@code source} is used in messages and kept in the result. */
    public LoadedConfig parse(String yaml, Path source) {
        JsonNode root;
        try {
            root = new YamlTreeReader(mapper).read(yaml);
        } catch (YamlTreeReader.YamlStructureException e) {
            throw new ConfigException("Invalid YAML in " + source + ": " + e.getMessage());
        } catch (StreamReadException e) {
            throw new ConfigException("Invalid YAML in " + source + locationOf(e.getLocation()));
        } catch (JacksonException e) {
            throw new ConfigException("Invalid YAML in " + source + " ("
                + e.getClass().getSimpleName() + ")");
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new ConfigException("Configuration file " + source + " is empty");
        }
        if (!(root instanceof ObjectNode rootObject)) {
            throw new ConfigException("Configuration file " + source
                + " must contain a mapping at the top level");
        }
        List<String> credentialKeys = new ArrayList<>();
        stripCredentialKeys(rootObject, "", credentialKeys);
        rootObject.properties().removeIf(entry -> entry.getKey().startsWith("x-"));
        // 002 research D-12: before binding, so a 001 file is not refused as "Unknown key"
        Format001Detector.check(rootObject, source);
        checkCredentialsSections(rootObject, source);
        rejectEmptyListEntries(rootObject, "", source);
        List<String> urlProblems = new ArrayList<>();
        sanitizeUrls(rootObject, source, urlProblems);
        if (!rootObject.hasNonNull("auditDirectory")) {
            rootObject.put("auditDirectory", defaultAuditDirectory(rootObject).toString());
        }
        if (!rootObject.hasNonNull("workspace")) {
            rootObject.put("workspace", defaultWorkspace(rootObject).toString());
        }
        try {
            return new LoadedConfig(source, mapper.treeToValue(rootObject, ProfileConfig.class),
                credentialKeys, urlProblems);
        } catch (UnrecognizedPropertyException e) {
            String at = pathOf(e.getPath().subList(0, e.getPath().size() - 1));
            throw new ConfigException("Unknown key '" + e.getPropertyName() + "'"
                + (at.isEmpty() ? " at the top level" : " at " + at) + " in " + source);
        } catch (DatabindException e) {
            // the offending value is never echoed: it could be a URL or text with secrets
            String what = targetType(e) == VersionSetting.class ? "versionLine" : "value";
            throw new ConfigException("Invalid " + what + " at " + pathOf(e.getPath()) + " in "
                + source + ": expected " + expected(e));
        } catch (JacksonException e) {
            throw new ConfigException("Invalid configuration in " + source + " ("
                + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * {@code <home>/.inubit-mcp/<profile.name>/audit} (002 research D-7). A missing or invalid
     * profile name, which {@link ConfigValidator} reports and with which the server never starts,
     * is not used in a path: the directory is then {@link #NO_PROFILE_AUDIT_DIRECTORY} below
     * {@code <home>/.inubit-mcp}, a name no profile can have (and not the 001 directory).
     */
    private Path defaultAuditDirectory(ObjectNode root) {
        JsonNode name = root.path("profile").path("name");
        if (name.isString() && ProfileInfo.isValidName(name.asString())) {
            return AuditLog.defaultDirectory(userHome, name.asString());
        }
        return userHome.resolve(".inubit-mcp").resolve(NO_PROFILE_AUDIT_DIRECTORY).resolve("audit");
    }

    /**
     * {@code <home>/.inubit-mcp/<profile.name>/workspace} (feature 003, FR-001). As for the audit
     * directory, an invalid profile name is never part of the path: it is
     * {@link #NO_PROFILE_AUDIT_DIRECTORY} then (the server does not start with such a name, and
     * {@link ConfigValidator} creates no workspace for it).
     */
    private Path defaultWorkspace(ObjectNode root) {
        JsonNode name = root.path("profile").path("name");
        String directory = name.isString() && ProfileInfo.isValidName(name.asString())
            ? name.asString() : NO_PROFILE_AUDIT_DIRECTORY;
        return userHome.resolve(".inubit-mcp").resolve(directory).resolve("workspace");
    }

    /** True for keys that must never appear in the YAML: credentials come from the environment. */
    static boolean isCredentialKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return CREDENTIAL_KEYS.contains(lower) || lower.contains("password");
    }

    /**
     * The {@code credentials} mapping at the top, group and node level (feature 010) configures
     * only where the credentials come from; credential keys inside it are still stripped and
     * reported, except the parameter kinds ({@link #isParameterName}). An empty
     * ({@code credentials:}) or {@code null} section is the same as an absent one (002 review
     * G3); a scalar or list value is a credential key.
     */
    private static boolean isCredentialsSection(String path, Map.Entry<String, JsonNode> entry) {
        return SECTION_OWNER.matcher(path).matches() && entry.getKey().equals(CREDENTIALS_SECTION)
            && (entry.getValue().isObject() || entry.getValue().isNull());
    }

    /**
     * A parameter kind inside {@code credentials.parameters}: its value is a Parameter Store
     * name, which {@link #checkParameters} validates before anything else can see it.
     */
    private static boolean isParameterName(String path, String key) {
        return PARAMETER_KINDS.contains(key) && PARAMETERS_PATH.matcher(path).matches();
    }

    static Path expandHome(String path, Path home) {
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return home.resolve(path.substring(2));
        }
        return Path.of(path);
    }

    private static ConfigException notFound(String searched) {
        return new ConfigException("No configuration file found. Searched:\n" + searched);
    }

    private static void stripCredentialKeys(JsonNode node, String path, List<String> found) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> entries = object.properties().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (isCredentialKey(entry.getKey()) && !isCredentialsSection(path, entry)
                    && !isParameterName(path, entry.getKey())) {
                    found.add(childPath);
                    entries.remove();
                } else {
                    stripCredentialKeys(entry.getValue(), childPath, found);
                }
            }
        } else if (node != null && node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                stripCredentialKeys(node.get(i), path + "[" + i + "]", found);
            }
        }
    }

    /**
     * The key rules of the {@code credentials} sections (feature 010, research R-4), after the
     * credential keys are stripped: only {@code prefix}, {@code envPrefix} and {@code source} at
     * the top level,
     * only {@code source} and {@code parameters} on groups and nodes, only the keys of its type
     * inside {@code source} (so AWS access keys are refused, FR-004) and only the parameter kinds
     * inside {@code parameters}, each with a valid name. Messages name keys and paths, never a
     * value: a value may be a pasted secret.
     */
    private static void checkCredentialsSections(ObjectNode root, Path source) {
        checkCredentialsSection(root, "", source);
        JsonNode groups = root.get("groups");
        if (groups == null || !groups.isArray()) {
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            if (!(groups.get(i) instanceof ObjectNode group)) {
                continue;
            }
            String groupPath = "groups[" + i + "]";
            checkCredentialsSection(group, groupPath, source);
            JsonNode nodes = group.get("nodes");
            if (nodes == null || !nodes.isArray()) {
                continue;
            }
            for (int j = 0; j < nodes.size(); j++) {
                if (nodes.get(j) instanceof ObjectNode node) {
                    checkCredentialsSection(node, groupPath + ".nodes[" + j + "]", source);
                }
            }
        }
    }

    private static void checkCredentialsSection(ObjectNode owner, String ownerPath,
        Path source) {
        if (!(owner.get(CREDENTIALS_SECTION) instanceof ObjectNode section)) {
            return; // absent or null; any other value was stripped as a credential key
        }
        boolean topLevel = ownerPath.isEmpty();
        String path = topLevel ? CREDENTIALS_SECTION : ownerPath + "." + CREDENTIALS_SECTION;
        if (topLevel) {
            rejectUnknownKeys(section, path, List.of("prefix", "envPrefix", "source"),
                "allowed: prefix, envPrefix, source; parameters only on groups and nodes", source);
        } else {
            rejectUnknownKeys(section, path, List.of("source", "parameters"),
                "allowed: source, parameters; prefix and envPrefix only at the top level", source);
        }
        checkSource(section.get("source"), path + ".source", source);
        JsonNode parameters = section.get("parameters");
        if (parameters instanceof ObjectNode parameterNames) {
            checkParameters(parameterNames, path + ".parameters", source);
        } else if (parameters != null && !parameters.isNull()) {
            throw new ConfigException("Invalid value at " + path + ".parameters in " + source
                + ": expected a mapping");
        }
    }

    private static void checkSource(JsonNode node, String path, Path source) {
        if (node == null || node.isNull()) {
            return;
        }
        String types = "one of " + CREDENTIAL_STORE + ", " + AWS_PARAMETER_STORE;
        if (!(node instanceof ObjectNode sourceNode)) {
            throw new ConfigException("Invalid value at " + path + " in " + source
                + ": expected a mapping with type " + types);
        }
        JsonNode type = sourceNode.get("type");
        if (type == null || type.isNull()) {
            throw new ConfigException(path + ".type is missing in " + source + ": expected "
                + types);
        }
        String typeName = type.isString() ? type.asString() : "";
        if (typeName.equals(ENVIRONMENT)) {
            throw new ConfigException("Invalid value at " + path + ".type in " + source
                + ": credentials.source.type environment was removed in 0.7.0; credentials come"
                + " from the credential store of this system (type credentialStore, the default)"
                + " or AWS Parameter Store (type awsParameterStore); see"
                + " docs/migration-environment-variables.md");
        }
        if (typeName.equals(CREDENTIAL_STORE)) {
            rejectUnknownKeys(sourceNode, path, List.of("type", "prefix"),
                "allowed for type " + CREDENTIAL_STORE + ": type, prefix", source);
            checkText(sourceNode, path, "prefix", ProfileInfo::isValidPrefix,
                ProfileInfo.PREFIX_PATTERN.pattern(), source);
        } else if (typeName.equals(AWS_PARAMETER_STORE)) {
            rejectUnknownKeys(sourceNode, path, List.of("type", "region", "awsProfile"),
                "allowed for type " + AWS_PARAMETER_STORE + ": type, region, awsProfile", source);
            if (!sourceNode.hasNonNull("region")) {
                throw new ConfigException(path + ".region is missing in " + source
                    + ": required for type " + AWS_PARAMETER_STORE);
            }
            checkText(sourceNode, path, "region", SecretSourceConfig::isValidRegion,
                SecretSourceConfig.REGION_PATTERN.pattern(), source);
            checkText(sourceNode, path, "awsProfile", SecretSourceConfig::isValidAwsProfile,
                SecretSourceConfig.AWS_PROFILE_PATTERN.pattern(), source);
        } else {
            throw new ConfigException("Invalid value at " + path + ".type in " + source
                + ": expected " + types);
        }
    }

    /** An optional text value at {@code path.key}; absent and {@code null} are accepted. */
    private static void checkText(ObjectNode node, String path, String key,
        Predicate<String> valid, String rule, Path source) {
        JsonNode value = node.get(key);
        if (value != null && !value.isNull()
            && !(value.isString() && valid.test(value.asString()))) {
            throw new ConfigException("Invalid value at " + path + "." + key + " in " + source
                + ": expected " + rule);
        }
    }

    private static void checkParameters(ObjectNode parameters, String path, Path source) {
        rejectUnknownKeys(parameters, path, PARAMETER_KINDS,
            "allowed: " + String.join(", ", PARAMETER_KINDS), source);
        for (String kind : PARAMETER_KINDS) {
            checkText(parameters, path, kind, ParameterNames::isValidName,
                ParameterNames.NAME_RULE, source);
        }
    }

    private static void rejectUnknownKeys(ObjectNode node, String path, List<String> allowed,
        String hint, Path source) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new ConfigException("Unknown key '" + entry.getKey() + "' at " + path
                    + " in " + source + " (" + hint + ")");
            }
        }
    }

    private static void rejectEmptyListEntries(JsonNode node, String path, Path source) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childPath = path + "[" + i + "]";
                if (node.get(i).isNull()) {
                    throw new ConfigException("Empty list entry at " + childPath + " in " + source);
                }
                rejectEmptyListEntries(node.get(i), childPath, source);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                rejectEmptyListEntries(entry.getValue(),
                    path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), source);
            }
        }
    }

    /**
     * Checks {@code baseUrl} and {@code cli.url} before binding: malformed URLs are rejected
     * without echoing them. A value containing {@code @} (user info, wherever it appears) is
     * reported in {@code problems} and dropped as a whole; for {@code baseUrl}, query and fragment
     * are reported and removed. No later {@code toString()} can reveal the removed parts.
     */
    private static void sanitizeUrls(ObjectNode root, Path source, List<String> problems) {
        JsonNode groups = root.get("groups");
        if (groups == null || !groups.isArray()) {
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            if (!(groups.get(i) instanceof ObjectNode group)) {
                continue;
            }
            String groupPath = "groups[" + i + "]";
            String groupName = nameOf(group, groupPath);
            sanitizeCliUrl(group, groupPath, groupPath + " '" + groupName + "'", source,
                problems);
            sanitizeSoapUrl(group, groupPath, groupPath + " '" + groupName + "'", source,
                problems);
            JsonNode nodes = group.get("nodes");
            if (nodes == null || !nodes.isArray()) {
                continue;
            }
            for (int j = 0; j < nodes.size(); j++) {
                if (!(nodes.get(j) instanceof ObjectNode node)) {
                    continue;
                }
                String nodePath = groupPath + ".nodes[" + j + "]";
                String label = groupName + "/" + nameOf(node, nodePath);
                sanitizeUrl(node, "baseUrl", nodePath + ".baseUrl", label, "baseUrl", true,
                    source, problems);
                sanitizeCliUrl(node, nodePath, label, source, problems);
                sanitizeSoapUrl(node, nodePath, label, source, problems);
            }
        }
    }

    private static void sanitizeCliUrl(ObjectNode owner, String ownerPath, String label,
        Path source, List<String> problems) {
        if (owner.get("cli") instanceof ObjectNode cli) {
            sanitizeUrl(cli, "url", ownerPath + ".cli.url", label, "cli.url", false, source,
                problems);
        }
    }

    /** {@code e2e.soap.baseUrl} (feature 004): like {@code baseUrl}, no user info or query. */
    private static void sanitizeSoapUrl(ObjectNode owner, String ownerPath, String label,
        Path source, List<String> problems) {
        if (owner.get("e2e") instanceof ObjectNode e2e
            && e2e.get("soap") instanceof ObjectNode soap) {
            sanitizeUrl(soap, "baseUrl", ownerPath + ".e2e.soap.baseUrl", label,
                "e2e.soap.baseUrl", true, source, problems);
        }
    }

    private static void sanitizeUrl(ObjectNode parent, String key, String path, String label,
        String displayKey, boolean isBaseUrl, Path source, List<String> problems) {
        JsonNode value = parent.get(key);
        if (value == null || !value.isString()) {
            return;
        }
        String text = value.asString().strip();
        if (text.indexOf('@') >= 0) {
            // Any '@' counts as user info: "https://u:p#x@h" or "https://u:p/q@h" would otherwise
            // parse as a host plus fragment or path and keep the credentials. The value is dropped
            // as a whole, so no part of it can ever be printed.
            problems.add(label + ": " + displayKey + " must not contain user info ('@'); the value"
                + " was ignored. Credentials belong in environment variables");
            parent.remove(key);
            return;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new ConfigException("Invalid URL at " + path + " in " + source);
        }
        if (isBaseUrl && Urls.hasQueryOrFragment(uri)) {
            problems.add(label + ": " + displayKey + " must not contain a query or fragment");
            uri = Urls.withoutQueryAndFragment(uri);
        }
        parent.put(key, uri.toString());
    }

    private static String nameOf(ObjectNode node, String fallback) {
        JsonNode name = node.get("name");
        return name != null && name.isString() ? name.asString() : fallback;
    }

    private static Class<?> targetType(DatabindException e) {
        return e instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null;
    }

    private static String expected(DatabindException e) {
        Class<?> type = targetType(e);
        if (type == null) {
            return "a different value";
        }
        if (type == Duration.class) {
            return "an ISO-8601 duration such as PT5S";
        }
        if (type == VersionSetting.class) {
            return "AUTO, V8_1, V9_X or a full version such as \"8.1.17\"";
        }
        if (type.isEnum()) {
            return "one of " + String.join(", ", Arrays.stream(type.getEnumConstants())
                .map(String::valueOf).toList());
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (type == Integer.class || type == int.class || type == Long.class
            || type == long.class) {
            return "an integer";
        }
        if (type == Path.class) {
            return "a file system path";
        }
        if (type == URI.class) {
            return "a URL";
        }
        if (type == String.class) {
            return "a text value";
        }
        if (List.class.isAssignableFrom(type)) {
            return "a list";
        }
        return "a mapping";
    }

    private static String pathOf(List<JacksonException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : references) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String locationOf(TokenStreamLocation location) {
        if (location == null || location.getLineNr() < 0) {
            return "";
        }
        return " at line " + location.getLineNr() + ", column " + location.getColumnNr();
    }

    /** Accepts only ISO-8601 duration strings such as {@code PT5S}; numbers are rejected. */
    private static final class IsoDurationDeserializer extends ValueDeserializer<Duration> {

        @Override
        public Duration deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                try {
                    return Duration.parse(parser.getString().strip());
                } catch (DateTimeParseException e) {
                    // reported below
                }
            }
            throw MismatchedInputException.from(parser, Duration.class,
                "expected an ISO-8601 duration");
        }
    }

    /**
     * {@code versionLine} (feature 009, research D-2): only strings, see
     * {@link VersionSetting#parse}; a YAML number such as {@code 8.1} is refused.
     */
    private static final class VersionSettingDeserializer
        extends ValueDeserializer<VersionSetting> {

        @Override
        public VersionSetting deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                Optional<VersionSetting> setting = VersionSetting.parse(parser.getString().strip());
                if (setting.isPresent()) {
                    return setting.get();
                }
            }
            throw MismatchedInputException.from(parser, VersionSetting.class,
                "expected a versionLine");
        }
    }

    /** Deserializes paths, expanding a leading {@code ~} to the user's home directory. */
    private static final class HomeExpandingPathDeserializer extends ValueDeserializer<Path> {

        private final Path home;

        HomeExpandingPathDeserializer(Path home) {
            this.home = home;
        }

        @Override
        public Path deserialize(JsonParser parser, DeserializationContext context) {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                throw InvalidFormatException.from(parser, "Expected a path", text, Path.class);
            }
            try {
                return expandHome(text.strip(), home);
            } catch (InvalidPathException e) {
                throw InvalidFormatException.from(parser, "Invalid path", text, Path.class);
            }
        }
    }
}
