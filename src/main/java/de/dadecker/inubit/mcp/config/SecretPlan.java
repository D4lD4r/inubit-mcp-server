package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the credentials of one node come from (feature 010, data-model.md → SecretPlan, research
 * R-6), computed at load time. Holds names only, never values.
 *
 * @param node      the node
 * @param source    the effective source: node → group → profile → credential store (default)
 * @param origin    where {@code source} was declared
 * @param entries   the credential store entry names (credential store source only), under the
 *                  source's {@code prefix}, else the profile-wide prefix, else the default prefix
 * @param names     the Parameter Store names (AWS source only): per kind the node's name, else the
 *                  group's name if the node has no own {@code source}; kinds may be missing here,
 *                  {@link ConfigValidator} reports them
 */
public record SecretPlan(
    NodeId node,
    SecretSourceConfig source,
    Origin origin,
    Optional<CredentialNames> entries,
    Optional<ParameterNames> names) {

    /** The level that declared the effective source. */
    public enum Origin { NODE, GROUP, PROFILE, DEFAULT }

    public SecretPlan {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(names, "names");
        boolean store = source instanceof SecretSourceConfig.SystemStore;
        if (store != entries.isPresent() || store == names.isPresent()) {
            throw new IllegalArgumentException("A credential store plan has entry names, an AWS plan"
                + " has parameter names");
        }
        if (entries.isPresent() && !entries.get().node().equals(node)) {
            throw new IllegalArgumentException("The entry names belong to another node");
        }
    }

    /** True if the effective source is AWS Parameter Store. */
    public boolean aws() {
        return source instanceof SecretSourceConfig.AwsParameterStore;
    }

    /**
     * The source the nodes of {@code group} inherit: the group's, else the profile's, else the
     * credential store.
     *
     * @param profile the top-level {@code credentials} section
     */
    static SecretSourceConfig groupSource(CredentialsConfig profile, GroupConfig group) {
        return group.credentials().source().or(profile::source)
            .orElseGet(() -> new SecretSourceConfig.SystemStore(Optional.empty()));
    }

    /**
     * The plan of {@code node} in {@code group}.
     *
     * @param profile       the top-level {@code credentials} section
     * @param profilePrefix {@link ProfileConfig#credentialPrefix()}
     */
    static SecretPlan resolve(CredentialsConfig profile, String profilePrefix, GroupConfig group,
        NodeConfig node) {
        NodeId id = NodeId.of(group.name(), node.name());
        Optional<SecretSourceConfig> own = node.credentials().source();
        SecretSourceConfig source;
        Origin origin;
        if (own.isPresent()) {
            source = own.get();
            origin = Origin.NODE;
        } else {
            source = groupSource(profile, group);
            origin = group.credentials().source().isPresent() ? Origin.GROUP
                : profile.source().isPresent() ? Origin.PROFILE : Origin.DEFAULT;
        }
        if (source instanceof SecretSourceConfig.SystemStore store) {
            String prefix = store.prefix().orElse(profilePrefix);
            return new SecretPlan(id, source, origin,
                Optional.of(new CredentialNames(prefix, id)), Optional.empty());
        }
        ParameterNames nodeNames = node.credentials().parameters().orElse(ParameterNames.EMPTY);
        ParameterNames groupNames = own.isPresent() ? ParameterNames.EMPTY
            : group.credentials().parameters().orElse(ParameterNames.EMPTY);
        ParameterNames names = new ParameterNames(
            nodeNames.username().or(groupNames::username),
            nodeNames.password().or(groupNames::password),
            nodeNames.trustStorePassword().or(groupNames::trustStorePassword),
            nodeNames.e2eUsername().or(groupNames::e2eUsername),
            nodeNames.e2ePassword().or(groupNames::e2ePassword));
        return new SecretPlan(id, source, origin, Optional.empty(), Optional.of(names));
    }
}
