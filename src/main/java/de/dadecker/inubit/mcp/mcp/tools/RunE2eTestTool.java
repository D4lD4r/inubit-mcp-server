package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.application.E2eTestService;
import de.dadecker.inubit.mcp.application.E2eTestService.E2eRequest;
import de.dadecker.inubit.mcp.application.E2eTestService.Response;
import de.dadecker.inubit.mcp.mcp.CallContext;
import de.dadecker.inubit.mcp.mcp.ToolHandler;
import de.dadecker.inubit.mcp.mcp.ToolHints;
import java.util.Map;
import java.util.Objects;

/**
 * {@code run_e2e_test} (feature 004, contracts/mcp-tools-delta.md, US5): sends a SOAP envelope
 * from the workspace to an endpoint of one development node through {@link E2eTestService} and
 * reports the answer and what INUBIT did. The arguments are validated by the SDK against
 * {@code run_e2e_test.input.json}; the output is {@code {"challenge": …}} (where
 * {@code e2eTests} is {@code CONFIRM}) or {@code {"result": …}}. Refusals before anything is
 * sent are tool errors.
 *
 * <p>Registered only if a development node allows end-to-end tests (FR-001, FR-003).
 */
public final class RunE2eTestTool implements ToolHandler {

    static final String DESCRIPTION = "Send a SOAP envelope from the workspace to an endpoint of"
        + " ONE {node} and report the response and the process instances, errors and log entries"
        + " it caused. Allowed only where e2eTests permits.";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private final E2eTestService service;

    public RunE2eTestTool(E2eTestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String name() {
        return "run_e2e_test";
    }

    @Override
    public String descriptionText() {
        return DESCRIPTION;
    }

    /** Destructive (it triggers processes), non-idempotent, open world (FR-026). */
    @Override
    public ToolHints annotations() {
        return ToolHints.destructive("Send a SOAP test message to a development INUBIT node");
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        return handle(arguments, CallContext.NONE);
    }

    @Override
    public Object handle(Map<String, Object> arguments, CallContext context) {
        ToolArguments args = new ToolArguments(arguments);
        Response response = service.run(new E2eRequest(args.string("node"),
            args.string("envelope"), args.string("path"), args.optionalString("soapAction"),
            args.optionalString("workflow"), args.integer("timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS), args.flag("includeExcerpt"),
            args.optionalString("confirmationCode"), context.client()));
        return switch (response) {
            case Response.Challenge challenge -> Map.of("challenge", challenge.preview());
            case Response.Completed completed -> Map.of("result", completed.run());
        };
    }
}
