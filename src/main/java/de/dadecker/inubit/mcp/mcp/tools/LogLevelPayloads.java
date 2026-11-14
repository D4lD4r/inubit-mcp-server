package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.domain.model.NodeId;
import de.dadecker.inubit.mcp.domain.model.node.LeaseSummary;
import de.dadecker.inubit.mcp.domain.model.node.LogLevelResult;
import de.dadecker.inubit.mcp.domain.model.node.TemporaryLevel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The JSON shapes {@code set_temporary_log_level} and {@code reset_temporary_log_level} share
 * (feature 024, contracts/mcp-tools-delta.md "Output"): the challenge with its preview, a logger
 * with its level, and the open lease as {@code $defs/LeaseSummary}: the {@link LeaseSummary}
 * itself, as {@code get_health} reports it ({@code ResultJson}, research D-23).
 */
final class LogLevelPayloads {

    private LogLevelPayloads() {
    }

    /** {@code challenge}: nothing was changed; the call is repeated with the code. */
    record Challenge(String confirmationCode, Instant expiresAt, Preview preview,
        String message) {

        static Challenge of(LogLevelResult.Challenge challenge) {
            return new Challenge(challenge.confirmationCode(), challenge.expiresAt(),
                Preview.of(challenge.preview()), challenge.message());
        }
    }

    /**
     * The preview of a set (with {@code loggers} and {@code durationMinutes}) or of a reset or
     * forget (without); {@code openLease} only when a lease is open.
     */
    record Preview(NodeId node, String inubitNode, Optional<List<Logger>> loggers,
        Optional<Integer> durationMinutes, Optional<LeaseSummary> openLease) {

        static Preview of(LogLevelResult.ChallengePreview preview) {
            Optional<LeaseSummary> openLease = preview.openLease();
            return switch (preview) {
                case LogLevelResult.Preview set -> new Preview(set.node(), set.inubitNode(),
                    Optional.of(Logger.of(set.loggers())), Optional.of(set.durationMinutes()),
                    openLease);
                case LogLevelResult.ResetPreview reset -> new Preview(reset.node(),
                    reset.inubitNode(), Optional.empty(), Optional.empty(), openLease);
            };
        }
    }

    /** One logger and its temporary level. */
    record Logger(String name, TemporaryLevel level) {

        static List<Logger> of(List<LeaseSummary.Logger> loggers) {
            return loggers.stream()
                .map(logger -> new Logger(logger.name().value(), logger.level()))
                .toList();
        }
    }
}
