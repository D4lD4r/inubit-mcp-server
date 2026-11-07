package de.dadecker.inubit.mcp.adapter.os;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link AclApi} through JNA core (feature 013, research R-8): {@code SetNamedSecurityInfoW} and
 * {@code GetNamedSecurityInfoW} with an access list built by {@code InitializeAcl} and
 * {@code AddAccessAllowedAceEx}, SIDs converted with {@code ConvertStringSidToSidW} and
 * {@code ConvertSidToStringSidW}, account names from {@code LookupAccountSidW} (all
 * {@code Advapi32}). The native libraries are loaded on the first call, never on construction.
 */
final class JnaAclApi implements AclApi {

    /** The well-known SID of {@code NT AUTHORITY\SYSTEM}. */
    static final String SYSTEM_SID = "S-1-5-18";

    /** {@code SE_FILE_OBJECT}. */
    private static final int SE_FILE_OBJECT = 1;
    private static final int OWNER_SECURITY_INFORMATION = 0x00000001;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000;
    /** {@code ACL_REVISION}. */
    private static final int ACL_REVISION = 2;
    /** {@code sizeof(ACL)}; {@code AceCount} is the {@code WORD} at offset 4. */
    private static final int ACL_HEADER = 8;
    private static final int ACE_COUNT_OFFSET = 4;
    /**
     * {@code ACCESS_ALLOWED_ACE} without its SID: {@code ACE_HEADER} (type, flags, size) and the
     * access mask; the SID starts at this offset.
     */
    private static final int ACE_SID_OFFSET = 8;
    private static final int ACCESS_ALLOWED_ACE_TYPE = 0;
    /** {@code INHERIT_ONLY_ACE}: the entry applies only to objects created below, not this one. */
    private static final int INHERIT_ONLY_ACE = 0x8;
    private static final int OBJECT_INHERIT_ACE = 0x1;
    private static final int CONTAINER_INHERIT_ACE = 0x2;
    /** {@code FILE_ALL_ACCESS}: full control. */
    private static final int FILE_ALL_ACCESS = 0x001F01FF;
    /** Characters for an account or domain name; longer names are shown as the SID. */
    private static final int NAME_LENGTH = 256;

    /** No {@code LastErrorException} is declared (as in {@link JnaCredApi}): the result decides. */
    interface Advapi32 extends StdCallLibrary {

        boolean ConvertStringSidToSidW(WString text, PointerByReference sid);

        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference text);

        int GetLengthSid(Pointer sid);

        boolean InitializeAcl(Pointer acl, int length, int revision);

        boolean AddAccessAllowedAceEx(Pointer acl, int revision, int flags, int mask, Pointer sid);

        boolean GetAce(Pointer acl, int index, PointerByReference ace);

        int SetNamedSecurityInfoW(WString name, int type, int information, Pointer owner,
            Pointer group, Pointer dacl, Pointer sacl);

        int GetNamedSecurityInfoW(WString name, int type, int information,
            PointerByReference owner, PointerByReference group, PointerByReference dacl,
            PointerByReference sacl, PointerByReference descriptor);

        boolean LookupAccountSidW(WString system, Pointer sid, char[] name,
            IntByReference nameLength, char[] domain, IntByReference domainLength,
            IntByReference use);
    }

    /** {@code LocalFree} for the buffers {@code Advapi32} allocates. */
    interface Kernel32 extends StdCallLibrary {

        Pointer LocalFree(Pointer memory);
    }

    /** Loaded on first use (class initialization of the holder). */
    private static final class Holder {
        static final Advapi32 ADVAPI32 = Native.load("Advapi32", Advapi32.class);
        static final Kernel32 KERNEL32 = Native.load("Kernel32", Kernel32.class);
    }

    @Override
    public void protect(Path path, String ownerSid, boolean directory) throws IOException {
        Pointer owner = sid(path, ownerSid);
        try {
            Pointer system = sid(path, SYSTEM_SID);
            try {
                int flags = directory ? OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE : 0;
                int size = ACL_HEADER + 2 * ACE_SID_OFFSET + Holder.ADVAPI32.GetLengthSid(owner)
                    + Holder.ADVAPI32.GetLengthSid(system);
                try (Memory acl = new Memory(size)) {
                    if (!Holder.ADVAPI32.InitializeAcl(acl, size, ACL_REVISION)
                        || !Holder.ADVAPI32.AddAccessAllowedAceEx(acl, ACL_REVISION, flags,
                            FILE_ALL_ACCESS, owner)
                        || !Holder.ADVAPI32.AddAccessAllowedAceEx(acl, ACL_REVISION, flags,
                            FILE_ALL_ACCESS, system)) {
                        throw failure(path, "InitializeAcl/AddAccessAllowedAceEx",
                            Native.getLastError());
                    }
                    // setting the owner needs WRITE_OWNER, even to the same SID: only if it differs
                    int information = DACL_SECURITY_INFORMATION
                        | PROTECTED_DACL_SECURITY_INFORMATION
                        | (ownerSid.equals(ownerSid(path)) ? 0 : OWNER_SECURITY_INFORMATION);
                    int error = Holder.ADVAPI32.SetNamedSecurityInfoW(name(path), SE_FILE_OBJECT,
                        information, owner, null, acl, null);
                    if (error != 0) {
                        throw failure(path, "SetNamedSecurityInfoW", error);
                    }
                }
            } finally {
                Holder.KERNEL32.LocalFree(system);
            }
        } finally {
            Holder.KERNEL32.LocalFree(owner);
        }
    }

    @Override
    public String ownerSid(Path path) throws IOException {
        PointerByReference owner = new PointerByReference();
        PointerByReference descriptor = new PointerByReference();
        int error = Holder.ADVAPI32.GetNamedSecurityInfoW(name(path), SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION, owner, null, null, null, descriptor);
        if (error != 0) {
            throw failure(path, "GetNamedSecurityInfoW", error);
        }
        try {
            return text(path, owner.getValue());
        } finally {
            Holder.KERNEL32.LocalFree(descriptor.getValue());
        }
    }

    @Override
    public Optional<List<Principal>> allowed(Path path) throws IOException {
        PointerByReference dacl = new PointerByReference();
        PointerByReference descriptor = new PointerByReference();
        int error = Holder.ADVAPI32.GetNamedSecurityInfoW(name(path), SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION, null, null, dacl, null, descriptor);
        if (error != 0) {
            throw failure(path, "GetNamedSecurityInfoW", error);
        }
        try {
            Pointer acl = dacl.getValue();
            if (acl == null) {
                return Optional.empty();
            }
            int count = Short.toUnsignedInt(acl.getShort(ACE_COUNT_OFFSET));
            List<Principal> principals = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                PointerByReference ace = new PointerByReference();
                if (!Holder.ADVAPI32.GetAce(acl, index, ace)) {
                    throw failure(path, "GetAce", Native.getLastError());
                }
                if (Byte.toUnsignedInt(ace.getValue().getByte(0)) == ACCESS_ALLOWED_ACE_TYPE
                    && (ace.getValue().getByte(1) & INHERIT_ONLY_ACE) == 0) {
                    Pointer sid = ace.getValue().share(ACE_SID_OFFSET);
                    String text = text(path, sid);
                    principals.add(new Principal(text, account(sid).orElse(text)));
                }
            }
            return Optional.of(principals);
        } finally {
            Holder.KERNEL32.LocalFree(descriptor.getValue());
        }
    }

    private static WString name(Path path) {
        return new WString(path.toAbsolutePath().toString());
    }

    /** The binary SID of {@code text}; the caller frees it with {@code LocalFree}. */
    private static Pointer sid(Path path, String text) throws IOException {
        PointerByReference sid = new PointerByReference();
        if (!Holder.ADVAPI32.ConvertStringSidToSidW(new WString(text), sid)) {
            throw failure(path, "ConvertStringSidToSidW", Native.getLastError());
        }
        return sid.getValue();
    }

    private static String text(Path path, Pointer sid) throws IOException {
        PointerByReference text = new PointerByReference();
        if (!Holder.ADVAPI32.ConvertSidToStringSidW(sid, text)) {
            throw failure(path, "ConvertSidToStringSidW", Native.getLastError());
        }
        try {
            return text.getValue().getWideString(0);
        } finally {
            Holder.KERNEL32.LocalFree(text.getValue());
        }
    }

    /** {@code DOMAIN\name} of {@code sid}; empty if it cannot be looked up. */
    private static Optional<String> account(Pointer sid) {
        char[] name = new char[NAME_LENGTH];
        char[] domain = new char[NAME_LENGTH];
        if (!Holder.ADVAPI32.LookupAccountSidW(null, sid, name, new IntByReference(NAME_LENGTH),
            domain, new IntByReference(NAME_LENGTH), new IntByReference())) {
            return Optional.empty();
        }
        String account = Native.toString(name);
        String prefix = Native.toString(domain);
        return Optional.of(prefix.isEmpty() ? account : prefix + "\\" + account);
    }

    private static FileSystemException failure(Path path, String function, int error) {
        return new FileSystemException(path.toString(), null,
            function + " failed (Windows error " + error + ")");
    }
}
