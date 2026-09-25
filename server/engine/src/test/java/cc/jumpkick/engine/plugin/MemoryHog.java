// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Allocates and writes every page until the kernel kills it. {@link WorkerContainmentTest} launches
 * it inside a cgroup with a small {@code memory.max}; it has no dependencies so that JVM stays
 * small enough to start.
 */
public final class MemoryHog {

    private static final int PAGE = 4096;

    /** Large enough that a few chunks cross a 64 MiB cap, small enough to fault promptly. */
    private static final int CHUNK = 32 * 1024 * 1024;

    /** Keeps the page stores live so the loop cannot be deleted. */
    private static volatile byte sink;

    private MemoryHog() {}

    public static void main(String[] args) {
        List<byte[]> held = new ArrayList<>();
        while (true) {
            byte[] block = new byte[CHUNK];
            for (int i = 0; i < block.length; i += PAGE) {
                block[i] = 1;
                sink = block[i];
            }
            held.add(block);
        }
    }
}
