// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The window of this process's Windows console: {@code GetConsoleWindow}, its class name and its
 * visibility. Windows only; each call throws where kernel32 or user32 is absent.
 */
final class WindowsConsoleWindow {

    private static final int CLASS_CHARS = 256;

    private WindowsConsoleWindow() {}

    /** The console window's class name, or {@code null} when the console has no window. */
    static @Nullable String className() throws Throwable {
        MemorySegment hwnd = window();
        if (hwnd.address() == 0L) return null;
        MethodHandle getClassName = Linker.nativeLinker()
                .downcallHandle(
                        user32().findOrThrow("GetClassNameW"),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(ValueLayout.JAVA_CHAR, CLASS_CHARS);
            int n = (int) getClassName.invokeExact(hwnd, buf, CLASS_CHARS);
            if (n <= 0) return "";
            return new String(buf.asSlice(0, n * 2L).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
        }
    }

    /** Whether the console window is visible; {@code false} when the console has none. */
    static boolean visible() throws Throwable {
        MemorySegment hwnd = window();
        if (hwnd.address() == 0L) return false;
        MethodHandle isVisible = Linker.nativeLinker()
                .downcallHandle(
                        user32().findOrThrow("IsWindowVisible"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        return (int) isVisible.invokeExact(hwnd) != 0;
    }

    private static MemorySegment window() throws Throwable {
        MethodHandle getConsoleWindow = Linker.nativeLinker()
                .downcallHandle(
                        SymbolLookup.libraryLookup("kernel32", Arena.global()).findOrThrow("GetConsoleWindow"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
        return (MemorySegment) getConsoleWindow.invokeExact();
    }

    private static SymbolLookup user32() {
        return SymbolLookup.libraryLookup("user32", Arena.global());
    }
}
