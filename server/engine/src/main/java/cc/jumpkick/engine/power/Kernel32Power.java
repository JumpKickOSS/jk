// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

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
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * kernel32's power calls through FFM: {@code GetSystemPowerStatus}, {@code PowerCreateRequest},
 * {@code PowerSetRequest}, {@code PowerClearRequest} and {@code CloseHandle}. Bound on first use;
 * on another OS, or a JVM without native access, every call answers "cannot". The engine is never a
 * native image, so these downcalls need no reachability metadata.
 */
final class Kernel32Power implements WindowsPower.Native {

    /** {@code SYSTEM_POWER_STATUS}: four bytes then two DWORDs, 12 bytes. */
    static final StructLayout STATUS = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("ACLineStatus"),
            ValueLayout.JAVA_BYTE.withName("BatteryFlag"),
            ValueLayout.JAVA_BYTE.withName("BatteryLifePercent"),
            ValueLayout.JAVA_BYTE.withName("SystemStatusFlag"),
            ValueLayout.JAVA_INT.withName("BatteryLifeTime"),
            ValueLayout.JAVA_INT.withName("BatteryFullLifeTime"));

    /**
     * {@code REASON_CONTEXT} on 64-bit Windows, 32 bytes: {@code Version}, {@code Flags}, then a
     * union whose larger arm (the detailed reason: a module, two ULONGs and a string array) is 24
     * bytes; the simple reason string sits at its start.
     */
    static final StructLayout REASON = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("Version"),
            ValueLayout.JAVA_INT.withName("Flags"),
            ValueLayout.ADDRESS.withName("SimpleReasonString"),
            MemoryLayout.paddingLayout(16));

    private static final int POWER_REQUEST_CONTEXT_VERSION = 0;
    private static final int POWER_REQUEST_CONTEXT_SIMPLE_STRING = 0x1;
    private static final int POWER_REQUEST_SYSTEM_REQUIRED = 1;

    private record Calls(
            MethodHandle getSystemPowerStatus,
            MethodHandle powerCreateRequest,
            MethodHandle powerSetRequest,
            MethodHandle powerClearRequest,
            MethodHandle closeHandle) {}

    private static final class Holder {
        static final @Nullable Calls CALLS = bind();
    }

    @SuppressWarnings("restricted")
    private static @Nullable Calls bind() {
        if (!Os.isWindows() || !Kernel32Power.class.getModule().isNativeAccessEnabled()) return null;
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            return new Calls(
                    linker.downcallHandle(
                            kernel32.findOrThrow("GetSystemPowerStatus"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("PowerCreateRequest"),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("PowerSetRequest"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("PowerClearRequest"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(
                            kernel32.findOrThrow("CloseHandle"),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
        } catch (Throwable unbound) {
            return null;
        }
    }

    @Override
    public int @Nullable [] powerStatus() {
        Calls c = Holder.CALLS;
        if (c == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(STATUS);
            int ok = (int) c.getSystemPowerStatus().invokeExact(status);
            if (ok == 0) return null;
            return new int[] {
                Byte.toUnsignedInt(status.get(ValueLayout.JAVA_BYTE, 0)),
                Byte.toUnsignedInt(status.get(ValueLayout.JAVA_BYTE, 1))
            };
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public PowerHost.@Nullable Hold systemRequired(String why) {
        Calls c = Holder.CALLS;
        if (c == null) return null;
        Arena arena = Arena.ofShared();
        try {
            MemorySegment reason = arena.allocate(REASON);
            reason.set(ValueLayout.JAVA_INT, 0, POWER_REQUEST_CONTEXT_VERSION);
            reason.set(ValueLayout.JAVA_INT, 4, POWER_REQUEST_CONTEXT_SIMPLE_STRING);
            reason.set(ValueLayout.ADDRESS, 8, arena.allocateFrom(why, StandardCharsets.UTF_16LE));
            MemorySegment request = (MemorySegment) c.powerCreateRequest().invokeExact(reason);
            // INVALID_HANDLE_VALUE is -1.
            if (request.address() == 0L || request.address() == -1L) {
                arena.close();
                return null;
            }
            int set = (int) c.powerSetRequest().invokeExact(request, POWER_REQUEST_SYSTEM_REQUIRED);
            if (set == 0) {
                int closed = (int) c.closeHandle().invokeExact(request);
                arena.close();
                return null;
            }
            return new PowerHost.Hold() {
                private boolean open = true;

                @Override
                public synchronized boolean alive() {
                    return open;
                }

                @Override
                public synchronized void close() {
                    if (!open) return;
                    open = false;
                    try {
                        int cleared = (int) c.powerClearRequest().invokeExact(request, POWER_REQUEST_SYSTEM_REQUIRED);
                        int closed = (int) c.closeHandle().invokeExact(request);
                    } catch (Throwable ignored) {
                        // The request dies with the process in any case.
                    } finally {
                        arena.close();
                    }
                }
            };
        } catch (Throwable t) {
            arena.close();
            return null;
        }
    }
}
