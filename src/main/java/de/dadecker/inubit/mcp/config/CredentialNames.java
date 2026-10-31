package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The credential environment variables of one node (002 research D-6,
 * contracts/configuration.md → Credential environment variables):
 * {@code <PREFIX>_<GROUP>_<NODE>_<KIND>} (node-specific) and {@code <PREFIX>_<GROUP>_<KIND>}
 * (group-wide), with {@code KIND} one of {@link #USERNAME}, {@link #PASSWORD},
 * {@link #TRUSTSTORE_PASSWORD}. Group and node names are upper-cased and every character other
 * than {@code A-Z0-9} becomes {@code _}.
 *
 * @param prefix the profile's effective prefix ({@code credentials.envPrefix}, or
 *     {@code INUBIT_<PROFILE>}); not validated here, so that messages can name the variables
 *     even of a configuration with errors
 */
public record CredentialVariables(String prefix, NodeId node) {

    public static final String USERNAME = "USERNAME";
    public static final String PASSWORD = "PASSWORD";
    public static final String TRUSTSTORE_PASSWORD = "TRUSTSTORE_PASSWORD";
    /** Every kind of the node credentials, in this order. */
    public static final List<String> KINDS = List.of(USERNAME, PASSWORD, TRUSTSTORE_PASSWORD);
    /** The optional basic authentication of the SOAP end-to-end tests (feature 004). */
    public static final String E2E_USERNAME = "E2E_USERNAME";
    public static final String E2E_PASSWORD = "E2E_PASSWORD";
    /** The kinds of the end-to-end test credentials. */
    public static final List<String> E2E_KINDS = List.of(E2E_USERNAME, E2E_PASSWORD);

    public CredentialVariables {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(node, "node");
    }

    /** The node-specific variable, e.g. {@code INUBIT_ACME_TEST_NODE1_PASSWORD}. */
    public String nodeVariable(String kind) {
        return groupPart() + "_" + normalize(node.name()) + "_" + kind;
    }

    /** The group-wide variable, e.g. {@code INUBIT_ACME_TEST_PASSWORD}. */
    public String groupVariable(String kind) {
        return groupPart() + "_" + kind;
    }

    /** Node-specific then group-wide variable; the first non-empty one wins. */
    public List<String> candidates(String kind) {
        return List.of(nodeVariable(kind), groupVariable(kind));
    }

    /** Both variables for a message: {@code <node variable> (or <group variable>)}. */
    public String either(String kind) {
        return nodeVariable(kind) + " (or " + groupVariable(kind) + ")";
    }

    private String groupPart() {
        return prefix + "_" + normalize(node.group().value());
    }

    /** Upper case; every character other than {@code A-Z0-9} becomes {@code _}. */
    static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }
}
