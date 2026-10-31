package de.dadecker.inubit.mcp.adapter.os;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.List;

/**
 * {@link CredApi} through JNA (feature 011, research R-4): {@code CredReadW} and {@code CredFree} of
 * {@code Advapi32}, mapped here because {@code jna-platform} has no {@code CREDENTIAL} mapping. The
 * native library is loaded on the first read, never on construction, so other systems never load it.
 */
final class JnaCredApi implements CredApi {

    /** {@code CRED_TYPE_GENERIC}. */
    private static final int CRED_TYPE_GENERIC = 1;

    /**
     * The {@code W} functions of {@code Advapi32}. {@code CredReadW} declares no
     * {@code LastErrorException}: JNA would throw it whenever the last error is non-zero, also after
     * a successful call, and the buffer would never be freed. The result decides;
     * {@link Native#getLastError()} is read only after a failed call.
     */
    interface Advapi32 extends StdCallLibrary {

        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);

        void CredFree(Pointer buffer);
    }

    /** {@code CREDENTIALW}; only {@code CredentialBlobSize} and {@code CredentialBlob} are read. */
    public static final class Credential extends Structure {
        public int Flags;
        public int Type;
        public Pointer TargetName;
        public Pointer Comment;
        public int LastWrittenLow;
        public int LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public Pointer TargetAlias;
        public Pointer UserName;

        public Credential(Pointer pointer) {
            super(pointer);
            read();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("Flags", "Type", "TargetName", "Comment", "LastWrittenLow",
                "LastWrittenHigh", "CredentialBlobSize", "CredentialBlob", "Persist",
                "AttributeCount", "Attributes", "TargetAlias", "UserName");
        }
    }

    /** Loaded on first use (class initialization of the holder). */
    private static final class Holder {
        static final Advapi32 ADVAPI32 = Native.load("Advapi32", Advapi32.class);
    }

    @Override
    public Result readGeneric(String targetName) {
        PointerByReference reference = new PointerByReference();
        if (!Holder.ADVAPI32.CredReadW(new WString(targetName), CRED_TYPE_GENERIC, 0, reference)) {
            return new Result.Error(Native.getLastError());
        }
        Pointer pointer = reference.getValue();
        try {
            Credential credential = new Credential(pointer);
            int size = credential.CredentialBlobSize;
            byte[] bytes = size == 0 || credential.CredentialBlob == null
                ? new byte[0] : credential.CredentialBlob.getByteArray(0, size);
            return new Result.Blob(bytes);
        } finally {
            Holder.ADVAPI32.CredFree(pointer);
        }
    }
}
