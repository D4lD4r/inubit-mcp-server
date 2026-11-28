package de.dadecker.inubit.mcp.domain.model;

/**
 * A {@link ToolErrorException} that arose after contact with the INUBIT server began (feature
 * 025, spec FR-030 (b)): in the server's own TLS handshake, after the StartCLI process of a
 * session run was started, or in classifying or parsing a returned session. Taking a slot of the
 * concurrency limiter is not contact. It carries the same {@link ToolError}, so the error's JSON
 * is unchanged; every other {@link ToolErrorException} of the StartCLI path is pre-contact.
 */
public final class ContactedException extends ToolErrorException {

    private static final long serialVersionUID = 1L;

    public ContactedException(ToolError error) {
        super(error);
    }
}
