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

    /**
     * With the default terminology in messages.
     *
     * @param prefix the effective credential prefix ({@link ProfileConfig#credentialPrefix()})
     */
    public CredentialResolver(Map<String, String> environment, SecretScrubber scrubber,
        String prefix) {
        this(environment, scrubber, Terminology.DEFAULT, prefix);
    }

    /**
     * @param terms  the display names of the levels in messages (FR-007)
     * @param prefix the effective credential prefix ({@link ProfileConfig#credentialPrefix()})
     */
    public CredentialResolver(Map<String, String> environment, SecretScrubber scrubber,
        Terminology terms, String prefix) {
        this.environment = Map.copyOf(environment);
        this.scrubber = scrubber;
        this.terms = Objects.requireNonNull(terms, "terms");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    /** The variables of {@code node} under this resolver's prefix. */
    public CredentialVariables variables(NodeId node) {
        return new CredentialVariables(prefix, node);
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
     * ({@link #isSafeUsername}), else none. The password is registered with the scrubber.
     */
    public Optional<E2eCredentials> e2e(NodeId server) {
        Optional<SourcedValue<String>> username = lookup(server, CredentialVariables.E2E_USERNAME)
            .filter(value -> isSafeUsername(value.value()));
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
            Optional<SourcedValue<String>> username = lookup(server, USERNAME);
            Optional<SourcedValue<Secret>> password = lookup(server, PASSWORD).map(this::register);
            Optional<SourcedValue<Secret>> trustStorePassword =
                lookup(server, TRUSTSTORE_PASSWORD).map(this::register);
            if (username.isEmpty()) {
                errors.add(missing(server, "username", USERNAME));
            } else if (!isSafeUsername(username.get().value())) {
                errors.add(server + ": the username in " + username.get().sourceVariable()
                    + " must not contain ':' or control characters and must not start with '-'"
                    + " (it is used in the Basic-auth header and as StartCLI argument)");
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

    /** No ':' (Basic auth), no control characters, no leading '-' (StartCLI option). */
    static boolean isSafeUsername(String username) {
        return !username.startsWith("-") && username.indexOf(':') < 0
            && username.chars().noneMatch(Character::isISOControl);
    }

    private Optional<SourcedValue<String>> lookup(NodeId server, String kind) {
        for (String variable : variables(server).candidates(kind)) {
            String value = environment.get(variable);
            if (value != null && !value.isEmpty()) {
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

    /** Groups every derived name by the groups/nodes it is derived for. */
    private Map<String, Set<String>> owners(List<NodeId> servers) {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        for (NodeId server : servers) {
            for (String kind : Stream.concat(KINDS.stream(),
                CredentialVariables.E2E_KINDS.stream()).toList()) {
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
        Set<String> known = owners(servers).keySet();
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
