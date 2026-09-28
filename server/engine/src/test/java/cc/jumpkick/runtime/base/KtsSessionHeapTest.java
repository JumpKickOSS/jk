// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.HeapScope;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.LearnedHeaps;
import cc.jumpkick.engine.plugin.WorkerLeases;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The script host's heap is jk-planned: 256 MiB until a peak is learned. */
class KtsSessionHeapTest {

    @Test
    void the_first_heap_is_256_mib_and_a_peak_replaces_it(@TempDir Path dir) {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        HeapScope.Key key = new HeapScope.Key(dir, KtsSession.HEAP_MODULE, HeapScope.KTS_HOST, 25);
        String first = KtsSession.plannedHeapFlag(heaps, key);
        assertThat(first).isEqualTo("-Xmx256m");
        List<String> command = List.of();
        try {
            command = KtsSession.withPlannedHeap(List.of("/usr/bin/java", "-cp", "host.jar", "Main"), first);
            JvmOptions.HeapChoice choice = JvmOptions.HeapChoice.inspect(command);
            assertThat(choice.userPinned()).isFalse();
            assertThat(choice.xmxBytes()).isEqualTo(KtsSession.FIRST_HEAP_BYTES);
            assertThat(command).contains("-XX:+ExitOnOutOfMemoryError");
            assertThat(WorkerLeases.jvmLease(choice.xmxBytes())).isEqualTo((256L + 160L) << 20);

            heaps.note(dir, KtsSession.HEAP_MODULE, HeapScope.KTS_HOST, 25, 400L << 20);
            String learned = KtsSession.plannedHeapFlag(heaps, key);
            assertThat(learned).isNotEqualTo(first);
            long chosen = heaps.choose(key.project(), key.module(), key.kind(), key.jdk(), KtsSession.FIRST_HEAP_BYTES);
            assertThat(learned).isEqualTo("-Xmx" + (chosen / (1024 * 1024)) + "m");
            assertThat(chosen).isGreaterThan(KtsSession.FIRST_HEAP_BYTES);
        } finally {
            JvmOptions.forgetPlannedHeapForTests(first);
            if (command.size() > 1) JvmOptions.forgetPlannedHeapForTests(command.get(1));
        }
    }
}
