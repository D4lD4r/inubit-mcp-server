package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Objects;
import java.util.Optional;

/**
 * The names of one node's secrets in messages, per source (feature 010, data-model.md →
 * SecretLabels, research R-15, FR-008). Names only, never values.
 */
public sealed interface SecretLabels {

    /** The node. */
    NodeId node();

    /**
     * The human-readable name of one kind: environment {@code <NODE_VAR> (or <GROUP_VAR>)}, AWS
     * {@code Parameter Store <name> (<region>[, profile <p>])}.
     *
     * @param kind one of {@link CredentialNames#KINDS} or {@link CredentialNames#E2E_KINDS}
     */
    String label(String kind);

    /** The next step after rejected or missing credentials, for the node's source. */
    String fixCredentials();

    /**
     * Completes "the certificates are encrypted and …" of a trust store that is unreadable
     * without a password, e.g. "neither … is set".
     */
    String trustStorePasswordMissing();

    /** Completes "… or …": how to supply the trust-store password, e.g. "set …". */
    String supplyTrustStorePassword();

    /** The labels of the node of {@code plan}. */
    static SecretLabels of(SecretPlan plan) {
        if (plan.source() instanceof SecretSourceConfig.AwsParameterStore store) {
            return new Aws(plan.node(), new StoreLocation(store.region(), store.awsProfile()),
                plan.names().orElseThrow());
        }
        return new Environment(plan.entries().orElseThrow());
    }

    /** Environment variables: the texts of features 002–009. */
    record Environment(CredentialNames variables) implements SecretLabels {

        public Environment {
            Objects.requireNonNull(variables, "variables");
        }

        @Override
        public NodeId node() {
            return variables.node();
        }

        @Override
        public String label(String kind) {
            return variables.either(kind);
        }

        @Override
        public String fixCredentials() {
            return "Fix " + variables.either(CredentialNames.PASSWORD) + " and _USERNAME,"
                + " then restart the MCP client from a shell with the new values";
        }

        @Override
        public String trustStorePasswordMissing() {
            return "neither " + label(CredentialNames.TRUSTSTORE_PASSWORD) + " is set";
        }

        @Override
        public String supplyTrustStorePassword() {
            return "set " + label(CredentialNames.TRUSTSTORE_PASSWORD);
        }
    }

    /**
     * AWS Parameter Store. A kind without a name is shown as its {@code credentials.parameters}
     * setting; for {@code username} and {@code password} that is a configuration error
     * ({@link ConfigValidator}), so {@link #fixCredentials()} marks it "(not configured)".
     */
    record Aws(NodeId node, StoreLocation location, ParameterNames names)
        implements SecretLabels {

        public Aws {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(names, "names");
        }

        @Override
        public String label(String kind) {
            return name(kind).map(name -> "Parameter Store " + name + " (" + where() + ")")
                .orElse(setting(kind) + " (Parameter Store, " + where() + ")");
        }

        @Override
        public String trustStorePasswordMissing() {
            String kind = CredentialNames.TRUSTSTORE_PASSWORD;
            return name(kind).isPresent() ? "no trust store password is set in " + label(kind)
                : "no trust store password parameter is configured (" + setting(kind) + ")";
        }

        @Override
        public String supplyTrustStorePassword() {
            String kind = CredentialNames.TRUSTSTORE_PASSWORD;
            return name(kind).isPresent() ? "store its password in " + label(kind)
                : "store its password in Parameter Store (" + where() + ") and set "
                    + setting(kind) + " to its name";
        }

        @Override
        public String fixCredentials() {
            return "Correct the parameter value(s) " + nameOrSetting(CredentialNames.USERNAME)
                + ", " + nameOrSetting(CredentialNames.PASSWORD) + " in Parameter Store ("
                + where() + "); no restart is needed";
        }

        private String where() {
            return location.region() + location.awsProfile().map(p -> ", profile " + p).orElse("");
        }

        private String nameOrSetting(String kind) {
            return name(kind).orElse(setting(kind) + " (not configured)");
        }

        private static String setting(String kind) {
            return "credentials.parameters." + key(kind);
        }

        private Optional<String> name(String kind) {
            return switch (kind) {
                case CredentialNames.USERNAME -> names.username();
                case CredentialNames.PASSWORD -> names.password();
                case CredentialNames.TRUSTSTORE_PASSWORD -> names.trustStorePassword();
                case CredentialNames.E2E_USERNAME -> names.e2eUsername();
                case CredentialNames.E2E_PASSWORD -> names.e2ePassword();
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };
        }

        /** The YAML key of {@code kind} under {@code credentials.parameters}. */
        private static String key(String kind) {
            return switch (kind) {
                case CredentialNames.USERNAME -> "username";
                case CredentialNames.PASSWORD -> "password";
                case CredentialNames.TRUSTSTORE_PASSWORD -> "trustStorePassword";
                case CredentialNames.E2E_USERNAME -> "e2eUsername";
                case CredentialNames.E2E_PASSWORD -> "e2ePassword";
                default -> throw new IllegalArgumentException("Unknown kind: " + kind);
            };
        }
    }
}
