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
 * only checked, never kept or described; the raw store text is never part of an error.
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
        for (EffectiveNodeConfig node : checked) {
            lines.put(node.id(), run.node(node));
        }
        if (run.unavailable != null) {
            run.warnings.add(store.kind().displayName() + " for " + checked.stream()
                .map(node -> node.id().value()).collect(Collectors.joining(", "))
                + ": resolvability not checked: " + run.unavailable);
        }
        return new Result(lines, run.errors, run.warnings, store.kind());
    }

    /** The state of one run: the entries read so far and whether the store can still be asked. */
    private static final class Run {

        private final CredentialStore store;
        private final Duration timeout;
        /** Each distinct account's outcome, read once. */
        private final Map<String, CredentialStore.Lookup> read = new HashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        /** Why the store cannot be asked, once known; then nothing more is read. */
        private String unavailable;

        Run(CredentialStore store, Duration timeout) {
            this.store = store;
            this.timeout = timeout;
        }

        Map<String, Line> node(EffectiveNodeConfig node) {
            CredentialNames names = node.secretPlan().entries().orElseThrow();
            long deadline = System.nanoTime() + timeout.toNanos();
            Map<String, Line> lines = new LinkedHashMap<>();
            Map<String, String> found = new HashMap<>();
            for (String key : KEYS) {
                boolean required = key.equals("username") || key.equals("password");
                String kind = kind(key);
                Line line = line(names, key, kind, deadline, found);
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
                CredentialStore.Lookup lookup = read.get(account);
                if (lookup == null) {
                    Duration left = Duration.ofNanos(deadline - System.nanoTime());
                    lookup = left.isNegative() || left.isZero()
                        ? new CredentialStore.Lookup.Failed(FailureKind.TIMEOUT, "no time left")
                        : store.read(account, left);
                    read.put(account, lookup);
                }
                switch (lookup) {
                    case CredentialStore.Lookup.NotFound notFound -> {
                        continue;
                    }
                    case CredentialStore.Lookup.Failed failed -> {
                        switch (failed.kind()) {
                            case ACCESS_DENIED -> {
                                return new Line(account, Status.ACCESS_DENIED);
                            }
                            case INVALID_VALUE -> {
                                found.put("rule:" + key, failed.detail());
                                return new Line(account, Status.INVALID_VALUE);
                            }
                            case TIMEOUT -> {
                                unavailable = "no answer within " + timeout.toSeconds() + " s"
                                    + " (an access dialog may be waiting)";
                                return new Line(both, Status.NOT_CHECKED);
                            }
                            default -> {
                                unavailable = "no usable credential store (" + failed.detail()
                                    + ")";
                                return new Line(both, Status.NOT_CHECKED);
                            }
                        }
                    }
                    case CredentialStore.Lookup.Found value -> {
                        String text = value.value().reveal();
                        if (text.isEmpty()) {
                            continue;
                        }
                        Optional<CredentialRules.Refusal> refusal = CredentialRules.checkValue(
                            CredentialRules.roleOf(key), ParameterType.SECURE_STRING, text);
                        if (refusal.isPresent()) {
                            found.put("rule:" + key, refusal.get().rule());
                            return new Line(account, Status.INVALID_VALUE);
                        }
                        return new Line(account, Status.OK);
                    }
                }
            }
            return new Line(both, Status.NOT_FOUND);
        }
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
