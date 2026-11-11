package de.dadecker.inubit.mcp.domain.model;

/** What a data stream or variable value of a Queue Manager entry is (feature 020, FR-009). */
public enum ContentKind {
    /** No bytes; no content is returned. */
    EMPTY,
    /** Text, returned decoded with its charset named. */
    TEXT,
    /** Binary; only size, detected signature and a hint are returned, never the content. */
    BINARY
}
