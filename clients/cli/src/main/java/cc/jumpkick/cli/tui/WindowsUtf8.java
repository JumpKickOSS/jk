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
 * <p>Runs from {@code Jk.main} before the first write, so every stream {@link
 * cc.jumpkick.cli.CliOutput} later opens is built over the retargeted {@link System#out}. {@code
 * CliOutput} reads its charset off that object through {@link PrintStream#charset()}, not off a
 * {@code stdout.encoding} property this class never sets — so there is no ordering contract between
 * the two and no way for them to disagree about how a glyph becomes bytes.
 *
 * <p>No-op on non-Windows. Best-effort: code-page / VTP failures still retarget the Java streams
 * so thin-JVM hosts with {@code stdout.encoding=Cp1252} stop replacing unmappable glyphs with
 * {@code ?}.
 */
public final class WindowsUtf8 {

    /** Windows UTF-8 code page. */
    private static final int CP_UTF8 = 65_001;

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
        // is typed as void and throws WrongMethodTypeException (swallowed by enable()). Nothing
        // acts on the result — a zero just means stdio is redirected and there is no console to
        // reconfigure, and enable() installs the UTF-8 Java streams either way.
        int ignoredOutCp = (int) setOutput.invokeExact(CP_UTF8);
        int ignoredInCp = (int) setInput.invokeExact(CP_UTF8);
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
            // VTP unavailable on old conhost → CSI paint may scroll; glyphs still come out right
            // via the UTF-8 code page, so nothing acts on the result. Cast for the same
            // signature-polymorphism reason as in setUtf8CodePages.
            int ignoredSetMode = (int) setMode.invokeExact(handle, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup("kernel32", Arena.global());
        MemorySegment sym = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return linker.downcallHandle(sym, desc);
    }
}
