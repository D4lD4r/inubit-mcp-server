package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.application.BackupStore;
import de.dadecker.inubit.mcp.application.DeploymentLedger;
import de.dadecker.inubit.mcp.application.PackageWriter;
import de.dadecker.inubit.mcp.domain.model.CapabilityMatrix;
import de.dadecker.inubit.mcp.domain.model.CliClientOptions;
import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.DeployMode;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.MajorMinor;
import de.dadecker.inubit.mcp.domain.model.NodeVersionState;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks a loaded configuration and its resolved credentials at startup (contracts/configuration.md
 * → Startup validation outcomes). All errors and warnings are collected; messages name keys,
 * servers and variables, never credential values.
 */
public final class ConfigValidator {

    /** Hex SHA-256 fingerprint, with or without colons. */
    static final Pattern PIN_PATTERN =
        Pattern.compile("^(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$|^[0-9A-Fa-f]{64}$");

    /** Values that are inserted into a StartCLI {@code --execCommand} (research R-11). */
    /** Same rule as {@code CliCommand.VALUE} (research R-11): no leading '-', no quotes. */
    static final Pattern CLI_VALUE_PATTERN =
        Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$");

    /** Upper bound of {@code confirmationTtl} (Phase 6 review W5). */
    static final Duration MAX_CONFIRMATION_TTL = Duration.ofHours(1);

    /**
     * The Java major each INUBIT version ships with (FR-006); other versions have no documented
     * JDK and are not checked.
     */
    private static final Map<MajorMinor, Integer> SHIPPED_JAVA = Map.of(
        new MajorMinor(8, 1), 17,
        new MajorMinor(9, 0), 21);

    /** Upper bound of {@code defaults.deployConfirmationTtl} (feature 005, research D-2). */
    static final Duration MAX_DEPLOY_CONFIRMATION_TTL = Duration.ofHours(2);

    /**
     * The username length the Windows line-length check assumes (feature 013); the username is a
     * credential, read on use, where the call checks the real one.
     */
    private static final int ASSUMED_USERNAME_LENGTH = 64;

    private final Predicate<Path> exists;
    private final Map<String, String> environment;
    private final boolean windows;
    private final Path tempDirectory;
    private final Function<Path, List<LoadedConfig>> otherProfiles;
    private final WorkspaceDirectory.Preparer workspaces;
    private final StartCliHost host;
    private final Function<Path, Optional<String>> releaseReader;
    private final Optional<ServerPaths> serverPaths;

    /**
     * The paths of the running server that the knowledge directory must keep clear of (feature
     * 019, research D-2).
     *
     * @param home the user's home directory, below which {@code ~/.inubit-mcp} lies
     * @param defaultConfigDirectory {@link ConfigLoader#defaultDirectory()}
     */
    private record ServerPaths(Path home, Path defaultConfigDirectory) {
    }

    /**
     * @param exists        file-system check, injectable for tests
     * @param environment   the process environment (for the {@code JAVA_HOME} fallback and the
     *     names of leftover credential variables; no credential value is read from it)
     * @param windows       whether the server runs on Windows, where StartCLI receives its
     *     values through the command interpreter (feature 013)
     * @param tempDirectory {@code java.io.tmpdir}, below which the inventory exports are written
     *     (its path is passed to StartCLI, review I9)
     * @param otherProfiles the other profile files of the default configuration directory, given
     *     the file being checked ({@link ConfigLoader#otherProfiles}); compared best effort for a
     *     shared audit directory, credential prefix or profile name (002 T031)
     * @param workspaces    creates and checks the workspace directory ({@link
     *     WorkspaceDirectory#prepare} with the private files of the host, built by the launcher;
     *     feature 013 T026)
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles,
        WorkspaceDirectory.Preparer workspaces) {
        this(exists, environment, windows, tempDirectory, otherProfiles, workspaces,
            StartCliHost.unchecked());
    }

    /**
     * @param host what the Windows host reported before StartCLI can start ({@code
     *     --check-config}; feature 013, contracts/configuration-delta.md); ignored off Windows
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles,
        WorkspaceDirectory.Preparer workspaces, StartCliHost host) {
        this(exists, environment, windows, tempDirectory, otherProfiles, workspaces, host,
            JdkRelease::read);
    }

    /**
     * @param releaseReader reads a JDK's {@code release} file for its Java major ({@link
     *     JdkRelease}, feature 017); empty when it cannot be read
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles,
        WorkspaceDirectory.Preparer workspaces, StartCliHost host,
        Function<Path, Optional<String>> releaseReader) {
        this(exists, environment, windows, tempDirectory, otherProfiles, workspaces, host,
            releaseReader, Optional.empty());
    }

    private ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles,
        WorkspaceDirectory.Preparer workspaces, StartCliHost host,
        Function<Path, Optional<String>> releaseReader, Optional<ServerPaths> serverPaths) {
        this.serverPaths = serverPaths;
        this.releaseReader = Objects.requireNonNull(releaseReader, "releaseReader");
        this.host = Objects.requireNonNull(host, "host");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.tempDirectory = tempDirectory;
        this.exists = exists;
        this.environment = Map.copyOf(environment);
        this.windows = windows;
        this.otherProfiles = Objects.requireNonNull(otherProfiles, "otherProfiles");
    }

    /**
     * This validator, also comparing the knowledge directory with the per-profile directories
     * below {@code home}, {@code ~/.inubit-mcp} itself and {@code defaultConfigDirectory}
     * (feature 019, FR-002), and with the configuration directory and the folder of the
     * configuration file (which may hold {@code ~/.inubit-mcp/<profile>}, where the knowledge
     * directory may lie). Without it only the configured directories of this and the other
     * profiles are compared.
     */
    public ConfigValidator withServerPaths(Path home, Path defaultConfigDirectory) {
        return new ConfigValidator(exists, environment, windows, tempDirectory, otherProfiles,
            workspaces, host, releaseReader, Optional.of(new ServerPaths(
                Objects.requireNonNull(home, "home"),
                Objects.requireNonNull(defaultConfigDirectory, "defaultConfigDirectory"))));
    }

    /**
     * Checks {@code loaded}; the messages use the profile's terminology (FR-007), or the default
     * names while the terminology itself is invalid.
     */
    public ValidationReport validate(LoadedConfig loaded) {
        ProfileConfig config = loaded.config();
        Terminology terms = config.terminology().effectiveOrDefault();
        Findings findings = new Findings(terms);
        for (String key : loaded.credentialKeys()) {
            // 002 research D-6: the entries under the profile's effective prefix
            // feature 011 (research R-10): the next step names both sources
            findings.termError("Credential key '%s' is not allowed in the configuration file;"
                + " store the value in the credential store (service inubit-mcp, account"
                + " %s_%s_USERNAME / _PASSWORD) or in AWS Parameter Store and name it in"
                + " credentials.parameters", key, config.credentialPrefix(), variablePart(terms));
        }
        loaded.urlProblems().forEach(findings::error);
        checkProfile(config, findings);
        Optional<WorkspaceDirectory.Result> workspace = checkWorkspace(config, findings);
        checkStructure(config, findings);
        checkPinPerServer(config, findings);
        checkResultLimits(config.resultLimits(), findings);
        checkDeployConfirmationTtl(config.defaults(), findings);
        for (EffectiveNodeConfig server : config.resolvableNodes()) {
            checkServer(server, findings);
            checkDevelopment(server, findings);
            checkParameterNames(server, findings);
            checkNodeJdk(server, config.defaults(), findings);
        }
        checkCliClients(config.defaults(), findings);
        checkIgnoredParameterNames(config, findings);
        checkOneDevelopmentNodePerGroup(config, findings);
        checkRepositorySwitch(config, findings);
        checkArchivesOptIn(config, findings);
        checkQueueEntryReadOptIn(config, findings);
        checkE2eNodeNames(config, findings);
        checkChain(config, findings);
        boolean cliConfigured = !config.defaults().cliClients().isEmpty()
            || config.resolvableNodes().stream().anyMatch(EffectiveNodeConfig::cliConfigured);
        if (windows && cliConfigured) {
            checkStartCliHost(findings);
        }
        Path exportRoot = CliPaths.forCommand(tempDirectory, windows);
        if (cliConfigured && !CliPaths.exportRootUsable(exportRoot, windows)) {
            findings.warning("java.io.tmpdir " + exportRoot + " cannot be passed to StartCLI"
                + " (it must match " + CliPaths.rule(windows).pattern() + "); inventory exports"
                + " report CLI_UNAVAILABLE. Start the MCP server with"
                + " -Djava.io.tmpdir=<" + CliPaths.tmpdirHint(windows) + ">");
        }
        if (cliConfigured && config.resolvableNodes().stream()
            .allMatch(node -> node.inventory().owner().isEmpty())) {
            // research D-10: once per profile, not per node
            findings.termWarning("No inventory.owner is set (in defaults, for any {group} or for"
                + " any {node}) although a CLI is configured; list_inventory and get_inventory_item"
                + " return NOT_CONFIGURED for every {node} without an owner");
        } else if (cliConfigured) {
            // 002 review U6: name the nodes the inventory tools will refuse
            List<String> withoutOwner = config.resolvableNodes().stream()
                .filter(node -> node.inventory().owner().isEmpty())
                .map(node -> node.id().value())
                .toList();
            if (!withoutOwner.isEmpty()) {
                findings.termWarning("{Nodes} without inventory.owner (list_inventory and"
                    + " get_inventory_item return NOT_CONFIGURED for them): %s",
                    String.join(", ", withoutOwner));
            }
        }
        List<LoadedConfig> others = otherProfiles.apply(loaded.source());
        checkOtherProfiles(loaded, others, findings);
        checkKnowledge(loaded, others, findings);
        checkEntryNameCollisions(config, findings);
        // feature 011 (FR-003): variables that earlier versions read are reported, names only
        LeftoverVariables.warning(environment, config).ifPresent(findings::warning);
        return new ValidationReport(findings.errors, findings.warnings, workspace);
    }

    /**
     * The other profile files of the default configuration directory (002 T031, spec edge cases;
     * best effort: profiles elsewhere are not seen):
     *
     * <ul>
     *   <li>an <b>error</b> if a file of <em>another</em> profile (another profile name) derives
     *       a credential store entry name that this profile derives as well, by nested prefixes
     *       (e.g. {@code INUBIT_ACME} + group {@code 2-test} and {@code INUBIT_ACME_2} + group
     *       {@code test}) or by an equal prefix with equal group names (002 US2 review P1,
     *       re-review N1): two customers would read the same entry. Only names are printed; only
     *       credential store nodes derive names ({@link ProfileConfig#credentialEntryNames()},
     *       research R-13), so two profiles may read the same Parameter Store names;
     *   <li>one <b>warning</b> per file that has the same profile name (a copy, which may start;
     *       shared entry names are listed in the warning then), the same effective credential
     *       prefix (if both profiles have credential store nodes) or the same audit directory.
     * </ul>
     *
     * A profile name read from another file is printed only if valid (review P3a).
     */
    private void checkOtherProfiles(LoadedConfig loaded, List<LoadedConfig> others,
        Findings findings) {
        ProfileConfig config = loaded.config();
        Set<String> ownVariables = config.credentialEntryNames();
        for (LoadedConfig other : others) {
            ProfileConfig otherConfig = other.config();
            String otherName = ProfileInfo.displayName(otherConfig.profile().name());
            String otherFile = "The profile file " + other.source() + " (profile "
                + (otherName.equals(ProfileInfo.INVALID_NAME) ? otherName : "'" + otherName + "'")
                + ")";
            Set<String> otherVariables = otherConfig.credentialEntryNames();
            Set<String> sharedVariables = new TreeSet<>(otherVariables);
            sharedVariables.retainAll(ownVariables);
            String name = config.profile().name();
            boolean copy = !name.isEmpty() && name.equals(otherConfig.profile().name());
            if (!sharedVariables.isEmpty() && !copy) {
                findings.error(otherFile + " derives the same credential store entry names as"
                    + " this profile, so both would read them: " + String.join(", ", sharedVariables)
                    + ". Set another credentials.prefix for one of them or rename the"
                    + " group or node");
            }
            List<String> shared = new ArrayList<>();
            if (copy) {
                String shown = ProfileInfo.isValidName(name) ? "'" + name + "'"
                    : ProfileInfo.INVALID_NAME;
                shared.add("the profile name " + shown
                    + " (profile names must be unique per workstation)");
            }
            // T007 review r1: a profile without credential store nodes reads no entries
            if (config.credentialPrefix().equals(otherConfig.credentialPrefix())
                && !ownVariables.isEmpty() && !otherVariables.isEmpty()) {
                shared.add("the credential entry prefix " + config.credentialPrefix()
                    + " (both read the same entries)");
            }
            if (copy && !sharedVariables.isEmpty()
                && !config.credentialPrefix().equals(otherConfig.credentialPrefix())) {
                shared.add("the credential store entry names " + String.join(", ",
                    sharedVariables) + " (both read them)");
            }
            // feature 003 FR-003: two profiles never share a workspace; a copy of the same
            // profile (same name) may start and shares it by design (002 re-review N1)
            Path workspace = canonical(config.workspace());
            Path otherWorkspace = canonical(otherConfig.workspace());
            boolean nested = workspace.startsWith(otherWorkspace)
                || otherWorkspace.startsWith(workspace);
            if (nested && copy) {
                shared.add("the workspace " + config.workspace() + " (one history for both)");
            } else if (nested) {
                String profile = ProfileInfo.isValidName(name) ? "'" + name + "'"
                    : ProfileInfo.INVALID_NAME;
                Path shown = config.workspace().toAbsolutePath().normalize();
                Path otherShown = otherConfig.workspace().toAbsolutePath().normalize();
                String relation = workspace.equals(otherWorkspace) ? "is also the workspace of"
                    : workspace.startsWith(otherWorkspace) ? "is inside the workspace "
                        + otherShown + " of" : "contains the workspace " + otherShown + " of";
                findings.error("The workspace " + shown + " of profile " + profile + " "
                    + relation + " the profile file " + other.source() + " (profile "
                    + (otherName.equals(ProfileInfo.INVALID_NAME) ? otherName : "'" + otherName
                        + "'") + "); profiles need separate workspaces (setting workspace)");
            }
            if (config.auditDirectory().toAbsolutePath().normalize()
                .equals(otherConfig.auditDirectory().toAbsolutePath().normalize())) {
                shared.add("the audit directory " + config.auditDirectory()
                    + " (their audit records mix)");
            }
            if (!shared.isEmpty()) {
                findings.warning(otherFile + " has " + joined(shared) + "; profiles that run side"
                    + " by side need their own (credentials.prefix, auditDirectory)");
            }
        }
    }

    /**
     * A directory the knowledge directory keeps clear of (feature 019, research D-2).
     *
     * @param what      how the error names it (contracts/configuration-delta.md)
     * @param path      as configured or derived
     * @param mayHoldIt whether the knowledge directory may lie inside it ({@code ~/.inubit-mcp})
     * @param mayHoldOwnData whether the knowledge directory may lie inside it when this profile's
     *                       data directory does too (the configuration roots, e.g. a
     *                       configuration file in the home directory)
     */
    private record Forbidden(String what, Path path, boolean mayHoldIt, boolean mayHoldOwnData) {

        Forbidden(String what, Path path) {
            this(what, path, false, false);
        }
    }

    /**
     * Feature 019 (FR-002, research D-2): the knowledge directory is absolute and never equals,
     * lies inside or contains a directory of this profile, of the server or of another profile of
     * the default configuration directory. Lying inside this profile's data directory {@code
     * ~/.inubit-mcp/<profile>} is allowed (it is the default), also where that directory itself
     * lies inside a configuration root (a configuration file in the home directory), but not
     * inside a workspace or audit directory. Paths are
     * compared {@linkplain #canonical canonically}; a copy of the same profile is not compared.
     */
    private void checkKnowledge(LoadedConfig loaded, List<LoadedConfig> others,
        Findings findings) {
        ProfileConfig config = loaded.config();
        Path directory = config.knowledge().directory();
        if (!directory.isAbsolute()) {
            findings.error("knowledge.directory " + directory + " must be absolute after"
                + " expansion of ~ (e.g. ~/Notes/inubit/acme)");
            return;
        }
        String name = config.profile().name();
        boolean ownName = ProfileInfo.isValidName(name) && !ProfileInfo.isReservedName(name);
        List<Forbidden> forbidden = new ArrayList<>();
        forbidden.add(new Forbidden("workspace", config.workspace()));
        forbidden.add(new Forbidden("audit directory", config.auditDirectory()));
        Optional<Path> dataDirectory = Optional.empty();
        if (serverPaths.isPresent()) {
            Path home = serverPaths.get().home();
            if (ownName) {
                dataDirectory = Optional.of(dataDirectory(home, name));
                forbidden.add(new Forbidden("backup directory",
                    BackupStore.defaultRoot(home, name)));
                forbidden.add(new Forbidden("deployment ledger directory",
                    DeploymentLedger.defaultDirectory(home, name)));
                forbidden.add(new Forbidden("package directory",
                    PackageWriter.defaultDirectory(home, name)));
            }
            forbidden.add(new Forbidden("server data directory ~/.inubit-mcp",
                home.resolve(".inubit-mcp"), true, false));
            List<Path> roots = new ConfigLocation(serverPaths.get().defaultConfigDirectory(),
                loaded.source()).roots();
            forbidden.add(new Forbidden("configuration directory", roots.get(0), false, true));
            if (roots.size() > 1) {
                forbidden.add(new Forbidden("folder of the configuration file", roots.get(1),
                    false, true));
            }
        }
        for (LoadedConfig other : others) {
            ProfileConfig otherConfig = other.config();
            String otherName = otherConfig.profile().name();
            if (!name.isEmpty() && name.equals(otherName)) {
                continue;
            }
            String profile = ProfileInfo.isValidName(otherName) ? "'" + otherName + "'"
                : ProfileInfo.INVALID_NAME;
            String file = " (" + other.source() + ")";
            if (serverPaths.isPresent() && ProfileInfo.isValidName(otherName)
                && !ProfileInfo.isReservedName(otherName)) {
                forbidden.add(new Forbidden("data directory of profile " + profile,
                    dataDirectory(serverPaths.get().home(), otherName)));
            }
            forbidden.add(new Forbidden("workspace of profile " + profile + file,
                otherConfig.workspace()));
            forbidden.add(new Forbidden("audit directory of profile " + profile + file,
                otherConfig.auditDirectory()));
            forbidden.add(new Forbidden("knowledge directory of profile " + profile + file,
                otherConfig.knowledge().directory()));
        }
        Path knowledge = canonical(directory);
        Optional<Path> ownData = dataDirectory.map(ConfigValidator::canonical)
            .filter(knowledge::startsWith);
        Path shown = directory.toAbsolutePath().normalize();
        for (Forbidden candidate : forbidden) {
            if (!candidate.path().isAbsolute()) {
                continue;
            }
            Path path = canonical(candidate.path());
            Path otherShown = candidate.path().toAbsolutePath().normalize();
            String prefix = "The knowledge directory " + shown + " ";
            if (knowledge.equals(path)) {
                findings.error(prefix + "is the same directory as the " + candidate.what() + " "
                    + otherShown + "; set knowledge.directory to a folder of its own");
            } else if (knowledge.startsWith(path) && !candidate.mayHoldIt()
                && !(candidate.mayHoldOwnData()
                    && ownData.filter(own -> own.startsWith(path)).isPresent())) {
                findings.error(prefix + "is inside the " + candidate.what() + " " + otherShown
                    + "; set knowledge.directory to a folder outside it");
            } else if (path.startsWith(knowledge)) {
                findings.error(prefix + "contains the " + candidate.what() + " " + otherShown
                    + "; set knowledge.directory to a folder that does not contain it");
            }
        }
    }

    private static Path dataDirectory(Path home, String profile) {
        return home.resolve(".inubit-mcp").resolve(profile);
    }

    /**
     * {@code path} as compared for FR-003 (review M2): the real path of its nearest existing
     * ancestor (symbolic links resolved) with the rest appended; lower-case if that file system
     * ignores case.
     */
    static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return absolute;
        }
        Path canonical = real.resolve(existing.relativize(absolute)).normalize();
        return ignoresCase(real) ? Path.of(canonical.toString().toLowerCase(Locale.ROOT))
            : canonical;
    }

    /** True if {@code existing} is also found under its name in the other case. */
    private static boolean ignoresCase(Path existing) {
        for (Path candidate = existing; candidate != null && candidate.getFileName() != null;
            candidate = candidate.getParent()) {
            String name = candidate.getFileName().toString();
            String swapped = name.equals(name.toUpperCase(Locale.ROOT))
                ? name.toLowerCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT);
            if (!swapped.equals(name)) {
                Path other = candidate.resolveSibling(swapped);
                try {
                    return Files.exists(other) && Files.isSameFile(candidate, other);
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * The workspace of feature 003 (FR-001, FR-002): absolute after {@code ~} expansion, then
     * created owner-only if missing and checked to be a readable and writable directory. Nothing
     * is created for an invalid or reserved profile name (the server does not start with it, and
     * the default path would not belong to a real profile).
     *
     * @return the outcome (kept in the report for the configuration summary), or empty if the
     *     workspace was not prepared
     */
    private Optional<WorkspaceDirectory.Result> checkWorkspace(ProfileConfig config,
        Findings findings) {
        Path workspace = config.workspace();
        if (!workspace.isAbsolute()) {
            findings.error("workspace " + workspace + " must be absolute after expansion of ~"
                + " (e.g. ~/work/acme-inubit or /srv/inubit/acme)");
            return Optional.empty();
        }
        String name = config.profile().name();
        if (!ProfileInfo.isValidName(name) || ProfileInfo.isReservedName(name)) {
            return Optional.empty();
        }
        WorkspaceDirectory.Result result = workspaces.prepare(workspace.normalize());
        if (result instanceof WorkspaceDirectory.Unusable unusable) {
            findings.error(unusable.problem());
        }
        return Optional.of(result);
    }

    /** Feature 005 (research D-2): positive and at most {@link #MAX_DEPLOY_CONFIRMATION_TTL}. */
    private static void checkDeployConfirmationTtl(Defaults defaults, Findings findings) {
        defaults.deployConfirmationTtl().ifPresent(ttl -> {
            if (ttl.isZero() || ttl.isNegative()) {
                findings.error("defaults: deployConfirmationTtl must be positive (default PT30M)");
            } else if (ttl.compareTo(MAX_DEPLOY_CONFIRMATION_TTL) > 0) {
                findings.error("defaults: deployConfirmationTtl must be at most PT2H (default"
                    + " PT30M)");
            }
        });
    }

    /** {@code a}, {@code a and b}, {@code a, b and c}. */
    private static String joined(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and "
            + parts.get(parts.size() - 1);
    }

    /** The {@code profile}, {@code terminology} and {@code credentials} sections (002). */
    private static void checkProfile(ProfileConfig config, Findings findings) {
        ProfileSection profile = config.profile();
        if (profile.name().isEmpty()) {
            findings.error("profile.name is required: every configuration file starts with a"
                + " profile block (profile: {name: <name>}), name matching "
                + ProfileInfo.NAME_RULE);
        } else if (ProfileInfo.isReservedName(profile.name())) {
            // 002 US2 review P3b
            findings.error("profile.name '" + profile.name() + "' is reserved: its default audit"
                + " directory would lie inside ~/.inubit-mcp/audit, the audit directory of"
                + " feature 001; choose another name");
        } else if (!ProfileInfo.isValidName(profile.name())) {
            findings.error("Invalid profile.name '" + profile.name() + "': expected "
                + ProfileInfo.NAME_RULE);
        }
        profile.description()
            .filter(description -> !ProfileInfo.isValidDescription(description))
            .ifPresent(description -> findings.error(ProfileInfo.DESCRIPTION_RULE));
        checkTerminology(config.terminology(), findings);
        CredentialsConfig credentials = config.credentials();
        credentials.prefix()
            .filter(prefix -> !ProfileInfo.isValidPrefix(prefix))
            .ifPresent(prefix -> findings.error("Invalid credentials.prefix: expected "
                + ProfileInfo.PREFIX_PATTERN.pattern()));
        credentials.envPrefix()
            .filter(prefix -> !ProfileInfo.isValidPrefix(prefix))
            .ifPresent(prefix -> findings.error("Invalid credentials.envPrefix: expected "
                + ProfileInfo.PREFIX_PATTERN.pattern()));
        // feature 011 (FR-005, research R-6): envPrefix is the deprecated name of prefix
        if (credentials.envPrefix().isPresent()) {
            if (credentials.prefix().isPresent()
                && !credentials.prefix().equals(credentials.envPrefix())) {
                findings.error("credentials.prefix and credentials.envPrefix differ; keep only"
                    + " credentials.prefix");
            } else {
                findings.warning("credentials.envPrefix is deprecated; rename it to"
                    + " credentials.prefix");
            }
        }
        // feature 010 (US1-3): the profile-wide prefix is the default of every source without one
        Optional<String> profilePrefix = credentials.profilePrefix();
        if (credentials.source().orElse(null)
            instanceof SecretSourceConfig.SystemStore store
            && store.prefix().isPresent() && profilePrefix.isPresent()
            && !store.prefix().equals(profilePrefix)) {
            findings.error("credentials.source.prefix and " + credentials.profilePrefixKey()
                + " differ; set only one of them or make them equal");
        }
    }

    private static void checkTerminology(TerminologyConfig terminology, Findings findings) {
        checkLevel("group", terminology.group(), findings);
        checkLevel("node", terminology.node(), findings);
        checkDistinct("singular", terminology.groupSingular(), terminology.nodeSingular(),
            findings);
        checkDistinct("plural", terminology.groupPlural(), terminology.nodePlural(), findings);
    }

    private static void checkLevel(String level, Optional<TerminologyConfig.Level> given,
        Findings findings) {
        given.ifPresent(names -> {
            checkTerm("terminology." + level + ".singular", names.singular(), findings);
            checkTerm("terminology." + level + ".plural", names.plural(), findings);
        });
    }

    /** Group and node names must differ; invalid names are reported by {@link #checkLevel}. */
    private static void checkDistinct(String form, String group, String node,
        Findings findings) {
        if (Terminology.isValidTerm(group) && Terminology.isValidTerm(node)
            && Terminology.sameTerm(group, node)) {
            findings.error("terminology.group." + form + " and terminology.node." + form
                + " must differ (case-insensitive): both are '" + group + "'");
        }
    }

    private static void checkTerm(String key, Optional<String> term, Findings findings) {
        if (term.isEmpty()) {
            findings.error(key + " is missing: a given terminology level needs both singular"
                + " and plural");
        } else if (!Terminology.isValidTerm(term.get())) {
            findings.error("Invalid " + key + " '" + term.get() + "': expected "
                + Terminology.TERM_PATTERN.pattern());
        }
    }

    /** {@code <GROUP>[_<NODE>]} in the display names, e.g. {@code <UMGEBUNG>[_<KNOTEN>]}. */
    static String variablePart(Terminology terms) {
        return "<" + variableName(terms.groupSingular()) + ">[_<"
            + variableName(terms.nodeSingular()) + ">]";
    }

    private static String variableName(String term) {
        return term.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "_");
    }

    private static void checkStructure(ProfileConfig config, Findings findings) {
        if (config.groups().isEmpty()) {
            findings.termError("The configuration must define at least one {group}");
        }
        Set<String> stageNames = new HashSet<>();
        for (int i = 0; i < config.groups().size(); i++) {
            GroupConfig stage = config.groups().get(i);
            // the group in a message: inside a sentence ({group} 'x') and at its start
            String stageLabel;
            String stageLabelStart;
            if (stage.name().isEmpty()) {
                findings.error("groups[" + i + "]: name is missing");
                stageLabel = "groups[" + i + "]";
                stageLabelStart = stageLabel;
            } else {
                if (!GroupId.isValidName(stage.name())) {
                    findings.termError("Invalid {group} name '%s': expected %s", stage.name(),
                        GroupId.NAME_PATTERN.pattern());
                }
                stageLabel = findings.terms.render("{group} '") + stage.name() + "'";
                stageLabelStart = findings.terms.render("{Group} '") + stage.name() + "'";
            }
            if (!stage.name().isEmpty() && !stageNames.add(stage.name())) {
                findings.termError("Duplicate {group} name '%s'", stage.name());
            }
            if (stage.nodes().isEmpty()) {
                findings.termError("{Group} '%s' must have at least one {node}", stage.name());
            }
            if (stage.cli().url().isPresent() && stage.nodes().size() > 1) {
                findings.termWarning("%s: cli.url is set for %s {nodes}, so all of them use the"
                    + " same StartCLI endpoint; set cli.url per {node} instead", stageLabelStart,
                    stage.nodes().size());
            }
            Set<String> serverNames = new HashSet<>();
            for (int j = 0; j < stage.nodes().size(); j++) {
                NodeConfig server = stage.nodes().get(j);
                if (server.name().isEmpty()) {
                    findings.error("groups[" + i + "].nodes[" + j + "]: name is missing");
                } else if (!GroupId.isValidName(server.name())) {
                    findings.termError("Invalid {node} name '%s' in %s: expected %s",
                        server.name(), stageLabel, GroupId.NAME_PATTERN.pattern());
                }
                if (!server.name().isEmpty() && !serverNames.add(server.name())) {
                    findings.termError("Duplicate {node} name '%s' in %s", server.name(),
                        stageLabel);
                }
                if (server.baseUrl().isEmpty()) {
                    boolean validNames = GroupId.isValidName(stage.name())
                        && GroupId.isValidName(server.name());
                    String label = validNames
                        ? stage.name() + "/" + server.name()
                        : "groups[" + i + "].nodes[" + j + "]";
                    findings.error(label + ": baseUrl is missing");
                }
            }
        }
    }

    /**
     * Feature 014 (FR-004, research R-3): {@code tls.pinPerServer} rules V-1, V-3 … V-6, V-10 and
     * V-11, checked on the values as written, not the effective ones, because the rules are about
     * where a value is set — except the https test of V-6, which looks at every address the
     * server is reached at, inherited ones included (T029b). YAML aliases are resolved before
     * binding, so an alias counts as written there.
     */
    private static void checkPinPerServer(ProfileConfig config, Findings findings) {
        for (GroupConfig stage : config.groups()) {
            TlsConfig stageTls = stage.tls();
            if (stageTls.marksStage() && stageTls.pinnedCertificateSha256().isPresent()) {
                findings.termError("{group} '%s': tls.pinnedCertificateSha256 is not allowed with"
                    + " tls.pinPerServer; set it on each {node}", stage.name());
            }
            if (stageTls.marksStage() && stageTls.trustStore().isEmpty()) {
                findings.termError("{group} '%s': tls.pinPerServer needs tls.trustStore on the"
                    + " {group}", stage.name());
            }
            for (NodeConfig server : stage.nodes()) {
                String id = stage.name() + "/" + server.name();
                TlsConfig serverTls = server.tls();
                if (serverTls.pinPerServer().isPresent()) {
                    findings.termError("%s: tls.pinPerServer is only valid on the {group}", id);
                }
                if (!stageTls.marksStage()) {
                    continue;
                }
                if (serverTls.trustStore().isPresent()) {
                    findings.termError("%s: tls.trustStore is not allowed on this {node} of {group}"
                        + " '%s' with tls.pinPerServer; set it on the {group}", id, stage.name());
                }
                // cli.url defaults to baseUrl (EffectiveNodeConfig), so baseUrl covers that case
                boolean https = Stream.of(server.baseUrl(),
                        server.cli().url().or(stage.cli()::url),
                        server.e2e().soap().baseUrl().or(stage.e2e().soap()::baseUrl))
                    .flatMap(Optional::stream)
                    .anyMatch(url -> "https".equalsIgnoreCase(url.getScheme()));
                if (https && serverTls.pinnedCertificateSha256().isEmpty()) {
                    findings.termError("%s: tls.pinPerServer on {group} '%s' needs"
                        + " tls.pinnedCertificateSha256 on this {node}", id, stage.name());
                }
                if (stage.nodes().size() < 2) {
                    continue;
                }
                if (stage.cli().url().isPresent() && server.cli().url().isEmpty()) {
                    findings.termError("%s: cli.url is inherited from {group} '%s' with"
                        + " tls.pinPerServer; set cli.url on each {node}", id, stage.name());
                }
                if (stage.e2e().soap().baseUrl().isPresent()
                    && server.e2e().soap().baseUrl().isEmpty()) {
                    findings.termError("%s: e2e.soap.baseUrl is inherited from {group} '%s' with"
                        + " tls.pinPerServer; set e2e.soap.baseUrl on each {node}", id,
                        stage.name());
                }
            }
        }
    }

    private static void checkResultLimits(ResultLimits limits, Findings findings) {
        if (limits.maxItems() < 1) {
            findings.error("resultLimits.maxItems must be at least 1");
        }
        if (limits.maxChars() < ResultLimits.MIN_MAX_CHARS) {
            findings.error("resultLimits.maxChars must be at least " + ResultLimits.MIN_MAX_CHARS);
        }
    }

    private void checkServer(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        checkBaseUrl(server, findings);
        if (server.production() && server.write().confirmation() == ConfirmationMode.CLIENT) {
            findings.termError("%s: write.confirmation CLIENT is not allowed in production"
                + " {groups}; use SERVER", id);
        }
        checkCliUrl(server, findings);
        checkTls(server, findings);
        if (server.cliConfigured()) {
            checkCli(server, findings);
        } else if (CapabilityMatrix.production().isCliOnly(
            NodeVersionState.initial(server.versionLine()).effectiveVersion())) {
            // feature 012 (FR-014): the server starts, the node answers CLI_UNAVAILABLE
            findings.warning(id + ": INUBIT 6.1 is reached through StartCLI only, but no cliHome"
                + " is configured; its capabilities report CLI_UNAVAILABLE");
        }
        if (server.versionLine().equals(VersionSetting.V9_X)) {
            // feature 017 (FR-011, research D-9); writes refused, reads unverified (009 D-13)
            findings.warning("versionLine V9_X of " + id + " does not select 9.0 support; use"
                + " V9_0 (INUBIT 9.0.x) or AUTO");
        }
        // FR-010: the servlet address INUBIT 9.0.2 removed, for a pinned node only
        server.cliForSetting().servletAddressWarning(server.id()).ifPresent(findings::warning);
        server.inventory().owner()
            .filter(owner -> !CLI_VALUE_PATTERN.matcher(owner).matches())
            .ifPresent(owner -> findings.error(id + ": inventory.owner must match "
                + CLI_VALUE_PATTERN.pattern()));
        checkPositive(id, "timeout", server.timeout(), findings);
        checkPositive(id, "cliTimeout", server.cliTimeout(), findings);
        checkPositive(id, "cliExportTimeout", server.cliExportTimeout(), findings);
        checkPositive(id, "hangingThreshold", server.hangingThreshold(), findings);
        checkPositive(id, "confirmationTtl", server.confirmationTtl(), findings);
        if (server.confirmationTtl().compareTo(MAX_CONFIRMATION_TTL) > 0) {
            // review W5: a confirmation must refer to a recent preview
            findings.error(id + ": confirmationTtl must be at most PT1H");
        }
        checkPositive(id, "inventory.cacheTtl", server.inventory().cacheTtl(), findings);
    }

    /**
     * The development settings of feature 004 (research D-1, contracts/configuration-delta.md):
     * neither {@code development.enabled} nor an {@code e2eTests} other than {@code FORBIDDEN} on
     * a production {@code group} or node; {@code e2eTests} other than {@code FORBIDDEN} needs
     * {@code e2e.soap.baseUrl}, which must be an absolute {@code https} URL ({@code http} is a
     * warning: development stages may run plain HTTP) — but never together with the e2e basic
     * authentication variables, which must not travel in clear text (review m-c).
     */
    private void checkDevelopment(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        EffectiveNodeConfig.Development development = server.development();
        if (server.production() && development.enabled()) {
            findings.termError("%s: development.enabled is not allowed in production {groups};"
                + " development tools write to INUBIT and are never offered there", id);
        }
        E2ePolicy e2eTests = development.e2eTests();
        if (server.production() && e2eTests != E2ePolicy.FORBIDDEN) {
            findings.termError("%s: e2eTests %s is not allowed in production {groups}; use"
                + " FORBIDDEN (the default)", id, e2eTests);
        }
        if (e2eTests != E2ePolicy.FORBIDDEN && development.soapBaseUrl().isEmpty()) {
            findings.termError("%s: e2eTests %s needs e2e.soap.baseUrl (for the {group} or the"
                + " {node})", id, e2eTests);
        }
        development.soapBaseUrl().ifPresent(url -> {
            String scheme = url.getScheme() == null ? ""
                : url.getScheme().toLowerCase(Locale.ROOT);
            if (url.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
                findings.error(id + ": e2e.soap.baseUrl must be an absolute https:// URL"
                    + " (http:// is accepted with a warning)");
            } else if (scheme.equals("http")) {
                // feature 011 (research R-11): credential store entries are checked when read
                List<String> credentials = e2eParameterNames(server);
                if (e2eTests != E2ePolicy.FORBIDDEN && !credentials.isEmpty()) {
                    findings.error(id + ": e2e.soap.baseUrl uses http:// while the e2e basic"
                        + " authentication is set (" + String.join(", ", credentials) + "); the"
                        + " credentials would travel in clear text — use https:// or unset them");
                } else {
                    findings.warning(id + ": e2e.soap.baseUrl uses http://; SOAP test messages"
                        + " are sent unencrypted");
                }
            }
        });
    }

    /** Feature 010 (research R-6): the configured e2e Parameter Store names of an AWS node. */
    private static List<String> e2eParameterNames(EffectiveNodeConfig server) {
        return server.secretPlan().names()
            .map(names -> Stream.of(names.e2eUsername(), names.e2ePassword())
                .flatMap(Optional::stream).toList())
            .orElse(List.of());
    }

    /**
     * The Parameter Store names of an AWS node (feature 010, research R-6,
     * contracts/configuration-delta.md): {@code username} and {@code password} are required, the
     * e2e names come in pairs, and a {@code trustStorePassword} name follows the rules of the
     * trust store password variable ({@link #checkCredentials}).
     */
    private static void checkParameterNames(EffectiveNodeConfig server, Findings findings) {
        SecretPlan plan = server.secretPlan();
        if (plan.names().isEmpty()) {
            return;
        }
        ParameterNames names = plan.names().get();
        String id = server.id().value();
        String where = plan.origin() == SecretPlan.Origin.NODE
            ? "on the {node}; the names of its {group} do not apply because the {node} declares"
                + " its own credentials.source"
            : "on the {node} or its {group}";
        if (names.username().isEmpty()) {
            findings.termError("%s: no Parameter Store name for the username (set"
                + " credentials.parameters.username " + where + ")", id);
        }
        if (names.password().isEmpty()) {
            findings.termError("%s: no Parameter Store name for the password (set"
                + " credentials.parameters.password " + where + ")", id);
        }
        if (names.e2eUsername().isPresent() != names.e2ePassword().isPresent()) {
            String missing = names.e2eUsername().isPresent() ? "e2ePassword" : "e2eUsername";
            findings.error(id + ": credentials.parameters." + missing + " is missing; the"
                + " end-to-end credentials need both e2eUsername and e2ePassword");
        }
        names.trustStorePassword().ifPresent(name -> {
            if (server.cliConfigured()) {
                findings.termError("%s: credentials.parameters.trustStorePassword (%s) is set,"
                    + " but this {node} has a CLI configured and needs a password-less trust"
                    + " store (StartCLI could only receive the password as a process argument)",
                    id, name);
            }
            if (server.tls().trustStore().isEmpty()) {
                findings.warning(id + ": credentials.parameters.trustStorePassword (" + name
                    + ") is set, but no tls.trustStore is configured; the parameter is ignored");
            }
        });
    }

    /**
     * Feature 010 (research R-6): {@code credentials.parameters} on a group or node whose
     * effective source is the credential store is ignored, with a warning.
     */
    private static void checkIgnoredParameterNames(ProfileConfig config, Findings findings) {
        for (GroupConfig group : config.groups()) {
            boolean environment = SecretPlan.groupSource(config.credentials(), group)
                instanceof SecretSourceConfig.SystemStore;
            if (environment && group.credentials().parameters().isPresent()) {
                findings.termWarning("{Group} '%s': credentials.parameters is ignored; the"
                    + " secrets of this {group} come from the credential store", group.name());
            }
            if (!GroupId.isValidName(group.name())) {
                continue;
            }
            for (NodeConfig node : group.nodes()) {
                if (!GroupId.isValidName(node.name())
                    || node.credentials().parameters().isEmpty()) {
                    continue;
                }
                SecretPlan plan = SecretPlan.resolve(config.credentials(),
                    config.credentialPrefix(), group, node);
                if (!plan.aws()) {
                    findings.termWarning("%s: credentials.parameters is ignored; the secrets of"
                        + " this {node} come from the credential store", plan.node().value());
                }
            }
        }
    }

    /**
     * Feature 011 (research R-10; from {@code CredentialResolver}, 002 research D-6): two groups or
     * nodes of this profile must not derive the same credential store entry name, or both would
     * read it. Only credential store nodes derive names (AWS nodes read named parameters).
     */
    private static void checkEntryNameCollisions(ProfileConfig config, Findings findings) {
        Terminology terms = config.terminology().effectiveOrDefault();
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (EffectiveNodeConfig server : config.resolvableNodes()) {
            if (server.secretPlan().aws()) {
                continue;
            }
            CredentialNames names = server.credentialNames();
            for (String kind : CredentialNames.KINDS) {
                owners.computeIfAbsent(names.nodeName(kind), k -> new TreeSet<>())
                    .add(terms.render("{node} ") + server.id());
                owners.computeIfAbsent(names.groupName(kind), k -> new TreeSet<>())
                    .add(terms.render("{group} ") + server.id().group());
            }
        }
        Map<Set<String>, List<String>> byOwners = new LinkedHashMap<>();
        owners.forEach((name, users) -> {
            if (users.size() > 1) {
                byOwners.computeIfAbsent(users, k -> new ArrayList<>()).add(name);
            }
        });
        byOwners.forEach((users, names) -> findings.error("Credential store entry names collide: "
            + String.join(", ", names) + " would be used by " + String.join(" and ", users)
            + terms.render("; rename one of the {groups} or {nodes}")));
    }

    /**
     * Review m-d: in a {@code group} where any node allows end-to-end tests, a node named
     * {@code e2e} would read the entry {@code <PREFIX>_<GROUP>_E2E_USERNAME} as its own username.
     * Only credential store nodes read these entries (research R-6, R-13), so AWS nodes are left
     * out.
     */
    private static void checkE2eNodeNames(ProfileConfig config, Findings findings) {
        Map<String, List<EffectiveNodeConfig>> byGroup = new TreeMap<>();
        config.resolvableNodes().stream().filter(server -> !server.secretPlan().aws())
            .forEach(server -> byGroup.computeIfAbsent(server.id().group().value(),
                group -> new ArrayList<>()).add(server));
        byGroup.values().forEach(nodes -> {
            boolean e2e = nodes.stream().anyMatch(server -> server.development().e2eTests()
                != E2ePolicy.FORBIDDEN);
            nodes.stream().filter(server -> e2e && server.id().name().equalsIgnoreCase("e2e"))
                .forEach(server -> findings.termError("%s: the {node} name '%s' clashes with the"
                    + " e2e basic-authentication entries of its {group} (%s is its username"
                    + " entry as well); rename the {node} or set e2eTests: FORBIDDEN for the"
                    + " {group}", server.id().value(), server.id().name(),
                    server.credentialNames().groupName(CredentialNames.E2E_USERNAME)));
        });
    }

    /**
     * The stage chain of feature 005 (research D-2, contracts/configuration-delta.md):
     *
     * <ul>
     *   <li>{@code deploy.from} is required and names an existing group other than this one;
     *   <li>the chain is acyclic; a cycle is named once, e.g. {@code dev → int → dev};
     *   <li>{@code mode: EXECUTE} on a production group needs the effective
     *       {@code write.productionOptIn: true} on every node of it ({@code PACKAGE_ONLY} sends
     *       nothing and needs none);
     *   <li>every node of a target group and of its source group has a StartCLI installation
     *       ({@code cli.home});
     *   <li>each {@code deploy.exclude} entry has exactly one of {@code diagramGroup},
     *       {@code name}, {@code repositoryPath}, with a non-blank value.
     * </ul>
     */
    private static void checkChain(ProfileConfig config, Findings findings) {
        Map<String, GroupConfig> groups = new java.util.LinkedHashMap<>();
        config.groups().forEach(group -> groups.putIfAbsent(group.name(), group));
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        Set<String> withoutCli = new TreeSet<>();
        for (GroupConfig group : config.groups()) {
            if (group.deploy().isEmpty()) {
                continue;
            }
            DeployConfig deploy = group.deploy().get();
            String name = group.name();
            String from = deploy.from().strip();
            if (from.isEmpty()) {
                findings.termError("{Group} '%s': deploy.from is required (the {group} this one"
                    + " receives releases from)", name);
            } else if (from.equals(name) || !groups.containsKey(from)) {
                findings.termError("{Group} '%s': deploy.from '%s' must name an existing {group}"
                    + " other than this one", name, from);
            } else {
                sources.put(name, from);
            }
            List<EffectiveNodeConfig> nodes = nodesOf(config, name);
            if (group.production() && deploy.mode() == DeployMode.EXECUTE) {
                List<String> missing = nodes.stream()
                    .filter(node -> !node.write().productionOptIn())
                    .map(node -> node.id().value()).toList();
                if (!missing.isEmpty()) {
                    findings.termError("{Group} '%s' is production: deploy mode EXECUTE needs"
                        + " write.productionOptIn: true on every {node} (missing: %s); use mode"
                        + " PACKAGE_ONLY to receive import packages instead", name,
                        String.join(", ", missing));
                }
            }
            List<EffectiveNodeConfig> involved = new ArrayList<>(nodes);
            if (sources.containsKey(name)) {
                involved.addAll(nodesOf(config, from));
            }
            involved.stream().filter(node -> !node.cliConfigured())
                .forEach(node -> withoutCli.add(node.id().value()));
            for (int i = 0; i < deploy.exclude().size(); i++) {
                ExcludeRule rule = deploy.exclude().get(i);
                List<Optional<String>> keys = List.of(rule.diagramGroup(), rule.name(),
                    rule.repositoryPath());
                long set = keys.stream().filter(Optional::isPresent).count();
                boolean blank = keys.stream().flatMap(Optional::stream)
                    .anyMatch(String::isBlank);
                if (set != 1 || blank) {
                    findings.termError("{Group} '%s': deploy.exclude[%s] must have exactly one"
                        + " of diagramGroup, name, repositoryPath, with a non-blank value", name,
                        i);
                }
            }
        }
        if (!withoutCli.isEmpty()) {
            findings.termError("Every {node} of each {group} with deploy and of its source"
                + " {group} needs a StartCLI installation (cli.home); missing on: %s",
                String.join(", ", withoutCli));
        }
        Set<String> reported = new HashSet<>();
        for (String start : sources.keySet()) {
            List<String> path = new ArrayList<>();
            String current = start;
            while (current != null && !path.contains(current)) {
                path.add(current);
                current = sources.get(current);
            }
            if (current == null || !current.equals(start)) {
                continue;
            }
            if (reported.addAll(path)) {
                path.add(start);
                findings.termError("The stage chain has a cycle: %s; every chain must start"
                    + " at one {group} without deploy", String.join(" → ", path));
            }
        }
    }

    /** The resolvable nodes of group {@code name}, in config order. */
    private static List<EffectiveNodeConfig> nodesOf(ProfileConfig config, String name) {
        return config.resolvableNodes().stream()
            .filter(node -> node.id().group().value().equals(name)).toList();
    }

    /**
     * Research D-25 (M8): the {@code Server-State} trailer of the workspace history names the
     * {@code group}, so at most one node per group may be a development node.
     */
    private static void checkOneDevelopmentNodePerGroup(ProfileConfig config,
        Findings findings) {
        Map<String, List<String>> developmentNodes = new TreeMap<>();
        for (EffectiveNodeConfig server : config.resolvableNodes()) {
            if (server.development().enabled()) {
                developmentNodes.computeIfAbsent(server.id().group().value(),
                    group -> new ArrayList<>())
                    .add(server.id().name());
            }
        }
        developmentNodes.forEach((group, nodes) -> {
            if (nodes.size() > 1) {
                findings.termError("{Group} '%s' has %s development {nodes} (%s); at most one"
                    + " {node} per {group} may set development.enabled: true (the workspace"
                    + " history records the server state per {group})", group, nodes.size(),
                    String.join(", ", nodes));
            }
        });
    }

    /**
     * Feature 008 (research D-2, contracts/configuration-delta.md): only where
     * {@code development.repository: WRITE} is set explicitly, so on the raw records. An error on
     * a production group or any of its nodes and on a node without the effective
     * {@code development.enabled: true}; a warning on a non-production group without such a
     * node. A {@code WRITE} inherited from {@code defaults} or the group is never a finding.
     */
    private static void checkRepositorySwitch(ProfileConfig config, Findings findings) {
        Defaults defaults = config.defaults();
        for (GroupConfig group : config.groups()) {
            if (group.production()) {
                if (isWrite(group.development())) {
                    findings.termError("{Group} '%s': development.repository: WRITE is not"
                        + " allowed in production {groups}", group.name());
                }
                group.nodes().stream().filter(node -> isWrite(node.development()))
                    .forEach(node -> findings.termError("%s/%s: development.repository: WRITE"
                        + " is not allowed in production {groups}", group.name(), node.name()));
                continue;
            }
            boolean anyDevelopmentNode = false;
            for (NodeConfig node : group.nodes()) {
                boolean developmentEnabled = node.development().enabled()
                    .or(group.development()::enabled).or(defaults.development()::enabled)
                    .orElse(Defaults.BUILTIN_DEVELOPMENT_ENABLED);
                anyDevelopmentNode |= developmentEnabled;
                if (!developmentEnabled && isWrite(node.development())) {
                    findings.termError("%s/%s: development.repository: WRITE needs"
                        + " development.enabled: true on the {node}", group.name(), node.name());
                }
            }
            if (!anyDevelopmentNode && isWrite(group.development())) {
                findings.termWarning("{Group} '%s': development.repository: WRITE has no effect;"
                    + " the {group} has no {node} with development.enabled: true", group.name());
            }
        }
    }

    /**
     * Feature 015 (research R-15, contracts/configuration-delta.md): {@code
     * archives.productionOptIn: true} on a group without {@code production: true} is a warning;
     * {@code export_archives} needs no opt-in there.
     */
    private static void checkArchivesOptIn(ProfileConfig config, Findings findings) {
        for (GroupConfig group : config.groups()) {
            if (!group.production()
                && group.archives().productionOptIn().equals(Optional.of(true))) {
                findings.termWarning("archives.productionOptIn has no effect on {group} %s"
                    + " (not production)", group.name());
            }
        }
    }

    /**
     * Feature 020 (US2-5, contracts/configuration-delta.md → Validation): {@code
     * unsupported.queueEntryRead: true} on a group with {@code production: true} is a warning.
     */
    private static void checkQueueEntryReadOptIn(ProfileConfig config, Findings findings) {
        for (GroupConfig group : config.groups()) {
            if (group.production()
                && group.unsupported().queueEntryRead().equals(Optional.of(true))) {
                findings.termWarning("unsupported.queueEntryRead is enabled on production {group}"
                    + " %s: Queue Manager entries are read through an undocumented INUBIT"
                    + " interface without vendor support", group.name());
            }
        }
    }

    private static boolean isWrite(DevelopmentConfig development) {
        return development.repository().equals(Optional.of(RepositoryMode.WRITE));
    }

    private static void checkBaseUrl(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        URI url = server.baseUrl();
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (url.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            findings.error(id + ": baseUrl must be an absolute https:// URL (http:// only with"
                + " allowInsecureHttp: true)");
        } else if (scheme.equals("http") && !server.allowInsecureHttp()) {
            findings.error(id + ": baseUrl uses http://; use https:// or set"
                + " allowInsecureHttp: true");
        } else if (scheme.equals("http")) {
            findings.warning(id + ": baseUrl uses http:// (allowInsecureHttp: true); credentials"
                + " are sent unencrypted");
        }
    }

    /** Checked only when {@code cli.url} differs from the default derived from the base URL. */
    private static void checkCliUrl(EffectiveNodeConfig server, Findings findings) {
        URI url = server.cli().url();
        if (url.equals(URI.create(server.baseUrl() + EffectiveNodeConfig.DEFAULT_CLI_PATH))) {
            return;
        }
        String id = server.id().value();
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (url.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            findings.error(id + ": cli.url must be an absolute https:// URL (http:// only with"
                + " allowInsecureHttp: true)");
        } else if (scheme.equals("http") && !server.allowInsecureHttp()) {
            findings.error(id + ": cli.url uses http://; use https:// or set"
                + " allowInsecureHttp: true");
        } else if (scheme.equals("http")) {
            findings.warning(id + ": cli.url uses http:// (allowInsecureHttp: true); the CLI"
                + " password is sent unencrypted");
        }
    }

    private void checkTls(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        EffectiveNodeConfig.Tls tls = server.tls();
        if (tls.disableHostnameVerification() && tls.pinnedCertificateSha256().isEmpty()) {
            findings.error(id + ": tls.disableHostnameVerification requires"
                + " tls.pinnedCertificateSha256");
        } else if (tls.disableHostnameVerification()) {
            findings.warning(id + ": hostname verification is disabled; the certificate is"
                + " checked against the pinned SHA-256 fingerprint instead");
        }
        tls.pinnedCertificateSha256()
            .filter(pin -> !PIN_PATTERN.matcher(pin).matches())
            .ifPresent(pin -> findings.error(id + ": tls.pinnedCertificateSha256 must be a SHA-256"
                + " fingerprint (64 hex digits, optionally colon-separated)"));
        tls.trustStore()
            .filter(path -> !exists.test(path))
            .ifPresent(path -> findings.error(id + ": tls.trustStore " + path + " not found"));
    }

    private void checkCli(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        if (windows) {
            checkWindowsValues(server, findings);
        }
        server.startCliScript(windows)
            .filter(script -> !exists.test(script))
            .ifPresent(script -> findings.warning(id + ": " + script + " not found; CLI-backed"
                + " capabilities report CLI_UNAVAILABLE"));
        if (server.cli().javaHome().isPresent()) {
            Path javaHome = server.cli().javaHome().get();
            if (!exists.test(javaHome)) {
                findings.warning(id + ": cli.javaHome " + javaHome + " not found; CLI-backed"
                    + " capabilities report CLI_UNAVAILABLE");
            }
        } else if (serverJavaHome().isEmpty()) {
            findings.warning(id + ": neither cliJavaHome / cli.javaHome nor JAVA_HOME is set;"
                + " CLI-backed capabilities report CLI_UNAVAILABLE");
        }
    }

    /**
     * Feature 017 (FR-006, research D-17): each {@code defaults.cliClients} entry, without
     * starting it: a key the capability matrix does not declare, a missing {@code home} or
     * {@code javaHome} directory, a {@code home} without StartCLI script, no JDK at all, the
     * JDK major of an 8.1 or 9.0 entry, and on Windows the values
     * StartCLI receives through the command interpreter (feature 013).
     */
    private void checkCliClients(Defaults defaults, Findings findings) {
        defaults.cliClients().forEach((key, client) -> {
            String subject = entrySubject(key);
            if (!CapabilityMatrix.production().declares(key)) {
                findings.warning(subject + ": INUBIT " + key.text() + " is not declared"
                    + " supported; nodes detected as " + key.text() + " use this client with"
                    + " unverified reads and refused writes");
            }
            // a home Windows refuses is named below, without naming its network host
            boolean refusedHome = windows && (WindowsArguments.isUnc(client.home())
                || WindowsArguments.refusal(client.home().toString()).isPresent());
            if (!exists.test(client.home())) {
                findings.warning(subject + ": home " + client.home() + " does not exist");
            } else if (!refusedHome) {
                // typically the INUBIT installation instead of its client directory
                Path script = startCliScript(client.home());
                if (!exists.test(script)) {
                    findings.warning(subject + ": " + script + " not found; CLI-backed"
                        + " capabilities report CLI_UNAVAILABLE on nodes using this entry");
                }
            }
            client.javaHome().filter(javaHome -> !exists.test(javaHome))
                .ifPresent(javaHome -> findings.warning(subject + ": javaHome " + javaHome
                    + " does not exist"));
            Optional<String> entryJdk = entryJavaHome(defaults, client);
            entryJdk.ifPresent(javaHome -> checkJdk(subject, key, javaHome, findings));
            if (entryJdk.isEmpty()) {
                findings.warning(subject + ": neither its javaHome, defaults.cliJavaHome nor"
                    + " JAVA_HOME is set; CLI-backed capabilities report CLI_UNAVAILABLE on nodes"
                    + " using this entry without their own cli.javaHome");
            }
            if (windows) {
                if (WindowsArguments.isUnc(client.home())) {
                    findings.warning(subject + ": home is a network path (\\\\…); StartCLI needs"
                        + " a local drive path on Windows");
                }
                refusedOnWindows(subject, "home", WindowsArguments.refusal(
                    client.home().toString()), findings);
                client.javaHome().map(Path::toString).ifPresent(javaHome ->
                    refusedOnWindows(subject, "javaHome", javaRefusal(javaHome), findings));
            }
        });
    }

    /** {@code <home>/bin/startcli.sh} ({@code startcli.bat} on Windows). */
    private Path startCliScript(Path home) {
        return home.resolve("bin").resolve(windows ? "startcli.bat" : "startcli.sh");
    }

    /** {@code defaults.cliClients."<key>"}, the subject of an entry's warnings. */
    public static String entrySubject(MajorMinor key) {
        return "defaults.cliClients.\"" + key.text() + "\"";
    }

    /** The JDK of an entry as a node choosing it without own JDK gets it (research D-3). */
    private Optional<String> entryJavaHome(Defaults defaults, CliClient client) {
        return client.javaHome().or(defaults::cliJavaHome).map(Path::toString)
            .or(this::serverJavaHome);
    }

    /**
     * FR-006: the JDK of a node pinned to 8.1 or 9.0 (a pin or a line with minor), unless it is
     * the JDK of the entry it chooses, already checked there. Only for a node whose client
     * depends on its version, so that 0.8.0 profiles keep their output (FR-009).
     */
    private void checkNodeJdk(EffectiveNodeConfig server, Defaults defaults, Findings findings) {
        Optional<InubitVersion> version =
            NodeVersionState.initial(server.versionLine()).effectiveVersion();
        if (version.isEmpty() || !server.choosesCliByVersion()) {
            return;
        }
        CliChoice choice = server.cliForSetting();
        if (choice.cli().home().isEmpty()) {
            return;
        }
        Optional<String> javaHome = choice.cli().javaHome().map(Path::toString)
            .or(this::serverJavaHome);
        boolean entryJdk = choice.entry().map(defaults.cliClients()::get)
            .flatMap(client -> entryJavaHome(defaults, client)).equals(javaHome);
        if (!entryJdk) {
            javaHome.ifPresent(home -> checkJdk(server.id().value(),
                MajorMinor.of(version.get()), home, findings));
        }
    }

    private void checkJdk(String subject, MajorMinor version, String javaHome,
        Findings findings) {
        Integer shipped = SHIPPED_JAVA.get(version);
        if (shipped == null) {
            return;
        }
        OptionalInt major;
        try {
            major = JdkRelease.major(Path.of(javaHome), releaseReader);
        } catch (InvalidPathException e) {
            return;
        }
        if (major.isPresent() && major.getAsInt() != shipped) {
            findings.warning(subject + ": the JDK in " + javaHome + " is Java " + major.getAsInt()
                + "; INUBIT " + version.text() + " ships with Java " + shipped);
        }
    }

    private static Optional<String> javaRefusal(String value) {
        return WindowsArguments.refusal(value)
            .or(() -> value.indexOf(';') >= 0 ? Optional.of("semicolon") : Optional.empty());
    }

    /**
     * Feature 017 (FR-006, contracts/configuration-delta.md → {@code --check-config}): the
     * warning for a {@code defaults.cliClients} entry whose client's declared range excludes the
     * entry's key; empty if the range includes it or cannot be evaluated.
     */
    public static Optional<String> clientRangeWarning(MajorMinor key, Path home,
        ClientVersion client) {
        if (client.compatibleWith(key.major(), OptionalInt.of(key.minor()))
            != ClientVersion.Compatibility.INCOMPATIBLE) {
            return Optional.empty();
        }
        return Optional.of(entrySubject(key) + ": the StartCLI client in " + home
            + " supports INUBIT " + client.supportedFrom().orElseThrow().raw() + " to "
            + client.supportedTo().orElseThrow().raw() + ", which excludes " + key.text());
    }

    /**
     * Feature 012 (FR-012a, contracts/configuration-delta.md → Validation): the warnings for a
     * node pinned to a CLI-only version (6.1) whose StartCLI client options were read by the local
     * probe of {@code --check-config}; each names a refusal {@code V61Connection} makes at
     * runtime. Not part of {@link #validate}, which runs no probes.
     *
     * @param terms          the profile's terminology (FR-007)
     * @param options        the client options; an option that could not be read is absent
     * @param trustStoreType the format of {@code tls.trustStore} ({@code JKS} or {@code PKCS12});
     *                       ignored without a trust store
     */
    public static List<String> clientOptionWarnings(Terminology terms, EffectiveNodeConfig server,
        CliClientOptions options, String trustStoreType) {
        String id = server.id().value();
        EffectiveNodeConfig.Tls tls = server.tls();
        List<String> warnings = new ArrayList<>();
        if (tls.disableHostnameVerification() && tls.pinnedCertificateSha256().isPresent()
            && !options.disableHostNameVerification()) {
            warnings.add(id + terms.render(": the StartCLI client offers no option to skip"
                + " host-name verification; StartCLI-backed tools on this {node} are refused"
                + " (TLS_ERROR)"));
        }
        if (tls.trustStore().isPresent() && trustStoreType.equals("PKCS12")
            && options.javaFeatureVersion().filter(java -> java >= 8).isEmpty()) {
            warnings.add(id + ": the StartCLI runtime (Java " + options.javaFeatureVersion()
                .map(String::valueOf).orElse("unknown") + ") cannot read the PKCS12 trust store;"
                + " convert it to JKS or set cliJavaHome");
        }
        if (!options.trustStoreFilePath() && tls.trustStore()
            .filter(path -> path.toString().chars().anyMatch(Character::isWhitespace))
            .isPresent()) {
            warnings.add(id + ": the trust-store path contains spaces, which the StartCLI runtime"
                + " settings cannot carry; use a path without spaces");
        }
        return warnings;
    }

    /**
     * The server's {@code JAVA_HOME} as {@code CliRunner} resolves it: on Windows the name is
     * matched ignoring case ({@link WindowsArguments#environmentValue}); blank counts as unset.
     */
    private Optional<String> serverJavaHome() {
        return Optional.ofNullable(windows
                ? WindowsArguments.environmentValue(environment, "JAVA_HOME")
                : environment.get("JAVA_HOME"))
            .filter(value -> !value.isBlank());
    }

    /**
     * Feature 013 (FR-002, FR-011, contracts/configuration-delta.md): the configured values that
     * StartCLI receives through the Windows command interpreter, checked as {@code CliRunner}
     * checks them per call. The messages name the setting and the character class, never the
     * value.
     */
    private void checkWindowsValues(EffectiveNodeConfig server, Findings findings) {
        String id = server.id().value();
        Path home = server.cli().home().orElseThrow();
        if (WindowsArguments.isUnc(home)) {
            findings.warning(id + ": cli.home is a network path (\\\\…); StartCLI needs a local"
                + " drive path on Windows");
        }
        refusedOnWindows(id, "cli.home", WindowsArguments.refusal(home.toString()), findings);
        Optional<String> javaHome = server.cli().javaHome().map(Path::toString)
            .or(this::serverJavaHome);
        javaHome.ifPresent(value -> refusedOnWindows(id,
            server.cli().javaHome().isPresent() ? "cli.javaHome" : "JAVA_HOME",
            javaRefusal(value), findings));
        server.tls().trustStore().ifPresent(trustStore -> refusedOnWindows(id, "tls.trustStore",
            WindowsArguments.refusal(trustStore.toString()), findings));
        refusedOnWindows(id, "cli.url", WindowsArguments.refusal(server.cli().url().toString()),
            findings);
        checkWindowsLineLength(server, findings);
    }

    private static void refusedOnWindows(String id, String setting, Optional<String> refusal,
        Findings findings) {
        refusal.ifPresent(characterClass -> findings.warning(id + ": " + setting + " contains a"
            + " character the Windows command interpreter interprets (" + characterClass + ");"
            + " CLI-backed capabilities report CLI_UNAVAILABLE"));
    }

    /**
     * The command line of a call with an empty command and a username of {@link
     * #ASSUMED_USERNAME_LENGTH} characters, built as {@code CliRunner} builds it; too long, it
     * names the longest configured value.
     */
    private static void checkWindowsLineLength(EffectiveNodeConfig server, Findings findings) {
        List<String> arguments = new ArrayList<>(List.of("-u",
            "u".repeat(ASSUMED_USERNAME_LENGTH)));
        server.tls().trustStore().ifPresent(trustStore -> {
            arguments.add("--trustStoreFilePath");
            arguments.add(trustStore.toString());
        });
        if (server.tls().disableHostnameVerification()) {
            arguments.add("--disableHostNameVerification");
        }
        arguments.addAll(List.of("--execCommand", "", server.cli().url().toString()));
        String script = server.startCliScript(true).orElseThrow().toString();
        if (WindowsArguments.lineLength(script, arguments) > WindowsArguments.MAX_LINE_LENGTH) {
            Map<String, Integer> lengths = new LinkedHashMap<>();
            lengths.put("cli.home", server.cli().home().orElseThrow().toString().length());
            server.tls().trustStore().ifPresent(trustStore ->
                lengths.put("tls.trustStore", trustStore.toString().length()));
            lengths.put("cli.url", server.cli().url().toString().length());
            String longest = lengths.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElseThrow();
            findings.warning(server.id().value() + ": the StartCLI command line would exceed "
                + WindowsArguments.MAX_LINE_LENGTH + " characters (" + longest + "); CLI-backed"
                + " capabilities report CLI_UNAVAILABLE");
        }
    }

    /** Once per profile: what the Windows host reported before StartCLI can start. */
    private void checkStartCliHost(Findings findings) {
        host.systemDirectoryProblem().ifPresent(reason -> findings.warning("StartCLI cannot be"
            + " started: the Windows system directory could not be read (" + reason + ");"
            + " CLI-backed capabilities report CLI_UNAVAILABLE"));
        host.containmentProblem().ifPresent(reason -> findings.warning("StartCLI processes could"
            + " not be bound to the server (" + reason + "); CLI-backed capabilities report"
            + " CLI_UNAVAILABLE"));
    }

    private static void checkPositive(String id, String key, Duration value, Findings findings) {
        if (value.isZero() || value.isNegative()) {
            findings.error(id + ": " + key + " must be a positive duration");
        }
    }

    /**
     * The collected findings. {@link #termError} and {@link #termWarning} take a template with
     * term placeholders ({@code {node}}, …) and {@code %s} for values: the template is rendered
     * with the profile's terminology before the values are inserted, so values from the file are
     * never rendered.
     */
    private static final class Findings {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Terminology terms;

        Findings(Terminology terms) {
            this.terms = terms;
        }

        void error(String message) {
            errors.add(message);
        }

        void warning(String message) {
            warnings.add(message);
        }

        void termError(String template, Object... values) {
            errors.add(format(template, values));
        }

        void termWarning(String template, Object... values) {
            warnings.add(format(template, values));
        }

        private String format(String template, Object... values) {
            return String.format(Locale.ROOT, terms.render(template), values);
        }
    }
}
