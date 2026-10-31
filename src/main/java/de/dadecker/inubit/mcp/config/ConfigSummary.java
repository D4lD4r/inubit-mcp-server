package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.ClientVersion;
import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import de.dadecker.inubit.mcp.domain.model.VersionSetting;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Text printed by {@code --check-config}: the profile, its terminology, credential store entry
 * scheme, audit directory and workspace ({@code ok}, {@code created} or the problem), then per
 * group the nodes with their id, the effective write flag, the development and end-to-end test
 * settings (feature 004, once any node configures them; with the SOAP base address but never
 * its user information), CLI
 * availability, the {@code deploy} line of a node whose group receives deployments (feature 005)
 * and where each credential comes from, then all warnings and errors. Feature 005 adds a
 * {@code Chains:} block (one line per stage chain and the exclusions of each target) before the
 * groups. Feature 009 (FR-011a, research D-13) adds the {@code versionLine} setting and, for a
 * node whose StartCLI client was probed, the client version with its declared range and its
 * comparison with a concrete pin. Feature 010 (research R-12) adds the secret source to the node
 * lines; an AWS node shows its region, AWS profile and parameter names with the outcome of
 * {@link ParameterCheck}. Feature 011 (research R-13): every node line shows
 * {@code secrets: credentialStore (<store>)} or {@code secrets: awsParameterStore (…)}; a
 * credential store node shows per kind the entry found (or both names) with the outcome of
 * {@link EntryCheck}, and the {@code Credential store entries:} line names the scheme once some
 * node reads entries under the profile prefix.
 * Groups and nodes are named with the profile's display names (FR-007). Values and URLs are never
 * printed.
 */
public final class ConfigSummary {

    private static final String MISSING = "(missing)";

    private final Predicate<Path> exists;
    private final boolean windows;

    public ConfigSummary(Predicate<Path> exists, boolean windows) {
        this.exists = exists;
        this.windows = windows;
    }

    /**
     * @param clients the StartCLI client of each node whose probe succeeded (feature 009); a
     *                failed probe is among the warnings of {@code report}
     * @param parameters the Parameter Store check of the AWS nodes (feature 010); its findings
     *                are among those of {@code report}
     * @param entries the credential store check (feature 011); its findings are among those of
     *                {@code report}
     */
    public String render(LoadedConfig loaded, ValidationReport report,
        Map<NodeId, ClientVersion> clients, ParameterCheck.Result parameters,
        EntryCheck.Result entries) {
        ProfileConfig config = loaded.config();
        Terminology terms = config.terminology().effectiveOrDefault();
        StringBuilder out = new StringBuilder();
        out.append("Configuration: ").append(loaded.source()).append('\n');
        out.append("Profile: ").append(config.profile().name().isEmpty() ? MISSING
            : config.profile().name());
        config.profile().description()
            .ifPresent(description -> out.append(" (").append(description).append(')'));
        out.append('\n');
        out.append("Terminology: ").append(terms.groupSingular()).append('/')
            .append(terms.groupPlural()).append(", ").append(terms.nodeSingular()).append('/')
            .append(terms.nodePlural()).append('\n');
        // 002 US2: what keeps this profile apart from others running side by side; feature 011:
        // once some node reads credential store entries under the profile prefix
        Map<NodeId, SecretPlan> plans = config.secretPlans();
        if (plans.isEmpty() || plans.values().stream().anyMatch(plan -> plan.entries()
            .filter(names -> names.prefix().equals(config.credentialPrefix())).isPresent())) {
            out.append("Credential store entries: service ").append(CredentialStore.SERVICE)
                .append(", account ").append(config.credentialPrefix()).append('_')
                .append(ConfigValidator.variablePart(terms)).append("_<KIND> (")
                .append(storeName(entries.store())).append(")\n");
        }
        out.append("Audit directory: ").append(config.auditDirectory()).append('\n');
        // feature 003 (FR-004): ok, created just now, or why it cannot be used
        out.append("Workspace: ").append(config.workspace()).append(" (")
            .append(report.workspace().map(result -> switch (result) {
                case WorkspaceDirectory.Usable usable -> usable.created() ? "created" : "ok";
                case WorkspaceDirectory.Unusable unusable -> unusable.problem();
            }).orElse("not usable: see the errors")).append(")\n");
        // feature 004: shown once a node is a development stage or allows end-to-end tests
        boolean development = config.resolvableNodes().stream().map(
            EffectiveNodeConfig::developmentPolicy).anyMatch(policy -> policy.enabled()
                || policy.e2eTests() != E2ePolicy.FORBIDDEN);
        // feature 005: the stage chain, once any group has deploy
        Map<String, DeployConfig> deploys = new LinkedHashMap<>();
        config.groups().forEach(group -> group.deploy()
            .ifPresent(deploy -> deploys.putIfAbsent(group.name(), deploy)));
        appendChains(out, config, deploys);
        GroupId group = null;
        for (EffectiveNodeConfig server : config.resolvableNodes()) {
            if (!server.id().group().equals(group)) {
                group = server.id().group();
                out.append(terms.render("{Group} ")).append(group)
                    .append(server.production() ? " (production)" : "").append(":\n");
            }
            out.append("  ").append(terms.render("{Node} ")).append(server.id()).append(": ")
                .append(writeFlag(server))
                .append(development ? ", " + development(server) : "")
                .append(", cli: ").append(cliAvailable(server) ? "available" : "unavailable")
                .append(", inubit: ").append(server.versionLine().text());
            Optional.ofNullable(clients.get(server.id()))
                .ifPresent(client -> out.append(client(server.versionLine(), client)));
            Optional.ofNullable(deploys.get(server.id().group().value())).ifPresent(deploy ->
                out.append(", deploy: from ").append(deploy.from().strip()).append(" (")
                    .append(deploy.mode()).append(')'));
            // feature 011 (research R-13): the source of every node
            if (server.secretPlan().aws()) {
                out.append(", secrets: awsParameterStore");
                appendParameters(out, server.secretPlan(), parameters);
            } else {
                out.append(", secrets: credentialStore (").append(storeName(entries.store()))
                    .append(')');
                appendEntries(out, server, entries);
            }
            out.append('\n');
        }
        appendList(out, "Warnings:", report.warnings());
        appendList(out, "Errors:", report.errors());
        out.append(report.hasErrors()
            ? "Result: FAILED (" + report.errors().size() + " error(s))"
            : "Result: OK").append('\n');
        return out.toString();
    }

    /**
     * Feature 011: {@code , username ← <entry> <status>, password ← …} and the optional kinds
     * that are present; an entry that was not found shows both names ({@code <node> | <group>}).
     * A required kind without an outcome (the check did not run for the node) is
     * {@code not checked}, so that no line lacks an outcome.
     */
    private static void appendEntries(StringBuilder out, EffectiveNodeConfig server,
        EntryCheck.Result entries) {
        CredentialNames names = server.credentialNames();
        for (String key : EntryCheck.KEYS) {
            Optional<EntryCheck.Line> line = entries.line(server.id(), key);
            boolean required = key.equals("username") || key.equals("password");
            if (line.isPresent()) {
                out.append(", ").append(key).append(" ← ").append(line.get().shown()).append(' ')
                    .append(line.get().status().text());
            } else if (required) {
                String kind = key.equals("username") ? CredentialNames.USERNAME
                    : CredentialNames.PASSWORD;
                out.append(", ").append(key).append(" ← ").append(names.nodeName(kind))
                    .append(" | ").append(names.groupName(kind)).append(' ')
                    .append(EntryCheck.Status.NOT_CHECKED.text());
            }
        }
    }

    /** The store in a node line; a system without a supported store says so. */
    private static String storeName(StoreKind kind) {
        return kind == StoreKind.NONE ? "none on this system" : kind.displayName();
    }

    /**
     * Feature 010: {@code (<region>[, profile <p>]), username ← <name> <status>, password ← …}
     * and the optional names that are configured; a missing required name is
     * {@code (missing)}.
     */
    private static void appendParameters(StringBuilder out, SecretPlan plan,
        ParameterCheck.Result parameters) {
        SecretSourceConfig.AwsParameterStore source =
            (SecretSourceConfig.AwsParameterStore) plan.source();
        out.append(" (").append(ParameterCheck.where(
            new StoreLocation(source.region(), source.awsProfile()))).append(')');
        for (String key : ParameterCheck.KEYS) {
            Optional<String> name = ParameterCheck.name(plan.names().orElseThrow(), key);
            boolean required = key.equals("username") || key.equals("password");
            if (name.isPresent() || required) {
                out.append(", ").append(key).append(" ← ").append(name.orElse(MISSING));
                parameters.status(plan.node(), key)
                    .ifPresent(status -> out.append(' ').append(status.text()));
            }
        }
    }

    /**
     * Feature 005 (research D-2): {@code Chains:} with one line per chain and, per target with
     * exclusions, {@code <group> excludes: <key> <pattern>, …}. A chain that cannot be derived
     * (an invalid {@code deploy} record or a cycle, reported among the errors) is not drawn.
     */
    private static void appendChains(StringBuilder out, ProfileConfig config,
        Map<String, DeployConfig> deploys) {
        if (deploys.isEmpty()) {
            return;
        }
        StageChain chain;
        try {
            chain = config.stageChain();
        } catch (IllegalArgumentException e) {
            out.append("Chains: not shown, see the errors\n");
            return;
        }
        out.append("Chains:\n");
        chain.render().forEach(line -> out.append("  ").append(line).append('\n'));
        chain.targets().forEach((target, link) -> {
            if (!link.exclude().isEmpty()) {
                out.append("  ").append(target).append(" excludes: ").append(String.join(", ",
                    link.exclude().stream().map(StageChain.Exclusion::toString).toList()))
                    .append('\n');
            }
        });
    }

    /**
     * Feature 009 (FR-011a): {@code , client: <version> (<from>–<to>)} (without range if the
     * client declares none), then {@code , client vs pin: match | mismatch | not comparable}
     * for a concrete pin, followed by {@code , pin outside client range} if the client's range
     * excludes the pin (FR-011), else {@code , server version: determined at runtime}.
     */
    private static String client(VersionSetting setting, ClientVersion client) {
        String range = client.supportedFrom().map(from -> " (" + from.raw() + "–"
            + client.supportedTo().orElseThrow().raw() + ")").orElse("");
        String server = setting instanceof VersionSetting.Pinned pinned
            ? "client vs pin: " + switch (client.compare(Optional.of(pinned.version()))) {
                case MATCH -> "match";
                case MISMATCH -> "mismatch";
                case NOT_COMPARABLE -> "not comparable";
            } + (client.compatibleWith(Optional.of(pinned.version()))
                == ClientVersion.Compatibility.INCOMPATIBLE ? ", pin outside client range" : "")
            : "server version: determined at runtime";
        return ", client: " + client.version().raw() + range + ", " + server;
    }

    /** CLI home configured and {@code startcli} found (data-model.md → NodeSummary). */
    boolean cliAvailable(EffectiveNodeConfig server) {
        return server.cliAvailable(exists, windows);
    }

    /**
     * Feature 004 (FR-005): {@code development: on (confirmation …) | off, e2e: FREE (<url>) |
     * CONFIRM (<url>) | FORBIDDEN}; the URL without user information. Feature 008: a development
     * node adds {@code repository: write | read-only} (the effective switch) after its
     * confirmation.
     */
    private static String development(EffectiveNodeConfig server) {
        DevelopmentPolicy policy = server.developmentPolicy();
        String development = policy.enabled() ? "development: on (confirmation "
            + policy.confirmation() + "), repository: "
            + (policy.repositoryWrite() ? "write" : "read-only") : "development: off";
        String e2e = "e2e: " + policy.e2eTests() + (policy.e2eTests() == E2ePolicy.FORBIDDEN
            ? "" : policy.soapBaseUrl().map(url -> " (" + withoutUserInfo(url) + ")")
                .orElse(""));
        return development + ", " + e2e;
    }

    private static String withoutUserInfo(URI url) {
        try {
            return new URI(url.getScheme(), null, url.getHost(), url.getPort(), url.getPath(),
                null, null).toString();
        } catch (URISyntaxException e) {
            return url.getScheme() + "://" + url.getHost();
        }
    }

    private static String writeFlag(EffectiveNodeConfig server) {
        if (server.effectiveWriteEnabled()) {
            return "write enabled (confirmation " + server.write().confirmation() + ")";
        }
        if (server.write().enabled() && server.production()) {
            return "read-only (production, no productionOptIn)";
        }
        return "read-only";
    }

    private static void appendList(StringBuilder out, String title, List<String> lines) {
        if (!lines.isEmpty()) {
            out.append(title).append('\n');
            lines.forEach(line -> out.append("  - ").append(line).append('\n'));
        }
    }
}
