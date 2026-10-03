// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnsizedHeapTest {

    private static final long GIB = 1L << 30;
    private static final long RAM = MemoryProbe.current().totalBytes();

    @Test
    void the_ceiling_is_the_jvms_own_share_of_ram() {
        assertThat(UnsizedHeap.ramCeiling(List.of("java", "-version"))).isEqualTo(RAM / 4);
        assertThat(UnsizedHeap.ramCeiling(List.of("java", "-XX:MaxRAMPercentage=50", "-version")))
                .isEqualTo(RAM / 2);
        assertThat(UnsizedHeap.ramCeiling(List.of("javac", "-J-XX:MaxRAMPercentage=10", "-version")))
                .isEqualTo((long) (RAM * 0.10));
    }

    /** A JVM with no heap of its own is leased what it likely uses, never more than it could take. */
    @Test
    void an_unsized_jvm_is_leased_its_first_heap_capped_by_its_ceiling() {
        long budget = 8 * GIB;
        List<String> free = List.of("java", "-XX:MaxRAMPercentage=50", "-cp", "w.jar", "W");
        assertThat(UnsizedHeap.lease(free, budget))
                .isEqualTo(Math.min(LearnedHeaps.firstHeap(UnsizedHeap.LEAST_BYTES, budget), RAM / 2));
        List<String> tiny = List.of("java", "-XX:MaxRAMPercentage=0.5", "-cp", "w.jar", "W");
        assertThat(UnsizedHeap.lease(tiny, budget)).isEqualTo(Math.max(WorkerLeases.MIN_XMX, (long) (RAM * 0.005)));
    }

    @Test
    void a_learned_peak_sizes_the_lease(@TempDir Path dir) throws Exception {
        HeapScope.Key key = new HeapScope.Key(dir, "g:m", HeapScope.PLUGIN, 25);
        LearnedHeaps.engine().note(key, 300L << 20);
        try {
            long leased = HeapScope.call(
                    key, () -> UnsizedHeap.lease(List.of("java", "-XX:MaxRAMPercentage=50", "W"), 8 * GIB));
            assertThat(leased).isEqualTo(Math.min(LearnedHeaps.size(300L << 20, 8 * GIB), RAM / 2));
        } finally {
            Files.deleteIfExists(LearnedHeaps.engine().file(dir));
        }
    }

    /** A node build or test process is leased what a bundler holds, up to half the host. */
    @Test
    void a_node_process_is_leased_the_node_default() {
        long budget = 8 * GIB;
        assertThat(UnsizedHeap.nodeCommand(List.of("/opt/node/bin/node", "/opt/npm/bin/npm-cli.js", "ci")))
                .isTrue();
        assertThat(UnsizedHeap.nodeCommand(List.of("/opt/pnpm/pnpm.exe", "install")))
                .isTrue();
        assertThat(UnsizedHeap.nodeCommand(List.of("java", "-version"))).isFalse();
        assertThat(UnsizedHeap.lease(List.of("/opt/node/bin/node", "build.js"), budget))
                .isEqualTo(Math.min(LearnedHeaps.firstHeap(UnsizedHeap.NODE_BYTES, budget), RAM / 2));
    }
}
