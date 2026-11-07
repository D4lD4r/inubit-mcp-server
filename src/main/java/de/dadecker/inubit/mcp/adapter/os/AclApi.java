package de.dadecker.inubit.mcp.adapter.os;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The Windows security calls of {@link OsPrivateFileSystem} (feature 013, research R-8). A seam, so
 * that the order and the failure handling around them are tested without Windows; production uses
 * {@link JnaAclApi}. Every method may throw {@link LinkageError} if the native library cannot be
 * loaded.
 */
interface AclApi {

    /** A principal of an access-list entry: its SID ({@code S-1-…}) and account name. */
    record Principal(String sid, String name) {
    }

    /**
     * Sets the owner of {@code path} to {@code ownerSid} (only if it differs, which needs
     * {@code WRITE_OWNER}) and its protected access list: full
     * control for {@code ownerSid} and {@code SYSTEM}, inherited by files and directories below
     * if {@code directory}.
     */
    void protect(Path path, String ownerSid, boolean directory) throws IOException;

    /** The SID of the owner of {@code path}. */
    String ownerSid(Path path) throws IOException;

    /**
     * The principals of the access-allowed entries of {@code path}'s access list that apply to the
     * object itself (inherit-only entries are left out), in their order; empty if the object has
     * no access list at all (which grants everyone full access).
     */
    Optional<List<Principal>> allowed(Path path) throws IOException;
}
