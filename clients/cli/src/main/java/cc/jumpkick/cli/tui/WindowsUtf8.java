// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.jdk.HostPlatform;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows console UTF-8 bootstrap: set the console input/output code pages to {@code 65001}, enable
 * virtual terminal processing, and retarget {@link System#out}/{@link System#err} to UTF-8
 * {@link PrintStream}s over the real stdio FDs.
 *
 * <p>Without this, the OEM console code page (often CP437) interprets UTF-8 glyph bytes as
 * mojibake ({@code ●} → {@code ΓùÅ}). {@code WriteConsoleW} can avoid the code page, but routing
 * every chrome write through JLine is fragile; flipping the console to UTF-8 matches what the
 * glyphs already assume.
 *
 * <p>No-op on non-Windows. Best-effort: code-page / VTP failures still retarget the Java streams
 * so thin-JVM hosts with {@code stdout.encoding=Cp1252} stop replacing unmappable glyphs with
 * {@code ?}.
 */
public final class WindowsUtf8 {

    /** Windows UTF-8 code page. */
    static final int CP_UTF8 = 65_001;

    private static final int STD_OUTPUT_HANDLE = -11;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;

    private static volatile boolean enabled;

    private WindowsUtf8() {}

    /**
     * Enable UTF-8 for the Windows console and Java stdio streams. Idempotent; safe to call before
     * any output.
     */
    public static void enable() {
        if (enabled) return;
        if (!HostPlatform.isWindows()) return;
        synchronized (WindowsUtf8.class) {
            if (enabled) return;
            try {
                setUtf8CodePages();
                enableVirtualTerminalProcessing();
            } catch (Throwable ignored) {
                // Best-effort native calls — still retarget Java streams below.
            }
            System.setOut(utf8Stream(FileDescriptor.out));
            System.setErr(utf8Stream(FileDescriptor.err));
            enabled = true;
        }
    }

    /** {@code true} after a successful {@link #enable()} on Windows. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Test-only: clear the enabled flag (does not restore console code pages or streams). */
    static synchronized void resetForTest() {
        enabled = false;
    }

    static PrintStream utf8Stream(FileDescriptor fd) {
        return new PrintStream(new FileOutputStream(fd), true, StandardCharsets.UTF_8);
    }

    private static void setUtf8CodePages() throws Throwable {
        MethodHandle setOutput =
                downcall("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle setInput =
                downcall("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // invokeExact is signature-polymorphic: a non-void return must be cast or the call
        // is typed as void and throws WrongMethodTypeException (swallowed by enable()).
        // Zero returns are normal when there is no console (redirected stdio).
        int outOk = (int) setOutput.invokeExact(CP_UTF8);
        int inOk = (int) setInput.invokeExact(CP_UTF8);
        if (outOk == 0 && inOk == 0) {
            return; // no console — UTF-8 Java streams still installed by enable()
        }
    }

    private static void enableVirtualTerminalProcessing() throws Throwable {
        MethodHandle getStdHandle =
                downcall("GetStdHandle", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MethodHandle getMode = downcall(
                "GetConsoleMode",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle setMode = downcall(
                "SetConsoleMode",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(STD_OUTPUT_HANDLE);
        if (handle.address() == 0L || handle.address() == -1L) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment modeBuf = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) getMode.invokeExact(handle, modeBuf);
            if (ok == 0) return;
            int mode = modeBuf.get(ValueLayout.JAVA_INT, 0);
            if ((mode & ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0) return;
            // VTP unavailable on old conhost → CSI paint may scroll; glyphs still OK via UTF-8 CP.
            int setOk = (int) setMode.invokeExact(handle, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
            if (setOk == 0) return;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MemorySegment sym = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return linker.downcallHandle(sym, desc);
    }
}
