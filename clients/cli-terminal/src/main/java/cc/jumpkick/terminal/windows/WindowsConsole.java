// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Os;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Session {@code CONIN$}/{@code CONOUT$}. Input is {@code WaitForSingleObject} + {@code ReadFile}
 * UTF-8. Output {@code WriteFile} is blocking. VTP is ORed onto the session CONOUT$ handle at open.
 */
public final class WindowsConsole implements AutoCloseable {
    static final int GENERIC_READ = 0x80000000;
    static final int GENERIC_WRITE = 0x40000000;
    static final int FILE_SHARE_READ = 0x1;
    static final int FILE_SHARE_WRITE = 0x2;
    static final int OPEN_EXISTING = 3;
    static final int HANDLE_FLAG_INHERIT = 0x1;
    static final int WAIT_OBJECT_0 = 0;
    static final int WAIT_TIMEOUT = 258;
    static final int WAIT_FAILED = 0xFFFFFFFF;
    static final int INFINITE = 0xFFFFFFFF;
    static final int ENABLE_PROCESSED_INPUT = 0x1;
    static final int ENABLE_LINE_INPUT = 0x2;
    static final int ENABLE_ECHO_INPUT = 0x4;
    static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x200;
    static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;

    private static final int SLICE_MS = 50;

    private final MemorySegment conIn;
    private final MemorySegment conOut;
    private final int savedIn;
    private final int savedOut;
    private volatile boolean open = true;

    private WindowsConsole(MemorySegment conIn, MemorySegment conOut, int savedIn, int savedOut) {
        this.conIn = conIn;
        this.conOut = conOut;
        this.savedIn = savedIn;
        this.savedOut = savedOut;
    }

    public static WindowsConsole openControlling() {
        if (!Os.isWindows()) {
            return null;
        }
        ensure();
        if (createFileW == null) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inName = wchar(arena, "CONIN$");
            MemorySegment outName = wchar(arena, "CONOUT$");
            MemorySegment conIn = (MemorySegment) createFileW.invokeExact(
                    inName,
                    GENERIC_READ | GENERIC_WRITE,
                    FILE_SHARE_READ | FILE_SHARE_WRITE,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    0,
                    MemorySegment.NULL);
            if (invalid(conIn)) {
                return null;
            }
            MemorySegment conOut = (MemorySegment) createFileW.invokeExact(
                    outName,
                    GENERIC_READ | GENERIC_WRITE,
                    FILE_SHARE_READ | FILE_SHARE_WRITE,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    0,
                    MemorySegment.NULL);
            if (invalid(conOut)) {
                int ignored = (int) closeHandle.invokeExact(conIn);
                return null;
            }
            int clearedIn = (int) setHandleInformation.invokeExact(conIn, HANDLE_FLAG_INHERIT, 0);
            int clearedOut = (int) setHandleInformation.invokeExact(conOut, HANDLE_FLAG_INHERIT, 0);
            MemorySegment modeBuf = arena.allocate(ValueLayout.JAVA_INT);
            if ((int) getConsoleMode.invokeExact(conIn, modeBuf) == 0) {
                closeBoth(conIn, conOut);
                return null;
            }
            int savedIn = modeBuf.get(ValueLayout.JAVA_INT, 0);
            if ((int) getConsoleMode.invokeExact(conOut, modeBuf) == 0) {
                closeBoth(conIn, conOut);
                return null;
            }
            int savedOut = modeBuf.get(ValueLayout.JAVA_INT, 0);
            int setVtp = (int) setConsoleMode.invokeExact(conOut, savedOut | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
            return new WindowsConsole(conIn, conOut, savedIn, savedOut);
        } catch (Throwable t) {
            return null;
        }
    }

    public void apply(InputMode mode) {
        if (!open) {
            return;
        }
        int next = savedIn;
        if (mode == InputMode.PROMPT) {
            next = (savedIn & ~(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT))
                    | ENABLE_VIRTUAL_TERMINAL_INPUT;
        } else if (mode == InputMode.PLAN_KEYS) {
            next = (savedIn & ~(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT))
                    | ENABLE_PROCESSED_INPUT
                    | ENABLE_VIRTUAL_TERMINAL_INPUT;
        }
        try {
            int ignored = (int) setConsoleMode.invokeExact(conIn, next);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    public void restoreOriginal() {
        if (!open) {
            return;
        }
        try {
            int in = (int) setConsoleMode.invokeExact(conIn, savedIn);
            int out = (int) setConsoleMode.invokeExact(conOut, savedOut | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    public int readByte(Duration timeout, BooleanSupplier live) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be >= 0");
        }
        boolean forever = timeout.isZero();
        long deadline = forever ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        ensure();
        while (live.getAsBoolean() && open) {
            if (!forever && System.nanoTime() >= deadline) {
                return -1;
            }
            int waitMs = forever
                    ? INFINITE
                    : (int) Math.min(Integer.MAX_VALUE, Math.max(0, (deadline - System.nanoTime()) / 1_000_000L));
            long t0 = System.nanoTime();
            try (Arena arena = Arena.ofConfined()) {
                int waited = (int) waitForSingleObject.invokeExact(conIn, waitMs);
                if (waited == WAIT_TIMEOUT) {
                    return -1;
                }
                if (waited == WAIT_FAILED) {
                    return -2;
                }
                MemorySegment buf = arena.allocate(8);
                MemorySegment nRead = arena.allocate(ValueLayout.JAVA_INT);
                int ok = (int) readFile.invokeExact(conIn, buf, 1, nRead, MemorySegment.NULL);
                int n = nRead.get(ValueLayout.JAVA_INT, 0);
                if (ok != 0 && n > 0) {
                    return Byte.toUnsignedInt(buf.get(ValueLayout.JAVA_BYTE, 0));
                }
                long elapsed = System.nanoTime() - t0;
                if (waited == WAIT_OBJECT_0 && elapsed < 1_000_000L) {
                    Thread.sleep(SLICE_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (Throwable t) {
                return -2;
            }
        }
        return -1;
    }

    public void writeFully(byte[] buf, BooleanSupplier live) {
        if (!open || buf.length == 0 || !live.getAsBoolean()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocate(buf.length);
            MemorySegment.copy(MemorySegment.ofArray(buf), 0, src, 0, buf.length);
            MemorySegment nWritten = arena.allocate(ValueLayout.JAVA_INT);
            int ignored = (int) writeFile.invokeExact(conOut, src, buf.length, nWritten, MemorySegment.NULL);
        } catch (Throwable ignored) {
            // best-effort paint
        }
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        restoreOriginal();
        closeBoth(conIn, conOut);
    }

    private static MemorySegment wchar(Arena arena, String s) {
        return arena.allocateFrom(ValueLayout.JAVA_CHAR, (s + "\0").toCharArray());
    }

    private static boolean invalid(MemorySegment h) {
        return h == null || h.address() == 0L || h.address() == -1L;
    }

    private static void closeBoth(MemorySegment in, MemorySegment out) {
        try {
            if (closeHandle != null) {
                if (!invalid(in)) {
                    int a = (int) closeHandle.invokeExact(in);
                }
                if (!invalid(out)) {
                    int b = (int) closeHandle.invokeExact(out);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private static volatile MethodHandle createFileW;
    private static volatile MethodHandle closeHandle;
    private static volatile MethodHandle setHandleInformation;
    private static volatile MethodHandle getConsoleMode;
    private static volatile MethodHandle setConsoleMode;
    private static volatile MethodHandle waitForSingleObject;
    private static volatile MethodHandle readFile;
    private static volatile MethodHandle writeFile;
    private static volatile boolean initAttempted;

    @SuppressWarnings("restricted")
    private static void ensure() {
        if (initAttempted || !Os.isWindows()) {
            return;
        }
        synchronized (WindowsConsole.class) {
            if (initAttempted) {
                return;
            }
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                createFileW = linker.downcallHandle(
                        k32.findOrThrow("CreateFileW"),
                        FunctionDescriptor.of(
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS));
                closeHandle = linker.downcallHandle(
                        k32.findOrThrow("CloseHandle"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                setHandleInformation = linker.downcallHandle(
                        k32.findOrThrow("SetHandleInformation"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                getConsoleMode = linker.downcallHandle(
                        k32.findOrThrow("GetConsoleMode"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                setConsoleMode = linker.downcallHandle(
                        k32.findOrThrow("SetConsoleMode"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                waitForSingleObject = linker.downcallHandle(
                        k32.findOrThrow("WaitForSingleObject"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                readFile = linker.downcallHandle(
                        k32.findOrThrow("ReadFile"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                writeFile = linker.downcallHandle(
                        k32.findOrThrow("WriteFile"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
            } catch (Throwable ignored) {
                // leave null
            } finally {
                initAttempted = true;
            }
        }
    }
}
