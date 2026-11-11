package de.dadecker.inubit.mcp.domain.model;

/** A part of a Queue Manager entry that can be read (feature 020). */
public enum EntryPart {
    /** The data stream (message content). */
    DATA,
    /** The workflow variables. */
    VARIABLES
}
