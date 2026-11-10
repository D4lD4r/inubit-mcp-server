package de.dadecker.inubit.mcp.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * The parts of a Queue Manager entry as read from INUBIT (feature 020, D-5), before
 * classification, masking and bounds; each part is read or carries why not.
 */
public record RawEntryContent(PartResult<byte[]> data, PartResult<List<RawVariable>> variables) {

    public RawEntryContent {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(variables, "variables");
    }

    /**
     * The outcome for one part. Reasons are fixed or scrubbed texts, never content of the entry.
     */
    public sealed interface PartResult<T> {

        /** The part was read. */
        record Read<T>(T value) implements PartResult<T> {

            public Read {
                Objects.requireNonNull(value, "value");
            }
        }

        /** The part was not asked for. */
        record NotRequested<T>(String reason) implements PartResult<T> {

            public NotRequested {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** INUBIT did not find the part. */
        record NotFound<T>() implements PartResult<T> {
        }

        /** Reading the part failed. */
        record Failed<T>(String reason) implements PartResult<T> {

            public Failed {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The part exceeds {@link QueueEntryBounds#MAX_DATA_BYTES} and was not processed. */
        record TooLarge<T>(long bytes) implements PartResult<T> {
        }
    }
}
