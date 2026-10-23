package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.Terminology;
import de.dadecker.inubit.mcp.infra.Secret;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves credentials from environment variables (research R-12, data-model.md → Credentials;
 * 002 research D-6).
 *
 * <ul>
 *   <li>Names ({@link CredentialVariables}): {@code <PREFIX>_<GROUP>_<NODE>_<KIND>} then
 *       {@code <PREFIX>_<GROUP>_<KIND>}, with {@code PREFIX} the profile's effective prefix and
 *       {@code KIND} one of {@code USERNAME}, {@code PASSWORD}, {@code TRUSTSTORE_PASSWORD}; the
 *       first non-empty value wins, each kind independently. Variables under another prefix are
 *       never read.
 *   <li>Derived names that two nodes or groups would share are a startup error.
 *   <li>Passwords, the Basic-auth token and trust-store passwords are registered with the
 *       {@link SecretScrubber}.
 *   <li>A {@code <PREFIX>_*_PASSWORD} variable that belongs to no configured node is a warning.
 *   <li>Messages name the levels with the profile's terminology.
 *   <li>Feature 010 (research R-6): each node follows its {@link SecretPlan}. An environment node
 *       reads the plan's variables (its source's {@code prefix} or the profile's); an AWS node is
 *       not looked up and gets {@link NodeCredentials#deferred(NodeId)} without a missing-variable
 *       error. A node without a plan reads the variables under the profile's prefix (a profile
 *       without {@code credentials.source}). The collision check and the unmatched-variable
 *       warning consider only environment nodes (research R-13).
 * </ul>
 */
public final class CredentialResolver {

    static final String USERNAME = CredentialVariables.USERNAME;
    static final String PASSWORD = CredentialVariables.PASSWORD;
    static final String TRUSTSTORE_PASSWORD = CredentialVariables.TRUSTSTORE_PASSWORD;
    private static final List<String> KINDS = CredentialVariables.KINDS;

    private final Map<String, String> environment;
    private final SecretScrubber scrubber;
    private final Terminology terms;
    private final String prefix;
    private final Map<NodeId, SecretPlan> plans;

    /**
     * With the default terminology in messages; every node reads environment variables (for
     * profiles without {@code credentials.source}).
     *
     * @param prefix the effective credential prefix ({@link ProfileConfig#credentialPrefix()})
     */
    public CredentialResolver(Map<String, String> environment, SecretScrubber scrubber,
        String prefix) {
        this(environment, scrubber, Terminology.DEFAULT, prefix);
    }

    /**
     * Every node reads environment variables (for profiles without {@code credentials.source}).
     *
     * @param terms  the display names of the levels in messages (FR-007)
     * @param prefix the effective credential prefix ({@link ProfileConfig#credentialPrefix()})
     */
    public CredentialResolver(Map<String, String> environment, SecretScrubber scrubber,
        Terminology terms, String prefix) {
        this(environment, scrubber, terms, prefix, Map.of());
    }

    /**
     * @param terms  the display names of the levels in messages (FR-007)
     * @param prefix the effective credential prefix ({@link ProfileConfig#credentialPrefix()})
     * @param plans  the secret plan of each node ({@link ProfileConfig#secretPlans()})
     */
    public CredentialResolver(Map<String, String> environment, SecretScrubber scrubber,
        Terminology terms, String prefix, Map<NodeId, SecretPlan> plans) {
        this.environment = Map.copyOf(environment);
        this.scrubber = scrubber;
        this.terms = Objects.requireNonNull(terms, "terms");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.plans = Map.copyOf(plans);
    }

    /**
     * The variables of {@code node}: those of its plan; for an AWS node or a node without a plan
     * those under this resolver's prefix (like {@link EffectiveNodeConfig#credentialVariables()}).
     */
    public CredentialVariables variables(NodeId node) {
        return Optional.ofNullable(plans.get(node)).flatMap(SecretPlan::variables)
            .orElseGet(() -> new CredentialVariables(prefix, node));
    }

    private boolean aws(NodeId node) {
        SecretPlan plan = plans.get(node);
        return plan != null && plan.aws();
    }

    /**
     * The optional basic authentication of the SOAP end-to-end tests of a node (feature 004,
     * research D-25 H8): {@code <PREFIX>_<GROUP>[_<NODE>]_E2E_USERNAME} and
     * {@code …_E2E_PASSWORD}, node-specific before group-wide, like the node credentials.
     */
    public record E2eCredentials(SourcedValue<String> username, SourcedValue<Secret> password) {
        public E2eCredentials {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }
    }

    /**
     * The end-to-end test credentials of {@code server}: both variables set and a safe username
     * ({@link CredentialRules#isSafeUsername}), else none. The password is registered with the
     * scrubber. None for an AWS node: its values are fetched on first use.
     */
    public Optional<E2eCredentials> e2e(NodeId server) {
        if (aws(server)) {
            return Optional.empty();
        }
        Optional<SourcedValue<String>> username = lookup(server, CredentialVariables.E2E_USERNAME)
            .filter(value -> CredentialRules.isSafeUsername(value.value()));
        Optional<SourcedValue<String>> password = lookup(server,
            CredentialVariables.E2E_PASSWORD);
        if (username.isEmpty() || password.isEmpty()) {
            return Optional.empty();
        }
        SourcedValue<Secret> secret = register(password.get());
        scrubber.registerBasicAuth(username.get().value(), secret.value());
        return Optional.of(new E2eCredentials(username.get(), secret));
    }

    /** {@link #resolve(List, Set)} without other known profiles. */
    public CredentialResolution resolve(List<NodeId> servers) {
        return resolve(servers, Set.of());
    }

    /**
     * Resolves the credentials of {@code servers}.
     *
     * @param otherProfiles the credential variable names of the other profiles that are known
     *     (those in the default configuration directory); they are never reported as unmatched,
     *     even if they start with this prefix (002 US2 review P2)
     */
    public CredentialResolution resolve(List<NodeId> servers, Set<String> otherProfiles) {
        List<String> errors = new ArrayList<>(collisions(servers));
        List<NodeCredentials> all = new ArrayList<>();
        for (NodeId server : servers) {
            if (aws(server)) {
                all.add(NodeCredentials.deferred(server));
                continue;
            }
            Optional<SourcedValue<String>> username = lookup(server, USERNAME);
            Optional<SourcedValue<Secret>> password = lookup(server, PASSWORD).map(this::register);
            Optional<SourcedValue<Secret>> trustStorePassword =
                lookup(server, TRUSTSTORE_PASSWORD).map(this::register);
            if (username.isEmpty()) {
                errors.add(missing(server, "username", USERNAME));
            } else if (!CredentialRules.isSafeUsername(username.get().value())) {
                errors.add(server + ": the username in " + username.get().sourceVariable()
                    + " " + CredentialRules.SAFE_USERNAME_RULE);
            }
            if (password.isEmpty()) {
                errors.add(missing(server, "password", PASSWORD));
            }
            Optional<Secret> token = username.isPresent() && password.isPresent()
                ? Optional.of(scrubber.registerBasicAuth(username.get().value(),
                    password.get().value()))
                : Optional.empty();
            all.add(new NodeCredentials(server, username, password, trustStorePassword, token));
        }
        return new CredentialResolution(all, errors,
            unmatchedPasswordVariables(servers, otherProfiles));
    }

    private Optional<SourcedValue<String>> lookup(NodeId server, String kind) {
        for (String variable : variables(server).candidates(kind)) {
            String value = environment.get(variable);
            if (CredentialRules.isSet(value)) {
                return Optional.of(new SourcedValue<>(value, variable));
            }
        }
        return Optional.empty();
    }

    private SourcedValue<Secret> register(SourcedValue<String> value) {
        return new SourcedValue<>(scrubber.register(value.value()), value.sourceVariable());
    }

    private String missing(NodeId server, String what, String kind) {
        List<String> names = variables(server).candidates(kind);
        return server + ": no " + what + " variable set (expected " + names.get(0) + " or "
            + names.get(1) + ")";
    }

    /**
     * Groups every derived name of the node credentials by the groups/nodes it is for; environment
     * nodes only (research R-13).
     */
    private Map<String, Set<String>> owners(List<NodeId> servers) {
        return owners(servers, KINDS);
    }

    /**
     * Groups every derived name of {@code kinds} by the groups/nodes it is derived for. The
     * end-to-end kinds count as known names (no typo warning) but not for the collision check:
     * a node named {@code e2e} clashes only where e2e tests are allowed (ConfigValidator). AWS
     * nodes derive no names (research R-13): they neither collide nor make a variable known.
     */
    private Map<String, Set<String>> owners(List<NodeId> servers, List<String> kinds) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (NodeId server : servers) {
            if (aws(server)) {
                continue;
            }
            for (String kind : kinds) {
                String serverVariable = variables(server).nodeVariable(kind);
                String stageVariable = variables(server).groupVariable(kind);
                owners.computeIfAbsent(serverVariable, k -> new TreeSet<>())
                    .add(terms.render("{node} ") + server);
                owners.computeIfAbsent(stageVariable, k -> new TreeSet<>())
                    .add(terms.render("{group} ") + server.group());
            }
        }
        return owners;
    }

    private List<String> collisions(List<NodeId> servers) {
        Map<Set<String>, List<String>> byOwners = new LinkedHashMap<>();
        owners(servers).forEach((variable, owners) -> {
            if (owners.size() > 1) {
                byOwners.computeIfAbsent(owners, k -> new ArrayList<>()).add(variable);
            }
        });
        List<String> errors = new ArrayList<>();
        byOwners.forEach((owners, variables) -> errors.add("Credential variable names collide: "
            + String.join(", ", variables) + " would be used by " + String.join(" and ", owners)
            + terms.render("; rename one of the {groups} or {nodes}")));
        return errors;
    }

    private List<String> unmatchedPasswordVariables(List<NodeId> servers,
        Set<String> otherProfiles) {
        Set<String> known = owners(servers, Stream.concat(KINDS.stream(),
            CredentialVariables.E2E_KINDS.stream()).toList()).keySet();
        Set<String> unmatched = new TreeSet<>();
        for (String variable : environment.keySet()) {
            if (variable.startsWith(prefix + "_") && variable.endsWith("_" + PASSWORD)
                && !known.contains(variable) && !otherProfiles.contains(variable)) {
                unmatched.add(variable);
            }
        }
        return unmatched.stream()
            .map(variable -> "Environment variable " + variable
                + terms.render(" matches no configured {group} or {node} (typo?)"))
            .toList();
    }
}
