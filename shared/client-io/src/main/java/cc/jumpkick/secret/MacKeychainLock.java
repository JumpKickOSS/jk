// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.secret;

import cc.jumpkick.host.Os;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.jspecify.annotations.Nullable;

/**
 * Whether the default macOS keychain is unlocked, from {@code SecKeychainCopyDefault} and {@code
 * SecKeychainGetStatus} in {@code Security.framework}, called in process. Reading the status shows
 * nothing and takes microseconds, where anything that asks the keychain for an item or its
 * settings may put up an unlock dialog in a desktop session.
 *
 * <p>It matters for reads too: a search of a locked keychain over SSH answers "not found"
 * ({@code errSecItemNotFound}), the same as an absent item, so without the status a locked keychain
 * would read as a login that was never made.
 */
@FunctionalInterface
interface MacKeychainLock {

    /** What the status says. */
    enum State {
        UNLOCKED,
        LOCKED,
        /** This user has no default keychain. */
        NO_KEYCHAIN,
        /** The status cannot be read here (not macOS, or no native access); ask {@code security}. */
        UNKNOWN
    }

    /** The default keychain's state; {@code reason} says more when it is not unlocked. */
    record Status(State state, @Nullable String reason) {}

    Status status();

    /** A lock whose state is never known, so the backend asks {@code security}: tests. */
    MacKeychainLock UNKNOWN = () -> new Status(State.UNKNOWN, "not read");

    /** This process's default keychain. */
    MacKeychainLock HOST = () -> Framework.binding().status();

    /** The bound calls, made once, on first use: never while a native image is built. */
    final class Framework {
        private static final String SECURITY = "/System/Library/Frameworks/Security.framework/Security";
        private static final String CORE_FOUNDATION =
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

        /** {@code kSecUnlockStateStatus}. */
        private static final int UNLOCKED_BIT = 1;

        /** {@code errSecNoDefaultKeychain}. */
        private static final int NO_DEFAULT_KEYCHAIN = -25307;

        private final @Nullable MethodHandle copyDefault;
        private final @Nullable MethodHandle getStatus;
        private final @Nullable MethodHandle release;
        private final @Nullable String unbound;

        private Framework(
                @Nullable MethodHandle copyDefault,
                @Nullable MethodHandle getStatus,
                @Nullable MethodHandle release,
                @Nullable String unbound) {
            this.copyDefault = copyDefault;
            this.getStatus = getStatus;
            this.release = release;
            this.unbound = unbound;
        }

        private static final class Holder {
            static final Framework BOUND = bind();
        }

        static Framework binding() {
            return Holder.BOUND;
        }

        @SuppressWarnings("restricted")
        private static Framework bind() {
            if (!Os.isDarwin()) return new Framework(null, null, null, "Security.framework exists only on macOS");
            if (!Framework.class.getModule().isNativeAccessEnabled()) {
                return new Framework(null, null, null, "this JVM was started without --enable-native-access");
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup security = SymbolLookup.libraryLookup(SECURITY, Arena.global());
                SymbolLookup cf = SymbolLookup.libraryLookup(CORE_FOUNDATION, Arena.global());
                return new Framework(
                        linker.downcallHandle(
                                security.findOrThrow("SecKeychainCopyDefault"),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                security.findOrThrow("SecKeychainGetStatus"),
                                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                        linker.downcallHandle(
                                cf.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                        null);
            } catch (Throwable unbound) {
                return new Framework(null, null, null, "Security.framework could not be bound: " + unbound);
            }
        }

        Status status() {
            MethodHandle copy = copyDefault, get = getStatus, free = release;
            if (copy == null || get == null || free == null) return new Status(State.UNKNOWN, unbound);
            AddressLayout address = ValueLayout.ADDRESS;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(address);
                int rc = (int) copy.invokeExact(out);
                if (rc == NO_DEFAULT_KEYCHAIN)
                    return new Status(State.NO_KEYCHAIN, "this user has no default keychain");
                if (rc != 0) return new Status(State.UNKNOWN, "SecKeychainCopyDefault answered OSStatus " + rc);
                MemorySegment keychain = out.get(address, 0);
                try {
                    MemorySegment bits = arena.allocate(ValueLayout.JAVA_INT);
                    rc = (int) get.invokeExact(keychain, bits);
                    if (rc != 0) return new Status(State.UNKNOWN, "SecKeychainGetStatus answered OSStatus " + rc);
                    return (bits.get(ValueLayout.JAVA_INT, 0) & UNLOCKED_BIT) != 0
                            ? new Status(State.UNLOCKED, null)
                            : new Status(State.LOCKED, "the default keychain is locked");
                } finally {
                    free.invokeExact(keychain);
                }
            } catch (Throwable t) {
                return new Status(State.UNKNOWN, "Security.framework failed: " + t);
            }
        }
    }
}
