// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Own-heap exhaustion, a cgroup kill, and everything else are one classification. */
class WorkerFateTest {

    @Test
    void exit_3_and_the_heap_banners_are_an_exhausted_heap() {
        assertThat(WorkerFate.classify(3, "", false)).isEqualTo(WorkerFate.Cause.HEAP_EXHAUSTED);
        assertThat(WorkerFate.classify(1, "java.lang.OutOfMemoryError: Java heap space", false))
                .isEqualTo(WorkerFate.Cause.HEAP_EXHAUSTED);
        assertThat(WorkerFate.classify(0, "java.lang.OutOfMemoryError: GC overhead limit exceeded", false))
                .isEqualTo(WorkerFate.Cause.HEAP_EXHAUSTED);
        assertThat(WorkerFate.mentionsHeap("Terminating due to java.lang.OutOfMemoryError: Java heap space"))
                .isTrue();
    }

    @Test
    void a_cgroup_kill_is_not_an_exhausted_heap_even_when_the_log_mentions_one() {
        assertThat(WorkerFate.classify(137, "java.lang.OutOfMemoryError: Java heap space", true))
                .isEqualTo(WorkerFate.Cause.KILLED_FOR_MEMORY);
        assertThat(WorkerFate.classify(137, "", true)).isEqualTo(WorkerFate.Cause.KILLED_FOR_MEMORY);
    }

    @Test
    void metaspace_and_a_normal_exit_are_other() {
        assertThat(WorkerFate.classify(1, "java.lang.OutOfMemoryError: Metaspace", false))
                .isEqualTo(WorkerFate.Cause.OTHER);
        assertThat(WorkerFate.classify(1, "java.lang.OutOfMemoryError: Direct buffer memory", false))
                .isEqualTo(WorkerFate.Cause.OTHER);
        assertThat(WorkerFate.classify(0, "BUILD SUCCESS", false)).isEqualTo(WorkerFate.Cause.OTHER);
        assertThat(WorkerFate.mentionsHeap(null)).isFalse();
    }
}
