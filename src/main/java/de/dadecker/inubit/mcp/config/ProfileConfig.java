package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.GroupId;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ProfileInfo;
import de.dadecker.inubit.mcp.domain.model.StageChain;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The whole YAML configuration of one profile (data-model.md → ProfileConfig; 002
 * contracts/configuration.md, format v2).
 *
 * @param workspace the artifact workspace of feature 003 (contracts/configuration-delta.md);
 *                  {@link ConfigLoader} sets the default {@code ~/.inubit-mcp/<profile>/workspace},
 *                  {@link ConfigValidator} checks it and creates it if missing
 */
public record ProfileConfig(
    ProfileSection profile,
    TerminologyConfig terminology,
    CredentialsConfig credentials,
    List<GroupConfig> groups,
    Defaults defaults,
    Path auditDirectory,
    LogLevel logLevel,
    ResultLimits resultLimits,
    Path workspace) {

    public ProfileConfig {
        profile = Optionals.orDefault(profile, ProfileSection.EMPTY);
        terminology = Optionals.orDefault(terminology, TerminologyConfig.EMPTY);
        credentials = Optionals.orDefault(credentials, CredentialsConfig.EMPTY);
        groups = Optionals.copyOrEmpty(groups);
        defaults = Optionals.orDefault(defaults, Defaults.EMPTY);
        Objects.requireNonNull(auditDirectory, "auditDirectory");
        logLevel = Optionals.orDefault(logLevel, LogLevel.INFO);
        resultLimits = Optionals.orDefault(resultLimits, ResultLimits.DEFAULT);
        Objects.requireNonNull(workspace, "workspace");
    }

    /**
     * The profile with its effective terminology and credential prefix.
     *
     * @throws IllegalArgumentException if the profile, terminology or credentials section is
     *     invalid; call this only after {@link ConfigValidator} reported no errors
     */
    public ProfileInfo profileInfo() {
        return new ProfileInfo(profile.name(), profile.description(), terminology.effective(),
            credentialPrefix());
    }

    /**
     * The effective prefix of the credential variables (002 research D-6):
     * {@code credentials.envPrefix}, or {@code INUBIT_} plus the normalized profile name. Not
     * validated, so that the configuration check can name the variables even of a file with
     * errors; {@link ConfigValidator} reports an invalid prefix or profile name.
     */
    public String credentialPrefix() {
        return credentials.envPrefix()
            .orElseGet(() -> ProfileInfo.defaultCredentialPrefix(profile.name()));
    }

    /**
     * Every credential variable name this profile derives: per node, the node-specific and the
     * group-wide name of each kind, under {@link #credentialPrefix()} (002 US2 review P1).
     */
    public Set<String> credentialVariableNames() {
        Set<String> names = new TreeSet<>();
        for (NodeId node : nodeIds()) {
            CredentialVariables variables = new CredentialVariables(credentialPrefix(), node);
            for (String kind : CredentialVariables.KINDS) {
                names.addAll(variables.candidates(kind));
            }
            // feature 004 (review m-g): the optional e2e basic authentication
            for (String kind : CredentialVariables.E2E_KINDS) {
                names.addAll(variables.candidates(kind));
            }
        }
        return names;
    }

    /**
     * The fully resolved settings of every server, in config order.
     *
     * @throws IllegalArgumentException if a stage or server name is invalid or a base URL is
     *     missing; call this only after {@link ConfigValidator} reported no errors
     */
    public List<EffectiveNodeConfig> effectiveNodes() {
        return nodes(true);
    }

    /**
     * The resolved settings of every server whose stage and server names are valid and whose
     * {@code baseUrl} is set, in config order. {@link ConfigValidator} reports the others.
     */
    public List<EffectiveNodeConfig> resolvableNodes() {
        return nodes(false);
    }

    /**
     * The stage chain of feature 005 (research D-2) from the {@code deploy} records of the
     * groups, in config order.
     *
     * @throws IllegalArgumentException if a group name, a {@code deploy.from} or an exclusion
     *     is invalid or the chain has a cycle; call this only after {@link ConfigValidator}
     *     reported no errors
     */
    public StageChain stageChain() {
        Map<GroupId, StageChain.ChainLink> targets = new LinkedHashMap<>();
        for (GroupConfig group : groups) {
            group.deploy().ifPresent(deploy -> targets.put(new GroupId(group.name()),
                new StageChain.ChainLink(new GroupId(deploy.from().strip()), deploy.mode(),
                    deploy.exclude().stream().map(ProfileConfig::exclusion).toList())));
        }
        return new StageChain(targets);
    }

    private static StageChain.Exclusion exclusion(ExcludeRule rule) {
        List<StageChain.Exclusion> set = new ArrayList<>();
        rule.diagramGroup().ifPresent(value -> set.add(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.DIAGRAM_GROUP, value.strip())));
        rule.name().ifPresent(value -> set.add(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.NAME, value.strip())));
        rule.repositoryPath().ifPresent(value -> set.add(new StageChain.Exclusion(
            StageChain.Exclusion.Kind.REPOSITORY_PATH, value.strip())));
        if (set.size() != 1) {
            throw new IllegalArgumentException("An exclude entry needs exactly one key");
        }
        return set.get(0);
    }

    /** Ids of all servers with valid stage and server names (with or without base URL). */
    public List<NodeId> nodeIds() {
        List<NodeId> ids = new ArrayList<>();
        for (GroupConfig group : groups) {
            for (NodeConfig node : group.nodes()) {
                if (GroupId.isValidName(group.name()) && GroupId.isValidName(node.name())) {
                    ids.add(NodeId.of(group.name(), node.name()));
                }
            }
        }
        return List.copyOf(ids);
    }

    private List<EffectiveNodeConfig> nodes(boolean strict) {
        List<EffectiveNodeConfig> result = new ArrayList<>();
        for (GroupConfig group : groups) {
            for (NodeConfig node : group.nodes()) {
                boolean resolvable = GroupId.isValidName(group.name())
                    && GroupId.isValidName(node.name()) && node.baseUrl().isPresent();
                if (strict || resolvable) {
                    result.add(EffectiveNodeConfig.resolve(defaults, group, node,
                        credentialPrefix()));
                }
            }
        }
        return List.copyOf(result);
    }
}
