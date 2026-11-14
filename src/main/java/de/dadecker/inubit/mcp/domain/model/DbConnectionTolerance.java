package de.dadecker.inubit.mcp.domain.model;

/**
 * The tolerance of an INUBIT server for database connection errors (data-model.md →
 * ServerSettings); the vendor example shows {@code Drop rate: 1%} and
 * {@code Drop interval (s): 180 seconds}.
 *
 * @param faultThreshold      the number of connection errors tolerated, at least 0
 * @param dropRatePercent     the drop rate, 0 … 100
 * @param dropIntervalSeconds the drop interval in seconds, at least 0
 */
public record DbConnectionTolerance(int faultThreshold, int dropRatePercent,
    long dropIntervalSeconds) {

    public DbConnectionTolerance {
        if (faultThreshold < 0) {
            throw new IllegalArgumentException("faultThreshold must be >= 0: " + faultThreshold);
        }
        if (dropRatePercent < 0 || dropRatePercent > 100) {
            throw new IllegalArgumentException(
                "dropRatePercent must be 0 … 100: " + dropRatePercent);
        }
        if (dropIntervalSeconds < 0) {
            throw new IllegalArgumentException(
                "dropIntervalSeconds must be >= 0: " + dropIntervalSeconds);
        }
    }
}
