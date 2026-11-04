// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code CredReadW}, {@code CredWriteW}, {@code CredDeleteW} and {@code CredFree} from {@code
 * advapi32}, called through the foreign-function API. Every call answers its Win32 error, captured
 * with the call ({@code GetLastError}), so nothing between the call and the read can overwrite it.
 *
 * <p>The handles are bound on first use, never while a native image is built: an image that
 * initialized a class holding downcall handles at build time does not link. The four downcall
 * shapes are registered in this module's {@code reachability-metadata.json}.
 */
final class Advapi32Credentials implements WindowsCredentialBackend.Calls {

    /** {@code CRED_TYPE_GENERIC}. */
    static final int CRED_TYPE_GENERIC = 1;

    /** {@code CRED_PERSIST_LOCAL_MACHINE}: survives logoff, stays on this machine (no roaming). */
    static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    /**
     * {@code CREDENTIALW} on 64-bit Windows: 80 bytes, pointers 8-aligned, {@code FILETIME} as two
     * {@code DWORD}s, four bytes of padding before {@code CredentialBlob}.
     */
    static final StructLayout CREDENTIAL = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Flags"),
            ValueLayout.JAVA_INT.withName("Type"),
            ValueLayout.ADDRESS.withName("TargetName"),
            ValueLayout.ADDRESS.withName("Comment"),
            ValueLayout.JAVA_INT.withName("LastWrittenLow"),
            ValueLayout.JAVA_INT.withName("LastWrittenHigh"),
            ValueLayout.JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("CredentialBlob"),
            ValueLayout.JAVA_INT.withName("Persist"),
            ValueLayout.JAVA_INT.withName("AttributeCount"),
            ValueLayout.ADDRESS.withName("Attributes"),
            ValueLayout.ADDRESS.withName("TargetAlias"),
            ValueLayout.ADDRESS.withName("UserName"));

    private static long offset(String field) {
        return CREDENTIAL.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    static final long TYPE = offset("Type");
    static final long TARGET_NAME = offset("TargetName");
    static final long COMMENT = offset("Comment");
    static final long BLOB_SIZE = offset("CredentialBlobSize");
    static final long BLOB = offset("CredentialBlob");
    static final long PERSIST = offset("Persist");
    static final long USER_NAME = offset("UserName");

    private final MethodHandle credRead;
    private final MethodHandle credWrite;
    private final MethodHandle credDelete;
    private final MethodHandle credFree;
    private final StructLayout stateLayout;
    private final VarHandle lastError;

    private Advapi32Credentials(
            MethodHandle credRead,
            MethodHandle credWrite,
            MethodHandle credDelete,
            MethodHandle credFree,
            StructLayout stateLayout,
            VarHandle lastError) {
        this.credRead = credRead;
        this.credWrite = credWrite;
        this.credDelete = credDelete;
        this.credFree = credFree;
        this.stateLayout = stateLayout;
        this.lastError = lastError;
    }

    /** The bound calls, or why they cannot be bound here. */
    record Binding(
            WindowsCredentialBackend.@Nullable Calls calls,
            @Nullable String reason) {}

    private static final class Holder {
        static final Binding BINDING = bind();
    }

    /** This process's binding, made once. */
    static Binding binding() {
        return Holder.BINDING;
    }

    @SuppressWarnings("restricted")
    private static Binding bind() {
        if (!Os.isWindows()) return new Binding(null, "advapi32 exists only on Windows");
        if (!Advapi32Credentials.class.getModule().isNativeAccessEnabled()) {
            return new Binding(null, "this JVM was started without --enable-native-access");
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup advapi = SymbolLookup.libraryLookup("advapi32", Arena.global());
            Linker.Option capture = Linker.Option.captureCallState("GetLastError");
            StructLayout state = Linker.Option.captureStateLayout();
            VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
            MethodHandle read = linker.downcallHandle(
                    advapi.findOrThrow("CredReadW"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS),
                    capture);
            MethodHandle write = linker.downcallHandle(
                    advapi.findOrThrow("CredWriteW"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    capture);
            MethodHandle delete = linker.downcallHandle(
                    advapi.findOrThrow("CredDeleteW"),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    capture);
            MethodHandle free = linker.downcallHandle(
                    advapi.findOrThrow("CredFree"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            return new Binding(new Advapi32Credentials(read, write, delete, free, state, lastError), null);
        } catch (Throwable unbound) {
            return new Binding(null, "advapi32 could not be bound: " + unbound);
        }
    }

    @Override
    public WindowsCredentialBackend.ReadResult read(String target) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(stateLayout);
            MemorySegment name = arena.allocateFrom(target, StandardCharsets.UTF_16LE);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int ok = (int) credRead.invokeExact(state, name, CRED_TYPE_GENERIC, 0, out);
            if (ok == 0) return new WindowsCredentialBackend.ReadResult(error(state), null);
            MemorySegment cred = out.get(ValueLayout.ADDRESS, 0).reinterpret(CREDENTIAL.byteSize());
            try {
                int size = cred.get(ValueLayout.JAVA_INT, BLOB_SIZE);
                MemorySegment blob = cred.get(ValueLayout.ADDRESS, BLOB);
                byte[] bytes = size == 0 || blob.address() == 0L
                        ? new byte[0]
                        : blob.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
                return new WindowsCredentialBackend.ReadResult(0, bytes);
            } finally {
                credFree.invokeExact(cred);
            }
        } catch (Throwable t) {
            throw failed("CredReadW", t);
        }
    }

    @Override
    public int write(String target, String comment, String userName, byte[] blob) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(stateLayout);
            MemorySegment cred = arena.allocate(CREDENTIAL);
            MemorySegment data = arena.allocate(Math.max(blob.length, 1));
            try {
                MemorySegment.copy(blob, 0, data, ValueLayout.JAVA_BYTE, 0, blob.length);
                cred.set(ValueLayout.JAVA_INT, TYPE, CRED_TYPE_GENERIC);
                cred.set(ValueLayout.ADDRESS, TARGET_NAME, arena.allocateFrom(target, StandardCharsets.UTF_16LE));
                cred.set(ValueLayout.ADDRESS, COMMENT, arena.allocateFrom(comment, StandardCharsets.UTF_16LE));
                cred.set(ValueLayout.JAVA_INT, BLOB_SIZE, blob.length);
                cred.set(ValueLayout.ADDRESS, BLOB, data);
                cred.set(ValueLayout.JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
                cred.set(ValueLayout.ADDRESS, USER_NAME, arena.allocateFrom(userName, StandardCharsets.UTF_16LE));
                int ok = (int) credWrite.invokeExact(state, cred, 0);
                return ok == 0 ? error(state) : 0;
            } finally {
                // The arena's memory goes back to the allocator; the secret does not go with it.
                data.fill((byte) 0);
            }
        } catch (Throwable t) {
            throw failed("CredWriteW", t);
        }
    }

    @Override
    public int delete(String target) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(stateLayout);
            MemorySegment name = arena.allocateFrom(target, StandardCharsets.UTF_16LE);
            int ok = (int) credDelete.invokeExact(state, name, CRED_TYPE_GENERIC, 0);
            return ok == 0 ? error(state) : 0;
        } catch (Throwable t) {
            throw failed("CredDeleteW", t);
        }
    }

    private int error(MemorySegment state) {
        int e = (int) lastError.get(state, 0L);
        // A failed call that left no error still failed; report it as ERROR_GEN_FAILURE.
        return e == 0 ? 31 : e;
    }

    private static SecretBackendException failed(String call, Throwable t) {
        Objects.requireNonNull(t, "t");
        return new SecretBackendException(call + " could not be called: " + t, t);
    }
}
