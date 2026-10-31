package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.E2ePolicy;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The credential store part of {@code --check-config} (feature 011, research R-13, FR-011): reads,
 * for every credential store node, the required kinds (node entry, else group entry) and the
 * optional kinds when present, and checks each value with {@link CredentialRules} as at run
 * time. Each distinct entry is read at most once per run (a group entry shared by many nodes opens
 * at most one access dialog); the reads of one node are bounded by {@link #TIMEOUT}. Values are
 * only checked, never kept or described: each read is classified at once into a value-free
 * outcome. The raw store text is never part of an error or warning; an unavailable store is
 * reported with a fixed reason per store. End-to-end entries are read only for nodes whose
 * end-to-end tests are not {@code FORBIDDEN} (at run time they are never read otherwise).
 *
 * <p>Errors: a required entry missing, an invalid value, access denied, a trust store password
 * entry of a node with a CLI, only one of the two end-to-end entries, end-to-end entries with an
 * {@code http://} {@code e2e.soap.baseUrl} while end-to-end tests are allowed. Warnings: a trust
 * store password entry without {@code tls.trustStore} ("ignored"); a store that cannot be asked
 * (not available, or no answer in time): one warning, the rest "not checked".
 */
public final class EntryCheck {

    /** The bound of the reads of one node (as at run time, research R-8). */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** The kinds in display order, as {@code credentials.parameters} keys. */
    static final List<String> KEYS =
        List.of("username", "password", "trustStorePassword", "e2eUsername", "e2ePassword");

    /** The outcome for one kind of one node. */
    public enum Status {
        OK("ok"),
        NOT_FOUND("not found"),
        INVALID_VALUE("invalid value"),
        ACCESS_DENIED("access denied"),
        /** The store could not be asked (not available, or no answer in time). */
        NOT_CHECKED("not checked");

        private final String text;

        Status(String text) {
            this.text = text;
        }

        /** As shown after the name. */
        public String text() {
            return text;
        }
    }

    /**
     * One kind of one node.
     *
     * @param shown the entry found (or read last), or {@code <node entry> | <group entry>}
     */
    public record Line(String shown, Status status) {
    }

    /**
     * The outcome of {@link #run}.
     *
     * @param lines per credential store node the line of each required kind and of each optional
     *     kind that is present
     * @param store the store that was asked
     */
    public record Result(Map<NodeId, Map<String, Line>> lines, List<String> errors,
        List<String> warnings, StoreKind store) {

        public Result {
            lines = Map.copyOf(lines);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        /** Nothing read: no credential store node. */
        public static Result none(StoreKind store) {
            return new Result(Map.of(), List.of(), List.of(), store);
        }

        /** The line of {@code key} of {@code node}, if there is one. */
        public Optional<Line> line(NodeId node, String key) {
            return Optional.ofNullable(lines.getOrDefault(node, Map.of()).get(key));
        }

        /** {@code report} with these errors and warnings appended. */
        public ValidationReport addTo(ValidationReport report) {
            List<String> allErrors = new ArrayList<>(report.errors());
            allErrors.addAll(errors);
            List<String> allWarnings = new ArrayList<>(report.warnings());
            allWarnings.addAll(warnings);
            return new ValidationReport(allErrors, allWarnings, report.workspace());
        }
    }

    private EntryCheck() {
    }

    /** Reads and checks the entries of the credential store nodes among {@code nodes}. */
    public static Result run(List<EffectiveNodeConfig> nodes, CredentialStore store) {
        return run(nodes, store, TIMEOUT);
    }

    /** With {@code timeout} instead of {@link #TIMEOUT}, so tests need not wait 60 s. */
    static Result run(List<EffectiveNodeConfig> nodes, CredentialStore store, Duration timeout) {
        List<EffectiveNodeConfig> checked = nodes.stream()
            .filter(node -> node.secretPlan().entries().isPresent()).toList();
        if (checked.isEmpty()) {
            return Result.none(store.kind());
        }
        Run run = new Run(store, timeout);
        Map<NodeId, Map<String, Line>> lines = new LinkedHashMap<>();
        List<String> incomplete = new ArrayList<>();
        for (EffectiveNodeConfig node : checked) {
            lines.put(node.id(), run.node(node));
            if (run.nodeIncomplete) {
                incomplete.add(node.id().value());
            }
        }
        if (run.unavailable != null) {
            String where = store.kind() == StoreKind.NONE
                ? "No supported credential store on this system"
                : store.kind().displayName();
            run.warnings.add(where + " for " + String.join(", ", incomplete)
                + ": resolvability not checked: " + run.unavailable);
        }
        return new Result(lines, run.errors, run.warnings, store.kind());
    }

    /** The state of one run: the entries read so far and whether the store can still be asked. */
    private static final class Run {

        private final CredentialStore store;
        private final Duration timeout;
        /** Each distinct account's value-free outcome, read once. */
        private final Map<String, Outcome> read = new HashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        /** Why the store cannot be asked, once known (a fixed text); then nothing more is read. */
        private String unavailable;
        /** Whether the node of the last {@link #node} call has a kind that was not checked. */
        private boolean nodeIncomplete;

        Run(CredentialStore store, Duration timeout) {
            this.store = store;
            this.timeout = timeout;
        }

        Map<String, Line> node(EffectiveNodeConfig node) {
            CredentialNames names = node.secretPlan().entries().orElseThrow();
            long deadline = System.nanoTime() + timeout.toNanos();
            Map<String, Line> lines = new LinkedHashMap<>();
            Map<String, String> found = new HashMap<>();
            boolean e2e = node.development().e2eTests() != E2ePolicy.FORBIDDEN;
            nodeIncomplete = false;
            for (String key : KEYS) {
                boolean required = key.equals("username") || key.equals("password");
                if (!e2e && key.startsWith("e2e")) {
                    continue;
                }
                String kind = kind(key);
                Line line = line(names, key, kind, deadline, found);
                if (line.status() == Status.NOT_CHECKED) {
                    nodeIncomplete = true;
                }
                if (!required && (line.status() == Status.NOT_FOUND
                    || line.status() == Status.NOT_CHECKED)) {
                    continue;
                }
                lines.put(key, line);
                String subject = node.id() + ": credential store entry "
                    + (line.status() == Status.NOT_FOUND || line.status() == Status.NOT_CHECKED
                        ? names.either(kind) : line.shown())
                    + " in the " + store.kind().displayName() + " for the " + what(key);
                switch (line.status()) {
                    case NOT_FOUND -> errors.add(subject + ": not found");
                    case ACCESS_DENIED -> errors.add(subject + ": access denied");
                    case INVALID_VALUE -> errors.add(subject + ": invalid value ("
                        + found.getOrDefault("rule:" + key, "the value breaks a credential rule")
                        + ")");
                    case OK, NOT_CHECKED -> {
                        // nothing to report here
                    }
                }
            }
            rules(node, names, lines);
            return lines;
        }

        /** The rules that combine kinds or depend on the node's settings (research R-11). */
        private void rules(EffectiveNodeConfig node, CredentialNames names,
            Map<String, Line> lines) {
            String id = node.id().value();
            Line trust = lines.get("trustStorePassword");
            if (trust != null && trust.status() == Status.OK) {
                if (node.cliConfigured()) {
                    errors.add(id + ": the credential store entry " + trust.shown() + " is set,"
                        + " but this node has a CLI configured and needs a password-less trust"
                        + " store (StartCLI could only receive the password as a process"
                        + " argument)");
                } else if (node.tls().trustStore().isEmpty()) {
                    warnings.add(id + ": the credential store entry " + trust.shown() + " is set,"
                        + " but no tls.trustStore is configured; the entry is ignored");
                }
            }
            Line e2eUser = lines.get("e2eUsername");
            Line e2ePassword = lines.get("e2ePassword");
            if ((e2eUser == null) != (e2ePassword == null)) {
                String missing = e2eUser == null ? names.either(CredentialNames.E2E_USERNAME)
                    : names.either(CredentialNames.E2E_PASSWORD);
                if (unavailable == null) {
                    errors.add(id + ": the end-to-end entry " + missing + " is missing; the"
                        + " end-to-end credentials need both "
                        + names.either(CredentialNames.E2E_USERNAME) + " and "
                        + names.either(CredentialNames.E2E_PASSWORD));
                }
            }
            boolean plainHttp = node.development().e2eTests() != E2ePolicy.FORBIDDEN
                && node.development().soapBaseUrl().map(url -> url.getScheme() != null
                    && url.getScheme().toLowerCase(Locale.ROOT).equals("http")).orElse(false);
            if (plainHttp && (e2eUser != null || e2ePassword != null)) {
                String entries = Stream.of(e2eUser, e2ePassword)
                    .filter(Objects::nonNull).map(Line::shown)
                    .collect(Collectors.joining(", "));
                errors.add(id + ": e2e.soap.baseUrl uses http:// while the end-to-end entries "
                    + entries + " are set; the credentials would travel unencrypted");
            }
        }

        /** One kind: the node entry, else the group entry if the node entry is missing or empty. */
        private Line line(CredentialNames names, String key, String kind, long deadline,
            Map<String, String> found) {
            String both = names.nodeName(kind) + " | " + names.groupName(kind);
            for (String account : names.candidates(kind)) {
                if (unavailable != null) {
                    return new Line(both, Status.NOT_CHECKED);
                }
                Outcome outcome = read.get(account);
                if (outcome == null) {
                    Duration left = Duration.ofNanos(deadline - System.nanoTime());
                    outcome = left.isNegative() || left.isZero()
                        ? new Outcome(OutcomeKind.TIMEOUT, "")
                        : classify(key, store.read(account, left));
                    read.put(account, outcome);
                }
                switch (outcome.kind()) {
                    case NOT_FOUND, EMPTY -> {
                        continue;
                    }
                    case OK -> {
                        return new Line(account, Status.OK);
                    }
                    case INVALID -> {
                        found.put("rule:" + key, outcome.rule());
                        return new Line(account, Status.INVALID_VALUE);
                    }
                    case DENIED -> {
                        return new Line(account, Status.ACCESS_DENIED);
                    }
                    case TIMEOUT -> {
                        unavailable = "no answer within " + timeout.toSeconds() + " s"
                            + " (an access dialog may be waiting)";
                        return new Line(both, Status.NOT_CHECKED);
                    }
                    case UNAVAILABLE -> {
                        unavailable = unavailableReason(store.kind());
                        return new Line(both, Status.NOT_CHECKED);
                    }
                }
            }
            return new Line(both, Status.NOT_FOUND);
        }
    }

    /** What a read gave, without the value. */
    private enum OutcomeKind { OK, EMPTY, NOT_FOUND, INVALID, DENIED, TIMEOUT, UNAVAILABLE }

    /** @param rule why a value was refused (a fixed text, never the value); else empty */
    private record Outcome(OutcomeKind kind, String rule) {
    }

    /** Classifies one read at once, so that no value outlives it. */
    private static Outcome classify(String key, CredentialStore.Lookup lookup) {
        return switch (lookup) {
            case CredentialStore.Lookup.NotFound notFound ->
                new Outcome(OutcomeKind.NOT_FOUND, "");
            case CredentialStore.Lookup.Failed failed -> switch (failed.kind()) {
                case ACCESS_DENIED -> new Outcome(OutcomeKind.DENIED, "");
                // the adapters' INVALID_VALUE details are fixed texts ("cannot be decoded …")
                case INVALID_VALUE -> new Outcome(OutcomeKind.INVALID, failed.detail());
                case TIMEOUT -> new Outcome(OutcomeKind.TIMEOUT, "");
                default -> new Outcome(OutcomeKind.UNAVAILABLE, "");
            };
            case CredentialStore.Lookup.Found value -> {
                String text = value.value().reveal();
                if (text.isEmpty()) {
                    yield new Outcome(OutcomeKind.EMPTY, "");
                }
                yield CredentialRules.checkValue(CredentialRules.roleOf(key),
                        ParameterType.SECURE_STRING, text)
                    .map(refusal -> new Outcome(OutcomeKind.INVALID, refusal.rule()))
                    .orElseGet(() -> new Outcome(OutcomeKind.OK, ""));
            }
        };
    }

    /**
     * A fixed reason per store for "could not be asked"; the store's own text (tool stderr) is
     * never shown (FR-010).
     */
    private static String unavailableReason(StoreKind kind) {
        return switch (kind) {
            case KEYCHAIN -> "the macOS Keychain could not be asked (no desktop session, or the"
                + " keychain is locked or missing)";
            case SECRET_SERVICE -> "the Secret Service could not be asked (secret-tool is not"
                + " installed or no Secret Service is running in this session)";
            case CREDENTIAL_MANAGER -> "the Windows Credential Manager could not be asked (no"
                + " credential set for this logon session, or the Windows credential API could not"
                + " be loaded)";
            case NONE -> "use AWS Parameter Store (credentials.source type awsParameterStore)";
        };
    }

    /** The {@link CredentialNames} kind of a key. */
    private static String kind(String key) {
        return switch (key) {
            case "username" -> CredentialNames.USERNAME;
            case "password" -> CredentialNames.PASSWORD;
            case "trustStorePassword" -> CredentialNames.TRUSTSTORE_PASSWORD;
            case "e2eUsername" -> CredentialNames.E2E_USERNAME;
            case "e2ePassword" -> CredentialNames.E2E_PASSWORD;
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    /** The kind in a sentence. */
    private static String what(String key) {
        return switch (key) {
            case "username" -> "username";
            case "password" -> "password";
            case "trustStorePassword" -> "trust store password";
            case "e2eUsername" -> "end-to-end username";
            case "e2ePassword" -> "end-to-end password";
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }
}
