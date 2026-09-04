// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.posix;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Login shell from the account database: {@code getpwuid} (nsswitch / Directory Services), then
 * {@code /etc/passwd}.
 */
public final class PosixPasswd {
    /** glibc/musl LP64 {@code struct passwd}: {@code pw_shell} after name, passwd, uid/gid, gecos, dir. */
    static final int PW_SHELL_LINUX = 40;

    /** Darwin LP64 {@code struct passwd}: extra {@code pw_change}/{@code pw_class} before gecos. */
    static final int PW_SHELL_DARWIN = 56;

    private static final int MAX_SHELL_BYTES = 4096;
    private static final Path ETC_PASSWD = Path.of("/etc/passwd");

    private static volatile MethodHandle getuidMh;
    private static volatile MethodHandle getpwuidMh;
    private static volatile boolean initAttempted;
    private static volatile Optional<String> cached;

    private PosixPasswd() {}

    /** {@code pw_shell} for the current uid, or empty on Windows / lookup failure. */
    public static Optional<String> loginShell() {
        Optional<String> hit = cached;
        if (hit != null) {
            return hit;
        }
        synchronized (PosixPasswd.class) {
            if (cached != null) {
                return cached;
            }
            cached = lookup();
            return cached;
        }
    }

    private static Optional<String> lookup() {
        if (Os.isWindows()) {
            return Optional.empty();
        }
        Optional<String> nativeShell = fromGetpwuid();
        if (nativeShell.isPresent()) {
            return nativeShell;
        }
        return fromEtcPasswd(ETC_PASSWD, System.getProperty("user.name", ""));
    }

    static Optional<String> fromGetpwuid() {
        if (Os.isWindows()) {
            return Optional.empty();
        }
        ensure();
        if (getuidMh == null || getpwuidMh == null) {
            return Optional.empty();
        }
        try {
            int uid = (int) getuidMh.invokeExact();
            MemorySegment raw = (MemorySegment) getpwuidMh.invokeExact(uid);
            if (raw == null || raw.address() == 0L) {
                return Optional.empty();
            }
            int offset = Os.isDarwin() ? PW_SHELL_DARWIN : PW_SHELL_LINUX;
            MemorySegment pwd = raw.reinterpret(offset + ValueLayout.ADDRESS.byteSize());
            MemorySegment shellPtr = pwd.get(ValueLayout.ADDRESS, offset);
            if (shellPtr == null || shellPtr.address() == 0L) {
                return Optional.empty();
            }
            String shell = shellPtr.reinterpret(MAX_SHELL_BYTES).getString(0);
            if (shell == null || shell.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(shell);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    static Optional<String> fromEtcPasswd(Path file, String userName) {
        if (userName == null || userName.isBlank()) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split(":", -1);
                if (fields.length < 7 || !userName.equals(fields[0])) {
                    continue;
                }
                String shell = fields[6];
                return shell.isBlank() ? Optional.empty() : Optional.of(shell);
            }
        } catch (IOException ignored) {
            // missing file or unreadable
        }
        return Optional.empty();
    }

    @SuppressWarnings("restricted")
    private static void ensure() {
        if (initAttempted) {
            return;
        }
        synchronized (PosixPasswd.class) {
            if (initAttempted) {
                return;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                getuidMh = bind(linker, lookup, "getuid", FunctionDescriptor.of(ValueLayout.JAVA_INT));
                getpwuidMh = bind(
                        linker, lookup, "getpwuid", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            } finally {
                initAttempted = true;
            }
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc) {
        try {
            return linker.downcallHandle(lookup.findOrThrow(name), desc);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
