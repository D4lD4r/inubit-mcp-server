package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.util.Objects;
import java.util.Optional;

/**
 * The names of one node's secrets in messages, per source (feature 010, data-model.md →
 * SecretLabels, research R-15, FR-008; feature 011, research R-12: the credential store replaces
 * environment variables). Names only, never values.
 */
public sealed interface SecretLabels {

    /** The node. */
    NodeId node();

    /**
     * The human-readable name of one kind: {@code credential store entry <NODE> (or <GROUP>) in
     * the <store>}, AWS {@code Parameter Store <name> (<region>[, profile <p>])}.
     *
     * @param kind one of {@link CredentialNames#KINDS} or {@link CredentialNames#E2E_KINDS}
     */
    String label(String kind);

    /** The next step after rejected or missing credentials, for the node's source. */
    String fixCredentials();

    /** The source in a sentence: "AWS Parameter Store", "the macOS Keychain". */
    String source();

    /**
     * Completes "the certificates are encrypted and …" of a trust store that is unreadable
     * without a password, e.g. "neither … is set".
     */
    String trustStorePasswordMissing();

    /** Completes "… or …": how to supply the trust-store password, e.g. "set …". */
    String supplyTrustStorePassword();

    /**
     * The labels of the node of {@code plan}.
     *
     * @param store the credential store of the running system, named in the texts of a credential
     *     store node
     */
    static SecretLabels of(SecretPlan plan, StoreKind store) {
        if (plan.source() instanceof SecretSourceConfig.AwsParameterStore aws) {
            return new Aws(plan.node(), new StoreLocation(aws.region(), aws.awsProfile()),
                plan.names().orElseThrow());
        }
        return new Store(plan.entries().orElseThrow(), store);
    }

    /**
     * The credential store of the running system (feature 011, research R-12): the entry names
     * (node entry, else group entry) and the store; a changed entry needs no restart, it is read
     * again after a rejected login.
     */
    record Store(CredentialNames names, StoreKind store) implements SecretLabels {

        public Store {
            Objects.requireNonNull(names, "names");
            Objects.requireNonNull(store, "store");
        }

        @Override
        public NodeId node() {
            return names.node();
        }

        @Override
        public String label(String kind) {
            return "credential store entry " + names.either(kind) + " in the "
                + store.displayName();
        }

        @Override
        public String fixCredentials() {
            return "Correct the credential store entries " + names.either(CredentialNames.USERNAME)
                + " and " + names.either(CredentialNames.PASSWORD) + " (service "
                + CredentialStore.SERVICE + ") in the " + store.displayName()
                + "; no restart is needed";
        }

        @Override
        public String source() {
            return "the " + store.displayName();
        }

        @Override
        public String trustStorePasswordMissing() {
            return "no trust store password entry " + names.either(
                CredentialNames.TRUSTSTORE_PASSWORD) + " is in the " + store.displayName();
        }

        @Override
        public String supplyTrustStorePassword() {
            return "store its password in the " + store.displayName() + " as entry "
                + names.either(CredentialNames.TRUSTSTORE_PASSWORD) + ", service "
                + CredentialStore.SERVICE;
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

        @Override
        public String source() {
            return "AWS Parameter Store";
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
