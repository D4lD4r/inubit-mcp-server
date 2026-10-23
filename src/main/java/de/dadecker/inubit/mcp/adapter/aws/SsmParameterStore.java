package de.dadecker.inubit.mcp.adapter.aws;

import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.Fetched;
import de.dadecker.inubit.mcp.config.ParameterStore;
import de.dadecker.inubit.mcp.config.ParameterType;
import de.dadecker.inubit.mcp.config.StoreLocation;
import de.dadecker.inubit.mcp.infra.Secret;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.AccessDeniedException;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.InvalidKeyIdException;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * {@link ParameterStore} on AWS Systems Manager Parameter Store (feature 010, research R-1, R-2,
 * R-11): {@code GetParameters} with decryption, at most 10 names per request, all requests of one
 * call within {@code timeout}. One SDK client per {@link StoreLocation}, built with the
 * {@code UrlConnectionHttpClient}, the region, and the named AWS profile or the default credential
 * chain. Failures are returned, never thrown; {@code logDetail} is the SDK message.
 */
public final class SsmParameterStore implements ParameterStore {

    /** The most names {@code GetParameters} accepts in one request. */
    private static final int BATCH_SIZE = 10;
    /** Error codes with which AWS rejects the caller's credentials themselves. */
    private static final Set<String> CREDENTIAL_ERRORS =
        Set.of("ExpiredTokenException", "UnrecognizedClientException");

    private record Client(SsmClient ssm, AwsCredentialsProvider credentials) {
    }

    /** {@code null} = the regional AWS endpoint. */
    private final URI endpointOverride;
    /** {@code null} = the AWS profile of the location or the default credential chain. */
    private final AwsCredentialsProvider fixedCredentials;
    private final Map<StoreLocation, Client> clients = new ConcurrentHashMap<>();

    public SsmParameterStore() {
        this(null, null);
    }

    /** For tests: a local endpoint and, unless {@code null}, fixed credentials. */
    SsmParameterStore(URI endpointOverride, AwsCredentialsProvider credentials) {
        this.endpointOverride = endpointOverride;
        this.fixedCredentials = credentials;
    }

    @Override
    public Fetched get(StoreLocation location, List<String> names, Duration timeout) {
        if (names.isEmpty()) {
            return new Fetched.PerName(Map.of());
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Client client;
        try {
            // The SDK reads the AWS config while building; a failed build is not cached.
            client = clients.computeIfAbsent(location, this::newClient);
        } catch (RuntimeException e) {
            return callFailed(FailureKind.NO_CREDENTIALS, detail(e));
        }
        Optional<Fetched> credentialFailure = checkCredentials(location, client);
        if (credentialFailure.isPresent()) {
            return credentialFailure.get();
        }
        Map<String, Fetched.Result> found = new HashMap<>();
        for (int from = 0; from < names.size(); from += BATCH_SIZE) {
            List<String> batch = names.subList(from, Math.min(from + BATCH_SIZE, names.size()));
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.isNegative() || remaining.isZero()) {
                return callFailed(FailureKind.UNREACHABLE, "timed out after " + timeout);
            }
            try {
                GetParametersResponse response = client.ssm().getParameters(request -> request
                    .names(batch).withDecryption(true)
                    .overrideConfiguration(o -> o.apiCallTimeout(remaining)));
                for (Parameter parameter : response.parameters()) {
                    found.put(parameter.name(), result(parameter));
                }
                for (String invalid : response.invalidParameters()) {
                    found.put(invalid, new Fetched.Failure(FailureKind.NOT_FOUND,
                        "listed in InvalidParameters"));
                }
            } catch (AwsServiceException e) {
                FailureKind kind = kind(e);
                if (kind != FailureKind.ACCESS_DENIED) {
                    return callFailed(kind, detail(e));
                }
                // The request is refused as a whole: no name of the batch could be read.
                batch.forEach(name -> found.put(name, new Fetched.Failure(kind, detail(e))));
            } catch (SdkException e) {
                // Client side: connection failure, timeout, abort.
                return callFailed(FailureKind.UNREACHABLE, detail(e));
            }
        }
        Map<String, Fetched.Result> results = new HashMap<>();
        for (String name : names) {
            results.put(name, found.getOrDefault(name, new Fetched.Failure(FailureKind.NOT_FOUND,
                "not in the GetParameters response")));
        }
        return new Fetched.PerName(results);
    }

    private Client newClient(StoreLocation location) {
        AwsCredentialsProvider credentials = fixedCredentials != null ? fixedCredentials
            : location.awsProfile().<AwsCredentialsProvider>map(ProfileCredentialsProvider::create)
                .orElseGet(() -> DefaultCredentialsProvider.builder().build());
        SsmClientBuilder builder = SsmClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .region(Region.of(location.region()))
            .credentialsProvider(credentials);
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride);
        }
        return new Client(builder.build(), credentials);
    }

    /**
     * Resolves the credentials before any request, so that a failing provider (expired SSO token,
     * incomplete profile) is told apart from a store that cannot be reached.
     */
    private static Optional<Fetched> checkCredentials(StoreLocation location, Client client) {
        try {
            if (location.awsProfile().isPresent()
                && ProfileFile.defaultProfileFile().profile(location.awsProfile().get())
                    .isEmpty()) {
                return Optional.of(callFailed(FailureKind.UNKNOWN_PROFILE, "AWS profile '"
                    + location.awsProfile().get() + "' not found in the AWS config or credentials"
                    + " file"));
            }
            client.credentials().resolveCredentials();
            return Optional.empty();
        } catch (RuntimeException e) {
            // Deliberately NO_CREDENTIALS for every failure here, including a network failure
            // while the provider contacts STS or SSO: the next step is to fix the AWS login.
            return Optional.of(callFailed(FailureKind.NO_CREDENTIALS, detail(e)));
        }
    }

    private static Fetched.Result result(Parameter parameter) {
        ParameterType type = switch (parameter.type()) {
            case SECURE_STRING -> ParameterType.SECURE_STRING;
            case STRING -> ParameterType.STRING;
            case STRING_LIST -> ParameterType.STRING_LIST;
            case null, default -> null;
        };
        if (type == null) {
            return new Fetched.Failure(FailureKind.INVALID_VALUE,
                "unsupported parameter type " + parameter.typeAsString());
        }
        return new Fetched.Value(Secret.of(parameter.value()), type);
    }

    /** Throttling, server errors and any other answer of the store count as unreachable. */
    private static FailureKind kind(AwsServiceException e) {
        if (e instanceof AccessDeniedException || e instanceof InvalidKeyIdException) {
            return FailureKind.ACCESS_DENIED;
        }
        if (e.awsErrorDetails() != null
            && CREDENTIAL_ERRORS.contains(e.awsErrorDetails().errorCode())) {
            return FailureKind.NO_CREDENTIALS;
        }
        return FailureKind.UNREACHABLE;
    }

    private static Fetched callFailed(FailureKind kind, String logDetail) {
        return new Fetched.CallFailed(new Fetched.Failure(kind, logDetail));
    }

    private static String detail(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
