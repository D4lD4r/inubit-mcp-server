package de.dadecker.inubit.mcp.mcp;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test tool with the schemas {@code schemas/echo_test.*.json} from the test classpath. The
 * {@code mode} argument selects success, a {@link ToolError}, a crash or an output that violates
 * the output schema.
 */
final class EchoTestTool implements ToolHandler {

    static final String NAME = "echo_test";
    static final NodeId SERVER = NodeId.parse("dev/node1");

    /** A payload record as later tools return them: a node id and an optional field. */
    record Echo(String echo, NodeId node, Optional<String> note) {
    }

    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String descriptionText() {
        return "Echoes the message (test tool).";
    }

    @Override
    public ToolHints annotations() {
        return ToolHints.readOnly("Echo (test)", false);
    }

    @Override
    public Object handle(Map<String, Object> arguments) {
        calls.incrementAndGet();
        String message = String.valueOf(arguments.getOrDefault("message", ""));
        return switch (String.valueOf(arguments.getOrDefault("mode", "ok"))) {
            case "toolError" -> throw new ToolErrorException(ToolError.of(ErrorCode.NOT_FOUND,
                "Nothing found for " + message, "It does not exist", "Check the name")
                .withNode(SERVER));
            case "crash" -> throw new IllegalStateException("boom: " + message);
            case "badOutput" -> Map.of("unexpected", 1);
            case "slow" -> {
                // the results of a burst are sent at about the same time
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield new Echo(message, SERVER, Optional.empty());
            }
            default -> new Echo(message, SERVER, Optional.empty());
        };
    }
}
