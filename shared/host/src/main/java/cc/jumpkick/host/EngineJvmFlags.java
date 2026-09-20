// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Path;
import java.util.List;

/**
 * The fixed flag set of the engine JVM, consumed by the spawn line ({@code EngineSpawn}) and by
 * the tests that start an engine-shaped JVM by hand, so the two never drift.
 */
public final class EngineJvmFlags {
    /**
     * How often HotSpot returns freed native memory to the OS ({@code malloc_trim}, glibc only; the
     * flag is accepted and inert elsewhere). Worker I/O and jar reading malloc through glibc, whose
     * arenas keep freed memory until trimmed; without this an idle engine sat at gigabytes of RSS
     * over a few hundred megabytes of live data.
     */
    public static final int TRIM_NATIVE_HEAP_INTERVAL_MS = 30_000;

    /**
     * The serving line's flags: SerialGC with tight heap-return ergonomics (an
     * idle coordinator must snap committed to ~live on its boundary GC), a periodic native-heap
     * trim for the same reason one level down, real IPv4 sockets for WSL
     * localhost forwarding, native access for PosixDetach's setsid(2) downcall, and a JVM that
     * dies on its first {@code OutOfMemoryError} after writing a heap dump. A capped coordinator
     * that survives an OOM is a silent peer every client has to displace; one that exits is
     * respawned on the next command with the dump to say why.
     */
    public static final List<String> BASE = List.of(
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
            "-XX:-ShrinkHeapInSteps",
            "-XX:TrimNativeHeapInterval=" + TRIM_NATIVE_HEAP_INTERVAL_MS,
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+HeapDumpOnOutOfMemoryError",
            PreferIpv4.JVM_FLAG,
            "--enable-native-access=ALL-UNNAMED");

    /**
     * Where {@code -XX:+HeapDumpOnOutOfMemoryError} writes. A directory: HotSpot then names each dump
     * {@code java_pid<pid>.hprof}, so every exit leaves its own file. Per engine home, so not in
     * the shared list.
     */
    public static String heapDumpPath(Path dump) {
        return "-XX:HeapDumpPath=" + dump;
    }

    private EngineJvmFlags() {}
}
