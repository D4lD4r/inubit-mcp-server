package de.dadecker.inubit.mcp.adapter.cli.v81;

import de.dadecker.inubit.mcp.adapter.cli.CliImportRunner;
import de.dadecker.inubit.mcp.domain.model.ImportProtocol;
import de.dadecker.inubit.mcp.domain.port.ImportPort;
import java.util.Objects;

/**
 * The 8.1 {@link ImportPort}: StartCLI imports through {@link CliImportRunner} (feature 004,
 * research D-8). Like the artifact exports, a request is checked first (CLI, credentials); only
 * an import that can run confirms unconfirmed credentials with the server's REST login, so that
 * a StartCLI run never holds the single permit of the credential guard; then StartCLI runs.
 */
public final class V81ImportAdapter implements ImportPort {

    private final CliImportRunner imports;
    private final Runnable confirmCredentials;

    /**
     * @param imports            the StartCLI imports of the server
     * @param confirmCredentials confirms unconfirmed credentials (a no-op once confirmed)
     */
    public V81ImportAdapter(CliImportRunner imports, Runnable confirmCredentials) {
        this.imports = Objects.requireNonNull(imports, "imports");
        this.confirmCredentials = Objects.requireNonNull(confirmCredentials,
            "confirmCredentials");
    }

    @Override
    public void checkAvailable() {
        imports.checkAvailable();
    }

    @Override
    public ImportProtocol importArchive(byte[] archive, Mode mode, String owner) {
        imports.checkAvailable();
        confirmCredentials.run();
        return imports.importArchive(archive, mode, owner);
    }

    @Override
    public void importRepository(byte[] archive, String owner) {
        imports.checkAvailable();
        confirmCredentials.run();
        imports.importRepository(archive, owner);
    }

    @Override
    public String toString() {
        return "V81ImportAdapter[" + imports + "]";
    }
}
