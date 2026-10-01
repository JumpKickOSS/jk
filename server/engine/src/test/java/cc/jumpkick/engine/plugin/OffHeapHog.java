// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Touches {@code args[0]} MiB of native memory outside its small heap, prints {@code ready}, then
 * sleeps until killed. {@link WorkerLeasesOffHeapTest} forks it to stand for a worker whose
 * resident set outgrows its lease.
 */
public final class OffHeapHog {

    private static final long PAGE = 4096;

    private OffHeapHog() {}

    public static void main(String[] args) throws InterruptedException {
        long bytes = Long.parseLong(args[0]) << 20;
        MemorySegment memory = Arena.global().allocate(bytes);
        for (long at = 0; at < bytes; at += PAGE) memory.set(ValueLayout.JAVA_BYTE, at, (byte) 1);
        System.out.println("ready");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
