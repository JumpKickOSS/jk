// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Gives a resident engine's memory back to the operating system once it has finished a job.
 *
 * <p>Two things hold an idle engine's RSS above its live data. The Java heap: SerialGC with a low
 * {@code MaxHeapFreeRatio} uncommits after a full collection, so the idle boundary asks for one.
 * The native heap: worker I/O, jar reading and the JIT allocate through the C allocator, and glibc
 * keeps what they free in per-thread arenas until something calls {@code malloc_trim}; that is
 * {@link #trimNative}, the same operation as {@code jcmd <pid> System.trim_native_heap}. Both run
 * at the boundary and again after {@link #SETTLE} of idleness, once the harvest thread and the
 * client disconnects that trail a job have finished allocating.
 */
final class HeapTrim {

    /** How long the engine is idle before the settled trim runs. */
    static final Duration SETTLE = Duration.ofSeconds(30);

    private static final String DIAGNOSTIC_COMMANDS = "com.sun.management:type=DiagnosticCommand";

    /** Arms the settled trim; a later arming supersedes an earlier one that has not fired. */
    private static final AtomicLong GENERATION = new AtomicLong();

    private HeapTrim() {}

    /**
     * Return freed native memory to the OS. The JVM's answer, e.g. {@code Trim native heap:
     * RSS+Swap: 1883M->1139M (-744M)}; a JVM without the command (not HotSpot, not glibc)
     * answers with why, and one whose management beans are unreachable answers {@code
     * unavailable}.
     */
    static String trimNative() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object out = server.invoke(
                    new ObjectName(DIAGNOSTIC_COMMANDS),
                    "systemTrimNativeHeap",
                    new Object[] {new String[0]},
                    new String[] {String[].class.getName()});
            return out == null ? "unavailable" : out.toString().strip();
        } catch (JMException | RuntimeException e) {
            Log.debug("trimNative: diagnostic command unavailable", e);
            return "unavailable";
        }
    }

    /**
     * Run {@code trim} after {@code settle} if {@code idle} still holds then. Re-arming before the
     * settle elapses supersedes the earlier arming, so a burst of back-to-back jobs pays one trim,
     * after the last of them.
     */
    static void later(BooleanSupplier idle, Runnable trim, Duration settle) {
        long mine = GENERATION.incrementAndGet();
        // Engine-lifetime chore: it reads no request's session.
        SessionContext.startVirtual("jk-idle-trim", () -> {
            try {
                Thread.sleep(settle);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (GENERATION.get() != mine || !idle.getAsBoolean()) return;
            try {
                trim.run();
            } catch (RuntimeException e) {
                Log.debug("later: idle trim failed", e);
            }
        });
    }
}
