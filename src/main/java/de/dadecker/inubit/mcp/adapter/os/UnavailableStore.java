package de.dadecker.inubit.mcp.adapter.os;

import de.dadecker.inubit.mcp.config.CredentialStore;
import de.dadecker.inubit.mcp.config.FailureKind;
import de.dadecker.inubit.mcp.config.StoreKind;
import java.time.Duration;

/**
 * The credential store of a system without a supported store (feature 011, research R-1): every
 * read is {@code STORE_NOT_AVAILABLE}; AWS Parameter Store is the alternative there.
 */
public final class UnavailableStore implements CredentialStore {

    private final String system;

    /** @param system the {@code os.name} of the running system, for the failure detail */
    public UnavailableStore(String system) {
        this.system = system;
    }

    @Override
    public StoreKind kind() {
        return StoreKind.NONE;
    }

    @Override
    public Lookup read(String account, Duration timeout) {
        return new Lookup.Failed(FailureKind.STORE_NOT_AVAILABLE,
            "no supported credential store on " + system);
    }
}
