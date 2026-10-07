package de.dadecker.inubit.mcp.adapter.cli;

import de.dadecker.inubit.mcp.adapter.CredentialGuard;
import de.dadecker.inubit.mcp.config.EffectiveNodeConfig;
import de.dadecker.inubit.mcp.config.NodeCredentials;
import de.dadecker.inubit.mcp.domain.model.Durations;
import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Objects;

/**
 * The tags of one server through StartCLI (feature 004, research D-16, SC-005; Constitution I):
 *
 * <ul>
 *   <li>{@link #tag}: {@code tag --tagMove '<tag>' --tagWorkflowGroup '<group>'
 *       --tagWorkflowType 'technical' --tagUser '<owner>'} — always one diagram group, because
 *       StartCLI tags every diagram of the owner without one (spike §6); a blank group is
 *       {@code INVALID_INPUT} before anything is launched, and so is any value that does not
 *       match {@link CliCommand#VALUE} (wildcards, quotes, a leading {@code -});
 *   <li>there is no tag removal: StartCLI removes a tag only for the whole owner
 *       ({@code --tagDelete}), which could take a tag away that marks another diagram group's
 *       state (research D-26); {@link CliCommand} refuses that option;
 *   <li>StartCLI prints no result line for {@code tag} (fixture {@code tag_ok}): exit code 0 without an {@code n-NOK} line is success, anything
 *       else {@code IMPORT_FAILED} (a write StartCLI did not complete; the caller verifies by a
 *       history export); the node's {@code cliExportTimeout} applies and is named on
 *       {@code TIMEOUT}. Every run goes through the node's {@link CredentialGuard}.
 * </ul>
 */
public final class CliTagRunner {

    private final EffectiveNodeConfig server;
    private final NodeCredentials credentials;
    private final CredentialGuard guard;
    private final CliRunner runner;
    private final CliOutputClassifier classifier;

    public CliTagRunner(EffectiveNodeConfig server, NodeCredentials credentials,
        CredentialGuard guard, CliRunner runner, CliOutputClassifier classifier) {
        this.server = Objects.requireNonNull(server, "server");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * The checks before a tag command: CLI and credentials.
     *
     * @throws ToolErrorException {@code CLI_UNAVAILABLE} or {@code AUTH_FAILED}
     */
    public void checkAvailable() {
        runner.checkAvailable(server);
        if (!credentials.complete()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.AUTH_FAILED,
                "No INUBIT credentials are configured for " + server.id()
                    + ", so StartCLI cannot log in",
                "The username or password variable for " + server.id() + " is not set",
                guard.fixCredentials()).withNode(server.id()));
        }
    }

    /**
     * Sets {@code tag} on the head versions of the technical workflows of {@code diagramGroup}
     * of {@code owner} and their modules.
     *
     * @throws ToolErrorException {@code INVALID_INPUT} before anything is launched;
     *     {@code IMPORT_FAILED} or {@code TIMEOUT} after StartCLI ran
     */
    public void tag(String tag, String diagramGroup, String owner) {
        if (diagramGroup == null || diagramGroup.isBlank()) {
            throw new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
                "The diagram group of a tag must not be empty; nothing was sent",
                "Without a diagram group StartCLI tags every diagram and module of the owner",
                "Name the diagram groups to tag").withNode(server.id()));
        }
        run(CliCommand.command("tag").quoted("--tagMove", tag)
            .quoted("--tagWorkflowGroup", diagramGroup)
            .quoted("--tagWorkflowType", "technical")
            .quoted("--tagUser", owner).build());
    }

    private void run(CliCommand command) {
        checkAvailable();
        CliResult result = runner.run(server, credentials.username().orElseThrow().value(),
            credentials.password().orElseThrow().value(), command, server.cliExportTimeout(),
            "cliExportTimeout", guard);
        CliOutput output = classifier.parse(result);
        if (result.exitCode() != 0 || !output.nokMessages().isEmpty() || result.truncated()) {
            String reason = output.nokMessages().isEmpty()
                ? (classifier.classify(result) instanceof CliOutcome.Failure failure
                    ? failure.error().code() + ": " + failure.error().message()
                    : "exit code " + result.exitCode())
                : String.join(" / ", output.nokMessages());
            throw new ToolErrorException(ToolError.of(ErrorCode.IMPORT_FAILED,
                "StartCLI reported a failed " + command.name() + " on " + server.id() + ": "
                    + reason,
                "INUBIT refused the tag command or StartCLI failed; part of it may have been"
                    + " applied",
                "tag_artifacts verifies by a history export of the requested groups")
                .withNode(server.id()));
        }
    }

    @Override
    public String toString() {
        return "CliTagRunner[" + server.id() + ", timeout "
            + Durations.human(server.cliExportTimeout()) + "]";
    }
}
