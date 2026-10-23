package de.dadecker.inubit.mcp.domain.model;

/** The way a capability reaches INUBIT (spec "Tool classification"); {@code CLI} is StartCLI. */
public enum Channel {
    REST,
    CLI,
    SOAP
}
