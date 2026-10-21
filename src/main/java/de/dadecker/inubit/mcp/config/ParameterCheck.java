package de.dadecker.inubit.mcp.config;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Parameter Store part of {@code --check-config} (feature 010, research R-12, FR-009): reads
 * every configured name of every AWS node, each distinct name once per {@link StoreLocation}, in
 * calls of at most {@link #BATCH_SIZE} names bounded by {@link #TIMEOUT}, and checks each value
 * with {@link CredentialRules} as at run time. Values are only checked, never kept or described;
 * the raw store message is never part of a finding.
 *
 * <p>Errors: a parameter missing, denied or invalid; an unknown AWS profile. Warnings: a
 * {@code String} parameter; no AWS credentials or the store unreachable ("resolvability not
 * checked").
 */
public final class ParameterCheck {

    /** The bound of each store call. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The most names per store call ({@code GetParameters} takes up to 10). */
    static final int BATCH_SIZE = 10;

    /** The {@code credentials.parameters} keys in display order. */
    static final List<String> KEYS =
        List.of("username", "password", "trustStorePassword", "e2eUsername", "e2ePassword");

    /** The outcome for one name of one node. */
    public enum Status {
        OK("ok"),
        /** A {@code String} parameter: accepted with a warning. */
        NOT_ENCRYPTED("not encrypted"),
        NOT_FOUND("not found"),
        ACCESS_DENIED("access denied"),
        INVALID_VALUE("invalid value"),
        /** No AWS credentials, store unreachable or unknown AWS profile. */
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
     * The outcome of {@link #run}.
     *
     * @param statuses per AWS node the status of each configured key
     */
    public record Result(Map<NodeId, Map<String, Status>> statuses, List<String> errors,
        List<String> warnings) {

        /** Nothing read: no AWS node. */
        public static final Result NONE = new Result(Map.of(), List.of(), List.of());

        public Result {
            statuses = Map.copyOf(statuses);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        /** The status of {@code key} of {@code node}, if it was read. */
        public Optional<Status> status(NodeId node, String key) {
            return Optional.ofNullable(statuses.getOrDefault(node, Map.of()).get(key));
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

    private ParameterCheck() {
    }

    /** Reads and checks the names of the AWS plans among {@code plans}; others are skipped. */
    public static Result run(Collection<SecretPlan> plans, ParameterStore store) {
        Map<StoreLocation, List<SecretPlan>> byLocation = new LinkedHashMap<>();
        for (SecretPlan plan : plans) {
            if (plan.source() instanceof SecretSourceConfig.AwsParameterStore aws) {
                byLocation.computeIfAbsent(new StoreLocation(aws.region(), aws.awsProfile()),
                    location -> new ArrayList<>()).add(plan);
            }
        }
        Map<NodeId, Map<String, Status>> statuses = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        byLocation.forEach((location, located) -> {
            Set<String> names = new LinkedHashSet<>();
            located.forEach(plan -> KEYS.forEach(key -> name(plan.names().orElseThrow(), key)
                .ifPresent(names::add)));
            Map<String, Fetched.Result> results = fetch(store, location, List.copyOf(names));
            Set<FailureKind> unchecked = EnumSet.noneOf(FailureKind.class);
            for (SecretPlan plan : located) {
                Map<String, Status> nodeStatuses = new HashMap<>();
                for (String key : KEYS) {
                    Optional<String> name = name(plan.names().orElseThrow(), key);
                    if (name.isEmpty()) {
                        continue;
                    }
                    String subject = plan.node() + ": Parameter Store " + name.get() + " ("
                        + where(location) + ") for credentials.parameters." + key;
                    Status status = switch (results.get(name.get())) {
                        case Fetched.Value value -> checkValue(key, value, subject, errors,
                            warnings);
                        case Fetched.Failure failure -> switch (failure.kind()) {
                            case NOT_FOUND -> failed(Status.NOT_FOUND, subject, errors);
                            case ACCESS_DENIED -> failed(Status.ACCESS_DENIED, subject, errors);
                            case INVALID_VALUE -> failed(Status.INVALID_VALUE, subject, errors);
                            case UNREACHABLE, NO_CREDENTIALS, UNKNOWN_PROFILE -> {
                                unchecked.add(failure.kind());
                                yield Status.NOT_CHECKED;
                            }
                        };
                    };
                    nodeStatuses.put(key, status);
                }
                statuses.put(plan.node(), Map.copyOf(nodeStatuses));
            }
            String access = "AWS Parameter Store (" + where(location) + ") for "
                + located.stream().map(plan -> plan.node().value())
                    .collect(Collectors.joining(", ")) + ": ";
            if (unchecked.contains(FailureKind.UNKNOWN_PROFILE)) {
                errors.add(access + "the AWS profile " + location.awsProfile().orElse("default")
                    + " is not defined in the local AWS configuration files; configure it and"
                    + " log in to it");
            }
            if (unchecked.contains(FailureKind.NO_CREDENTIALS)) {
                warnings.add(access + "resolvability not checked: no AWS credentials could be"
                    + " resolved (not logged in, or the login expired)");
            }
            if (unchecked.contains(FailureKind.UNREACHABLE)) {
                warnings.add(access + "resolvability not checked: the store could not be reached"
                    + " or did not answer within " + TIMEOUT.toSeconds() + " s");
            }
        });
        return new Result(statuses, errors, warnings);
    }

    /** The name configured for {@code key}, one of {@link #KEYS}. */
    static Optional<String> name(ParameterNames names, String key) {
        return switch (key) {
            case "username" -> names.username();
            case "password" -> names.password();
            case "trustStorePassword" -> names.trustStorePassword();
            case "e2eUsername" -> names.e2eUsername();
            case "e2ePassword" -> names.e2ePassword();
            default -> throw new IllegalArgumentException("Unknown key: " + key);
        };
    }

    /** {@code <region>[, profile <p>]}. */
    static String where(StoreLocation location) {
        return location.region() + location.awsProfile().map(p -> ", profile " + p).orElse("");
    }

    /**
     * A result for every name; after a failed call the remaining names are not asked for (the
     * next call would fail the same way) and get that failure.
     */
    private static Map<String, Fetched.Result> fetch(ParameterStore store, StoreLocation location,
        List<String> names) {
        Map<String, Fetched.Result> results = new HashMap<>();
        for (int from = 0; from < names.size(); from += BATCH_SIZE) {
            List<String> batch = names.subList(from, Math.min(from + BATCH_SIZE, names.size()));
            switch (store.get(location, batch, TIMEOUT)) {
                case Fetched.PerName perName -> batch.forEach(name -> results.put(name,
                    perName.results().getOrDefault(name,
                        new Fetched.Failure(FailureKind.NOT_FOUND, "no result for this name"))));
                case Fetched.CallFailed failed -> {
                    names.subList(from, names.size())
                        .forEach(name -> results.put(name, failed.failure()));
                    return results;
                }
            }
        }
        return results;
    }

    private static Status checkValue(String key, Fetched.Value value, String subject,
        List<String> errors, List<String> warnings) {
        CredentialRules.ValueRole role = switch (key) {
            case "username", "e2eUsername" -> CredentialRules.ValueRole.USERNAME;
            case "password", "e2ePassword" -> CredentialRules.ValueRole.PASSWORD;
            default -> CredentialRules.ValueRole.TRUST_STORE_PASSWORD;
        };
        Optional<CredentialRules.Refusal> refusal =
            CredentialRules.checkValue(role, value.type(), value.value().reveal());
        if (refusal.isPresent()) {
            Status status = refusal.get().kind() == FailureKind.NOT_FOUND ? Status.NOT_FOUND
                : Status.INVALID_VALUE;
            errors.add(subject + ": " + status.text() + " (" + refusal.get().rule() + ")");
            return status;
        }
        if (CredentialRules.checkType(value.type()) == CredentialRules.TypeVerdict.UNENCRYPTED) {
            warnings.add(subject + " is not encrypted (type String); store it as SecureString");
            return Status.NOT_ENCRYPTED;
        }
        return Status.OK;
    }

    private static Status failed(Status status, String subject, List<String> errors) {
        errors.add(subject + ": " + status.text());
        return status;
    }
}
