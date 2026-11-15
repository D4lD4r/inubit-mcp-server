package de.dadecker.inubit.mcp.adapter.cli.admin;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.adapter.cli.CliCommand;
import de.dadecker.inubit.mcp.adapter.cli.CliOutcome;
import de.dadecker.inubit.mcp.adapter.cli.CliOutput;
import de.dadecker.inubit.mcp.adapter.cli.CliOutputClassifier;
import de.dadecker.inubit.mcp.adapter.cli.CliResult;
import de.dadecker.inubit.mcp.adapter.cli.CliRunner;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.LibraryEntry;
import de.dadecker.inubit.mcp.domain.model.SecretTextMasker;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Objects;

/**
 * The library list of {@code read_model_details} on an INUBIT 8.1/9.0 node (feature 022, US3,
 * research D-21): one script-mode run of {@code library --list --type <type>}
 * ({@link CliCommand#libraryList}) per type, bounded by the node's {@code cliTimeout}, its answer
 * read by {@link LibraryListParser}.
 *
 * <p>A failed run (credentials, pin, launch, timeout, an exit code other than 0, cut output, a
 * refused login, or an {@code n-NOK} message) is thrown with the node set and the classifier's
 * excerpt, secret-named values masked; the list shows file names only. An answer the parser
 * cannot read is {@code UNEXPECTED_RESPONSE} with the node set.
 */
public final class CliLibraryRunner {

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliLibraryRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * The libraries of {@code type}.
     *
     * @param type {@code driver} or {@code plugin}
     * @throws ToolErrorException {@code INVALID_INPUT} for another type, {@code CLI_UNAVAILABLE}
     *     or {@code AUTH_FAILED} before anything is launched; with the node set, a failed run or
     *     an unknown answer (see the class comment)
     */
    public LibraryEntry.Listing list(String type) {
        CliCommand command = CliCommand.libraryList(type);
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
        CliResult result;
        try {
            result = runner.run(server, credentials.username().orElseThrow().value(),
                credentials.password().orElseThrow().value(), command, server.cliTimeout(), guard);
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
        CliOutput output = classifier.parse(result);
        if ((result.exitCode() != 0 || result.truncated()
            || CliOutputClassifier.isLoginFailure(result) || !output.nokMessages().isEmpty())
            && classifier.classify(result) instanceof CliOutcome.Failure failure) {
            ToolError error = failure.error();
            throw failed(new ToolError(error.code(), error.message(), error.likelyCause(),
                error.nextStep(), error.node(), error.excerpt().map(SecretTextMasker::mask)));
        }
        try {
            return LibraryListParser.parse(type, output.outputLines());
        } catch (ToolErrorException e) {
            throw failed(e.error());
        }
    }

    private ToolErrorException failed(ToolError error) {
        return new ToolErrorException(
            error.node().isPresent() ? error : error.withNode(server.id()));
    }

    @Override
    public String toString() {
        return "CliLibraryRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliTimeout()) + "]";
    }
}
