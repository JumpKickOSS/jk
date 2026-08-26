// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal.windows;

import cc.jumpkick.host.Os;
import cc.jumpkick.terminal.InputMode;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;

/**
 * Session {@code CONIN$}/{@code CONOUT$}. Input is {@code WaitForSingleObject} then
 * {@code PeekConsoleInputW}/{@code ReadConsoleInputW} — never {@code ReadFile}. A console input
 * handle stays signaled for KEY_UP, mouse, and focus records; {@code ReadFile} then waits for a
 * character after the wait timeout has already elapsed. Output {@code WriteFile} is blocking. VTP
 * is ORed onto the session CONOUT$ handle at open.
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
    static final int ENABLE_WINDOW_INPUT = 0x8;
    static final int ENABLE_MOUSE_INPUT = 0x10;
    static final int ENABLE_QUICK_EDIT_MODE = 0x40;
    static final int ENABLE_EXTENDED_FLAGS = 0x80;
    static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x200;
    static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x4;
    static final int KEY_EVENT = 0x1;
    static final int INPUT_RECORD_BYTES = 20;
    static final int OFF_EVENT_TYPE = 0;
    static final int OFF_KEY_DOWN = 4;
    static final int OFF_REPEAT = 8;
    static final int OFF_VK = 10;
    static final int OFF_UNICODE = 14;
    static final int VK_LEFT = 0x25;
    static final int VK_UP = 0x26;
    static final int VK_RIGHT = 0x27;
    static final int VK_DOWN = 0x28;

    private static final int SLICE_MS = 50;
    private static final byte[] EMPTY = new byte[0];

    private final MemorySegment conIn;
    private final MemorySegment conOut;
    private final int savedIn;
    private final int savedOut;
    private final ArrayDeque<Integer> queued = new ArrayDeque<>();
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

    /**
     * {@code PROMPT}/{@code PLAN_KEYS} drop line/echo/mouse/window/quick-edit so the wait handle
     * is not left signaled by records {@code ReadFile} would ignore. {@code PLAN_KEYS} keeps
     * processed input (Ctrl-C is a control event). {@code PROMPT} clears it (Ctrl-C is {@code 0x03}).
     */
    static int inputModeBits(int savedIn, InputMode mode) {
        if (mode == InputMode.COOKED || mode == InputMode.INHERIT_CHILD) {
            return savedIn;
        }
        int clear = ENABLE_LINE_INPUT
                | ENABLE_ECHO_INPUT
                | ENABLE_MOUSE_INPUT
                | ENABLE_WINDOW_INPUT
                | ENABLE_QUICK_EDIT_MODE;
        int next = (savedIn & ~clear) | ENABLE_EXTENDED_FLAGS;
        if (mode == InputMode.PROMPT) {
            return next & ~ENABLE_PROCESSED_INPUT;
        }
        return next | ENABLE_PROCESSED_INPUT;
    }

    /**
     * UTF-8 bytes for one {@code KEY_EVENT_RECORD}, or empty to discard (key-up, modifiers, mouse).
     */
    static byte[] bytesForKeyEvent(int eventType, int keyDown, char unicode, int vk) {
        if (eventType != KEY_EVENT || keyDown == 0) {
            return EMPTY;
        }
        if (unicode != 0) {
            if (unicode < 128) {
                return new byte[] {(byte) unicode};
            }
            return Character.toString(unicode).getBytes(StandardCharsets.UTF_8);
        }
        return switch (vk) {
            case VK_UP -> new byte[] {0x1B, '[', 'A'};
            case VK_DOWN -> new byte[] {0x1B, '[', 'B'};
            case VK_RIGHT -> new byte[] {0x1B, '[', 'C'};
            case VK_LEFT -> new byte[] {0x1B, '[', 'D'};
            default -> EMPTY;
        };
    }

    public void apply(InputMode mode) {
        if (!open) {
            return;
        }
        int next = inputModeBits(savedIn, mode);
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
        if (!queued.isEmpty()) {
            return queued.removeFirst();
        }
        boolean forever = timeout.isZero();
        long deadline = forever ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        ensure();
        if (waitForSingleObject == null || peekConsoleInputW == null || readConsoleInputW == null) {
            return -1;
        }
        while (live.getAsBoolean() && open) {
            if (!forever && System.nanoTime() >= deadline) {
                return -1;
            }
            if (!queued.isEmpty()) {
                return queued.removeFirst();
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
                int b = takeQueuedKeyByte(arena);
                if (b >= 0) {
                    return b;
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

    /**
     * Peek the front record; only then {@code ReadConsoleInputW} so an empty buffer cannot block.
     * Discards non-key records. Returns the first UTF-8 byte, or -1 if nothing to emit.
     */
    private int takeQueuedKeyByte(Arena arena) throws Throwable {
        MemorySegment rec = arena.allocate(INPUT_RECORD_BYTES);
        MemorySegment nRead = arena.allocate(ValueLayout.JAVA_INT);
        int peeked = (int) peekConsoleInputW.invokeExact(conIn, rec, 1, nRead);
        if (peeked == 0 || nRead.get(ValueLayout.JAVA_INT, 0) <= 0) {
            return -1;
        }
        int consumed = (int) readConsoleInputW.invokeExact(conIn, rec, 1, nRead);
        if (consumed == 0 || nRead.get(ValueLayout.JAVA_INT, 0) <= 0) {
            return -1;
        }
        int eventType = Short.toUnsignedInt(rec.get(ValueLayout.JAVA_SHORT, OFF_EVENT_TYPE));
        int keyDown = rec.get(ValueLayout.JAVA_INT, OFF_KEY_DOWN);
        int vk = Short.toUnsignedInt(rec.get(ValueLayout.JAVA_SHORT, OFF_VK));
        char unicode = rec.get(ValueLayout.JAVA_CHAR, OFF_UNICODE);
        int repeat = Math.max(1, Short.toUnsignedInt(rec.get(ValueLayout.JAVA_SHORT, OFF_REPEAT)));
        byte[] bytes = bytesForKeyEvent(eventType, keyDown, unicode, vk);
        if (bytes.length == 0) {
            return -1;
        }
        for (int r = 0; r < repeat; r++) {
            for (byte value : bytes) {
                queued.addLast(Byte.toUnsignedInt(value));
            }
        }
        return queued.removeFirst();
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
    private static volatile MethodHandle peekConsoleInputW;
    private static volatile MethodHandle readConsoleInputW;
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
                FunctionDescriptor consoleInput = FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS);
                peekConsoleInputW = linker.downcallHandle(k32.findOrThrow("PeekConsoleInputW"), consoleInput);
                readConsoleInputW = linker.downcallHandle(k32.findOrThrow("ReadConsoleInputW"), consoleInput);
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
