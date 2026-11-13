package de.dadecker.inubit.mcp.adapter.cli.node;

import de.dadecker.inubit.mcp.adapter.cli.helper.HelperFailures;
import de.dadecker.inubit.mcp.adapter.cli.helper.HelperRequest;
import de.dadecker.inubit.mcp.config.SecretLabels;
import de.dadecker.inubit.mcp.domain.model.InubitVersion;
import de.dadecker.inubit.mcp.domain.model.node.IoLogListing;
import de.dadecker.inubit.mcp.domain.model.node.IoMessageRef;
import de.dadecker.inubit.mcp.domain.port.IoLogPort;
import de.dadecker.inubit.mcp.infra.SecretScrubber;
import java.util.Objects;
import java.util.Optional;

/**
 * The I/O log of a process run and one of its messages, read with the INUBIT client helper's
 * {@code ioLog} operation (feature 024, FR-020 … FR-023, research D-8, D-9, D-20): one
 * {@link NodeHelperRunner} run within the call deadline, whose {@code TIMEOUT} names
 * {@code cliTimeout} for a listing and {@code cliExportTimeout} for a read; the frames and files
 * are judged by {@link NodeFramesClassifier#ioListing} and {@link NodeFramesClassifier#ioRead}.
 * The request names the message by its reference only; the helper writes the listing and the
 * message into the private directory. That directory is deleted before a listing or a
 * {@link IoLogRead.NoMatch} is returned, on every failure, and when a returned
 * {@link IoLogRead.Message} is closed.
 */
public final class CliIoLogAdapter implements IoLogPort {

    private static final String LIST_BUDGET = "cliTimeout";
    private static final String READ_BUDGET = "cliExportTimeout";
    private static final String LIST_FILE = "io-list.txt";
    private static final String MESSAGE_FILE = "io-message.bin";
    private static final HelperFailures.Subject SUBJECT = new HelperFailures.Subject(
        "INUBIT client helper", "read_io_log",
        "Use the Workbench's input/output logging view; report the INUBIT version",
        "refused the input/output logging for the configured user",
        "The INUBIT user of this node lacks the right to read the input/output logging of"
            + " modules",
        "Give the user the right the Workbench's input/output logging view needs");

    private final NodeHelperRunner runner;
    private final NodeFramesClassifier classifier;

    /**
     * @param runner runs the helper on the node
     * @param scrubber masks the helper's texts
     * @param labels the node's secret names
     */
    public CliIoLogAdapter(NodeHelperRunner runner, SecretScrubber scrubber,
        SecretLabels labels) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.classifier = new NodeFramesClassifier(scrubber, labels, SUBJECT);
    }

    @Override
    public IoLogListing list(String processId, String workflow, InubitVersion version,
        long deadlineNanos) {
        try (NodeHelperRunner.Run run = runner.run(version,
            out -> new HelperRequest.IoLog(processId, workflow, Optional.empty(),
                out.resolve(LIST_FILE), Optional.empty()),
            classifier, LIST_BUDGET, deadlineNanos)) {
            return classifier.ioListing(run.request(), run.inubitNode(), run.frames());
        }
    }

    @Override
    public IoLogRead read(String processId, String workflow, IoMessageRef ref,
        InubitVersion version, long deadlineNanos) {
        Objects.requireNonNull(ref, "ref");
        NodeHelperRunner.Run run = runner.run(version,
            out -> new HelperRequest.IoLog(processId, workflow, Optional.of(ref),
                out.resolve(LIST_FILE), Optional.of(out.resolve(MESSAGE_FILE))),
            classifier, READ_BUDGET, deadlineNanos);
        boolean handedOver = false;
        try {
            IoLogRead read = classifier.ioRead(run.request(), run.inubitNode(), run.frames(),
                run::close);
            handedOver = read instanceof IoLogRead.Message;
            return read;
        } finally {
            if (!handedOver) {
                run.close();
            }
        }
    }
}
