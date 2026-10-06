package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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

    /** Passwords shorter than this are a configuration error (Phase 3 review m4). */
    static final int MIN_SCRUBBED_LENGTH = 4;

    /** Upper bound of {@code confirmationTtl} (Phase 6 review W5). */
    static final Duration MAX_CONFIRMATION_TTL = Duration.ofHours(1);

    private final Predicate<Path> exists;
    private final Map<String, String> environment;
    private final boolean windows;
    private final Path tempDirectory;
    private final Function<Path, List<LoadedConfig>> otherProfiles;
    private final WorkspaceDirectory.Preparer workspaces;

    /**
     * @param exists      file-system check, injectable for tests
     * @param environment the process environment (for the {@code JAVA_HOME} fallback)
     * @param windows     whether the server runs on Windows, where CLI tools are unsupported
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows) {
        this(exists, environment, windows, Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * @param tempDirectory {@code java.io.tmpdir}, below which the inventory exports are written
     *     (its path is passed to StartCLI, review I9)
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory) {
        this(exists, environment, windows, tempDirectory, source -> List.of());
    }

    /**
     * @param otherProfiles the other profile files of the default configuration directory, given
     *     the file being checked ({@link ConfigLoader#otherProfiles}); compared best effort for a
     *     shared audit directory, credential prefix or profile name (002 T031)
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles) {
        this(exists, environment, windows, tempDirectory, otherProfiles,
            WorkspaceDirectory::prepare);
    }

    /**
     * @param workspaces creates and checks the workspace directory ({@link
     *     WorkspaceDirectory#prepare} unless a test injects another)
     */
    public ConfigValidator(Predicate<Path> exists, Map<String, String> environment,
        boolean windows, Path tempDirectory, Function<Path, List<LoadedConfig>> otherProfiles,
        WorkspaceDirectory.Preparer workspaces) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.tempDirectory = tempDirectory;
        this.exists = exists;
        this.environment = Map.copyOf(environment);
        this.windows = windows;
        this.otherProfiles = Objects.requireNonNull(otherProfiles, "otherProfiles");
    }

    /** A validator for the current process. */
    public static ConfigValidator forSystem() {
        return new ConfigValidator(Files::exists, System.getenv(),
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /**
     * Checks {@code loaded}; the messages use the profile's terminology (FR-007), or the default
     * names while the terminology itself is invalid.
     */
    public ValidationReport validate(LoadedConfig loaded, CredentialResolution credentials) {
        ProfileConfig config = loaded.config();
        Terminology terms = config.terminology().effectiveOrDefault();
        Findings findings = new Findings(terms);
        for (String key : loaded.credentialKeys()) {
            // 002 research D-6: the variables under the profile's effective prefix
            findings.termError("Credential key '%s' is not allowed in the configuration file;"
                + " credentials belong in environment variables (%s_%s_USERNAME /"
                + " _PASSWORD)", key, config.credentialPrefix(), variablePart(terms));
        }
        loaded.urlProblems().forEach(findings::error);
        checkProfile(config, findings);
        Optional<WorkspaceDirectory.Result> workspace = checkWorkspace(config, findings);
        checkStructure(config, findings);
        checkResultLimits(config.resultLimits(), findings);
        for (EffectiveNodeConfig server : config.resolvableNodes()) {
            checkServer(server, credentials, findings);
        }
        boolean cliConfigured = config.resolvableNodes().stream()
            .anyMatch(EffectiveNodeConfig::cliConfigured);
        if (!windows && cliConfigured && !CliPaths.exportRootUsable(tempDirectory)) {
            findings.warning("java.io.tmpdir " + tempDirectory + " cannot be passed to StartCLI"
                + " (it must match " + CliPaths.PATH_VALUE.pattern() + "); inventory exports"
                + " report CLI_UNAVAILABLE. Start the MCP server with"
                + " -Djava.io.tmpdir=<such a path>");
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
        checkOtherProfiles(loaded, findings);
        findings.errors.addAll(credentials.errors());
        findings.warnings.addAll(credentials.warnings());
        return new ValidationReport(findings.errors, findings.warnings, workspace);
    }

    /**
     * The other profile files of the default configuration directory (002 T031, spec edge cases;
     * best effort: profiles elsewhere are not seen):
     *
     * <ul>
     *   <li>an <b>error</b> if a file of <em>another</em> profile (another profile name) derives
     *       a credential variable name that this profile derives as well, by nested prefixes
     *       (e.g. {@code INUBIT_ACME} + group {@code 2-test} and {@code INUBIT_ACME_2} + group
     *       {@code test}) or by an equal prefix with equal group names (002 US2 review P1,
     *       re-review N1): two customers would read the same variable. Only names are printed;
     *   <li>one <b>warning</b> per file that has the same profile name (a copy, which may start;
     *       shared variable names are listed in the warning then), the same effective credential
     *       prefix or the same audit directory.
     * </ul>
     *
     * A profile name read from another file is printed only if valid (review P3a).
     */
    private void checkOtherProfiles(LoadedConfig loaded, Findings findings) {
        ProfileConfig config = loaded.config();
        Set<String> ownVariables = config.credentialVariableNames();
        for (LoadedConfig other : otherProfiles.apply(loaded.source())) {
            ProfileConfig otherConfig = other.config();
            String otherName = ProfileInfo.displayName(otherConfig.profile().name());
            String otherFile = "The profile file " + other.source() + " (profile "
                + (otherName.equals(ProfileInfo.INVALID_NAME) ? otherName : "'" + otherName + "'")
                + ")";
            Set<String> sharedVariables = new TreeSet<>(otherConfig.credentialVariableNames());
            sharedVariables.retainAll(ownVariables);
            String name = config.profile().name();
            boolean copy = !name.isEmpty() && name.equals(otherConfig.profile().name());
            if (!sharedVariables.isEmpty() && !copy) {
                findings.error(otherFile + " derives the same credential variable names as this"
                    + " profile, so both would read them: " + String.join(", ", sharedVariables)
                    + ". Set another credentials.envPrefix for one of them or rename the"
                    + " group or node");
            }
            List<String> shared = new ArrayList<>();
            if (copy) {
                String shown = ProfileInfo.isValidName(name) ? "'" + name + "'"
                    : ProfileInfo.INVALID_NAME;
                shared.add("the profile name " + shown
                    + " (profile names must be unique per workstation)");
            }
            if (config.credentialPrefix().equals(otherConfig.credentialPrefix())) {
                shared.add("the credential variable prefix " + config.credentialPrefix()
                    + " (both read the same variables)");
            }
            if (copy && !sharedVariables.isEmpty()
                && !config.credentialPrefix().equals(otherConfig.credentialPrefix())) {
                shared.add("the credential variable names " + String.join(", ", sharedVariables)
                    + " (both read them)");
            }
            // feature 003 FR-003: two profiles never share a workspace; a copy of the same
            // profile (same name) may start and shares it by design (002 re-review N1)
            Path workspace = config.workspace().toAbsolutePath().normalize();
            Path otherWorkspace = otherConfig.workspace().toAbsolutePath().normalize();
            boolean nested = workspace.startsWith(otherWorkspace)
                || otherWorkspace.startsWith(workspace);
            if (nested && copy) {
                shared.add("the workspace " + config.workspace() + " (one history for both)");
            } else if (nested) {
                String profile = ProfileInfo.isValidName(name) ? "'" + name + "'"
                    : ProfileInfo.INVALID_NAME;
                String relation = workspace.equals(otherWorkspace) ? "is also the workspace of"
                    : workspace.startsWith(otherWorkspace) ? "is inside the workspace "
                        + otherWorkspace + " of" : "contains the workspace " + otherWorkspace
                        + " of";
                findings.error("The workspace " + workspace + " of profile " + profile + " "
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
                    + " by side need their own (credentials.envPrefix, auditDirectory)");
            }
        }
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
        config.credentials().envPrefix()
            .filter(prefix -> !ProfileInfo.isValidPrefix(prefix))
            .ifPresent(prefix -> findings.error("Invalid credentials.envPrefix: expected "
                + ProfileInfo.PREFIX_PATTERN.pattern()));
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

    private static void checkResultLimits(ResultLimits limits, Findings findings) {
        if (limits.maxItems() < 1) {
            findings.error("resultLimits.maxItems must be at least 1");
        }
        if (limits.maxChars() < ResultLimits.MIN_MAX_CHARS) {
            findings.error("resultLimits.maxChars must be at least " + ResultLimits.MIN_MAX_CHARS);
        }
    }

    private void checkServer(EffectiveNodeConfig server, CredentialResolution credentials,
        Findings findings) {
        String id = server.id().value();
        checkBaseUrl(server, findings);
        if (server.production() && server.write().confirmation() == ConfirmationMode.CLIENT) {
            findings.termError("%s: write.confirmation CLIENT is not allowed in production"
                + " {groups}; use SERVER", id);
        }
        checkCliUrl(server, findings);
        checkTls(server, findings);
        credentials.all().stream()
            .filter(c -> c.node().equals(server.id()))
            .forEach(c -> checkCredentials(server, c, findings));
        if (server.cliConfigured()) {
            checkCli(server, findings);
        }
        if (server.versionLine() == VersionLine.V9_X) {
            findings.warning(id + ": versionLine V9_X is unsupported in this version; the 8.1"
                + " adapters are used");
        }
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

    private static void checkCredentials(EffectiveNodeConfig server,
        NodeCredentials credentials, Findings findings) {
        String id = server.id().value();
        credentials.trustStorePassword().ifPresent(password -> {
            if (server.cliConfigured()) {
                findings.termError("%s: %s is set, but this {node} has a CLI configured and needs"
                    + " a password-less trust store (StartCLI could only receive the password as"
                    + " a process argument)", id, password.sourceVariable());
            }
            if (server.tls().trustStore().isEmpty()) {
                findings.warning(id + ": " + password.sourceVariable() + " is set, but no"
                    + " tls.trustStore is configured; the variable is ignored");
            }
        });
        credentials.password()
            .filter(password -> password.value().reveal().length() < MIN_SCRUBBED_LENGTH)
            .ifPresent(password -> findings.error(id + ": the password from "
                + password.sourceVariable() + " is shorter than " + MIN_SCRUBBED_LENGTH
                + " characters; such a short value cannot be scrubbed reliably from logs and"
                + " results without masking unrelated text"));
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
            findings.warning(id + ": CLI tools are not supported on Windows in this version;"
                + " CLI-backed capabilities report CLI_UNAVAILABLE (REST tools work)");
            return;
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
        } else {
            String javaHome = environment.get("JAVA_HOME");
            if (javaHome == null || javaHome.isBlank()) {
                findings.warning(id + ": neither cliJavaHome / cli.javaHome nor JAVA_HOME is set;"
                    + " CLI-backed capabilities report CLI_UNAVAILABLE");
            }
        }
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
