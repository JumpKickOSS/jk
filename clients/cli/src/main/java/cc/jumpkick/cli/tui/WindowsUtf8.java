// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.jdk.HostPlatform;
import java.io.BufferedOutputStream;
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
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static int previousOutputCp = -1;
    private static int previousInputCp = -1;

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
                saveAndSetUtf8CodePages();
                enableVirtualTerminalProcessing();
                if (previousOutputCp > 0 || previousInputCp > 0) {
                    // The console code page belongs to the SESSION (the conhost / Windows
                    // Terminal tab), not to this process — without a restore, every jk
                    // invocation leaves the window silently switched to chcp 65001, changing
                    // how unrelated later commands decode OEM/ANSI text. VTP is left enabled
                    // on purpose: it is per-handle-inherited, modern terminals enable it
                    // anyway, and toggling it back mid-session can glitch a live paint.
                    Runtime.getRuntime()
                            .addShutdownHook(new Thread(WindowsUtf8::restoreCodePages, "jk-console-cp-restore"));
                }
            } catch (Throwable ignored) {
                // Best-effort native calls — still retarget Java streams below.
            }
            originalOut = System.out;
            originalErr = System.err;
            System.setOut(utf8Stream(FileDescriptor.out));
            System.setErr(utf8Stream(FileDescriptor.err));
            enabled = true;
        }
    }

    /** Test-only: restore the swapped streams and console code pages, then clear the flag. */
    static synchronized void resetForTest() {
        if (originalOut != null) System.setOut(originalOut);
        if (originalErr != null) System.setErr(originalErr);
        restoreCodePages();
        originalOut = null;
        originalErr = null;
        previousOutputCp = -1;
        previousInputCp = -1;
        enabled = false;
    }

    static PrintStream utf8Stream(FileDescriptor fd) {
        // Mirror the JVM's own stdio construction: a buffer under the PrintStream (small +
        // autoflush on a live console, 8 KiB without autoflush when redirected) — a bare
        // FileOutputStream turns every chunk write into its own WriteFile call.
        boolean console = System.console() != null;
        return new PrintStream(
                new BufferedOutputStream(new FileOutputStream(fd), console ? 128 : 8192),
                console,
                StandardCharsets.UTF_8);
    }

    private static void saveAndSetUtf8CodePages() throws Throwable {
        MethodHandle getOutput = downcall("GetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle getInput = downcall("GetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle setOutput =
                downcall("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle setInput =
                downcall("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        int outCp = (int) getOutput.invokeExact();
        int inCp = (int) getInput.invokeExact();
        // invokeExact is signature-polymorphic: a non-void return must be cast or the call
        // is typed as void and throws WrongMethodTypeException (swallowed by enable()). Nothing
        // acts on the set results — a zero just means stdio is redirected and there is no console
        // to reconfigure, and enable() installs the UTF-8 Java streams either way.
        int ignoredOutCp = (int) setOutput.invokeExact(CP_UTF8);
        int ignoredInCp = (int) setInput.invokeExact(CP_UTF8);
        previousOutputCp = outCp > 0 && outCp != CP_UTF8 ? outCp : -1;
        previousInputCp = inCp > 0 && inCp != CP_UTF8 ? inCp : -1;
    }

    /**
     * Put the console code pages back the way {@link #enable} found them — unless something else
     * changed them since (a nested {@code chcp}, another tool's bootstrap): then the session isn't
     * ours to restore.
     */
    private static void restoreCodePages() {
        try {
            MethodHandle getOutput = downcall("GetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle getInput = downcall("GetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle setOutput =
                    downcall("SetConsoleOutputCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            MethodHandle setInput =
                    downcall("SetConsoleCP", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            if (previousOutputCp > 0 && (int) getOutput.invokeExact() == CP_UTF8) {
                int ignoredOut = (int) setOutput.invokeExact(previousOutputCp);
            }
            if (previousInputCp > 0 && (int) getInput.invokeExact() == CP_UTF8) {
                int ignoredIn = (int) setInput.invokeExact(previousInputCp);
            }
        } catch (Throwable ignored) {
            // Best-effort — never let a restore failure surface at exit.
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
            // VTP unavailable on old conhost → CSI paint may scroll; glyphs still come out right
            // via the UTF-8 code page, so nothing acts on the result. Cast for the same
            // signature-polymorphism reason as in setUtf8CodePages.
            int ignoredSetMode = (int) setMode.invokeExact(handle, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
        }
    }

    /** Lazy holder: kernel32 loads once, and only when a downcall actually runs (Windows only). */
    private static final class Kernel32 {
        static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup("kernel32", Arena.global());
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        MemorySegment sym = Kernel32.LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return Linker.nativeLinker().downcallHandle(sym, desc);
    }
}
