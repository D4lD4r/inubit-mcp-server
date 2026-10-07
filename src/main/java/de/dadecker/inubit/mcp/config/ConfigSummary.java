package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.domain.model.DevelopmentPolicy;
import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Text printed by {@code --check-config}: the profile, its terminology, credential variable
 * scheme, audit directory and workspace ({@code ok}, {@code created} or the problem), then per
 * group the nodes with their id, the effective write flag, the development and end-to-end test
 * settings (feature 004, once any node configures them; with the SOAP base address but never
 * its user information), CLI
 * availability and the source variable of each credential, then all
 * warnings and errors.
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

    public String render(LoadedConfig loaded, CredentialResolution credentials,
        ValidationReport report) {
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
        // 002 US2: what keeps this profile apart from others running side by side
        out.append("Credential variables: ").append(config.credentialPrefix()).append('_')
            .append(ConfigValidator.variablePart(terms)).append("_USERNAME / _PASSWORD\n");
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
                .append(", cli: ").append(cliAvailable(server) ? "available" : "unavailable");
            Optional<NodeCredentials> serverCredentials = credentials.all().stream()
                .filter(c -> c.node().equals(server.id()))
                .findFirst();
            out.append(", username ← ").append(serverCredentials
                .flatMap(NodeCredentials::username).map(SourcedValue::sourceVariable)
                .orElse(MISSING));
            out.append(", password ← ").append(serverCredentials
                .flatMap(NodeCredentials::password).map(SourcedValue::sourceVariable)
                .orElse(MISSING));
            serverCredentials.flatMap(NodeCredentials::trustStorePassword)
                .ifPresent(ts -> out.append(", trustStorePassword ← ").append(ts.sourceVariable()));
            out.append('\n');
        }
        appendList(out, "Warnings:", report.warnings());
        appendList(out, "Errors:", report.errors());
        out.append(report.hasErrors()
            ? "Result: FAILED (" + report.errors().size() + " error(s))"
            : "Result: OK").append('\n');
        return out.toString();
    }

    /** CLI home configured and {@code startcli} found (data-model.md → NodeSummary). */
    boolean cliAvailable(EffectiveNodeConfig server) {
        return server.cliAvailable(exists, windows);
    }

    /**
     * Feature 004 (FR-005): {@code development: on (confirmation …) | off, e2e: FREE (<url>) |
     * CONFIRM (<url>) | FORBIDDEN}; the URL without user information.
     */
    private static String development(EffectiveNodeConfig server) {
        DevelopmentPolicy policy = server.developmentPolicy();
        String development = policy.enabled() ? "development: on (confirmation "
            + policy.confirmation() + ")" : "development: off";
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
