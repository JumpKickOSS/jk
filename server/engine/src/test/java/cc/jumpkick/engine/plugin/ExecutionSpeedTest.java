// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ExecutionSpeedTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_started_child_is_opted_out_of_execution_speed_throttling() throws Throwable {
        Process child = new ProcessBuilder("cmd", "/c", "ping -n 30 127.0.0.1 >NUL").start();
        try {
            assertEquals("control=0 state=0", Kernel32.state(child.pid()), "Windows decides until told otherwise");
            ExecutionSpeed.child(child);
            assertEquals("control=1 state=0", Kernel32.state(child.pid()));
        } finally {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void the_engine_opts_itself_out() throws Throwable {
        ExecutionSpeed.self();
        assertEquals("control=1 state=0", Kernel32.state(ProcessHandle.current().pid()));
    }

    @Test
    void an_exited_child_is_left_alone() throws Exception {
        Process done = new ProcessBuilder("java", "-version").start();
        done.waitFor(30, TimeUnit.SECONDS);
        ExecutionSpeed.child(done);
    }

    /** {@code GetProcessInformation(ProcessPowerThrottling)}, read back as the two masks. */
    private static final class Kernel32 {
        private static final SymbolLookup K = SymbolLookup.libraryLookup("kernel32", Arena.global());
        private static final Linker L = Linker.nativeLinker();
        private static final MethodHandle OPEN = L.downcallHandle(
                K.find("OpenProcess").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        private static final MethodHandle GET = L.downcallHandle(
                K.find("GetProcessInformation").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
        private static final MethodHandle CLOSE = L.downcallHandle(
                K.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        static String state(long pid) throws Throwable {
            MemorySegment handle = (MemorySegment) OPEN.invokeExact(0x1000, 0, (int) pid);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment s = arena.allocate(12, 4);
                s.set(ValueLayout.JAVA_INT, 0, 1);
                assertEquals(1, (int) GET.invokeExact(handle, 4, s, 12), "GetProcessInformation");
                return "control=" + s.get(ValueLayout.JAVA_INT, 4) + " state=" + s.get(ValueLayout.JAVA_INT, 8);
            } finally {
                int unused = (int) CLOSE.invokeExact(handle);
            }
        }
    }
}
