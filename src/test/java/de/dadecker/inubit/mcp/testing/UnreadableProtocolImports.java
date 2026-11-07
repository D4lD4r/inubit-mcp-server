package de.dadecker.inubit.mcp.testing;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import de.dadecker.inubit.mcp.domain.model.WritePermit;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import java.util.Objects;

/**
 * An import port that reports an import protocol it cannot read as {@code UNEXPECTED_RESPONSE}
 * after StartCLI ran, as a port without the {@code IMPORT_FAILED} mapping of the 8.1 CLI import
 * would; everything else is passed through. Placed below the real stale wrapper, it lets a test
 * raise a write's own stale mark (feature 017, research D-24).
 */
public final class UnreadableProtocolImports implements ImportPort {

    private static final String UNREADABLE = "The import protocol has no Total line";

    private final ImportPort delegate;

    public UnreadableProtocolImports(ImportPort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void checkAvailable() {
        delegate.checkAvailable();
    }

    @Override
    public ImportProtocol importArchive(byte[] archive, Mode mode, String owner,
        WritePermit permit) {
        try {
            return delegate.importArchive(archive, mode, owner, permit);
        } catch (ToolErrorException e) {
            if (e.error().code() == ErrorCode.IMPORT_FAILED
                && e.error().message().contains(UNREADABLE)) {
                ToolError unexpected = ToolError.of(ErrorCode.UNEXPECTED_RESPONSE,
                    e.error().message(), "StartCLI answered in an unknown format",
                    "Check the import on the node");
                throw new ToolErrorException(e.error().node().map(unexpected::withNode)
                    .orElse(unexpected));
            }
            throw e;
        }
    }

    @Override
    public void importRepository(byte[] archive, String owner, WritePermit permit) {
        delegate.importRepository(archive, owner, permit);
    }
}
