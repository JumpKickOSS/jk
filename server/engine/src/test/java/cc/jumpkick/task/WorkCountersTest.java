// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import org.junit.jupiter.api.Test;

class WorkCountersTest {

    @Test
    void the_work_object_counts_restores_that_happened_and_compiled_sources() {
        String before = WorkCounters.statusJson();
        assertThat(WorkCounters.restored(true)).isTrue();
        assertThat(WorkCounters.restored(false)).isFalse();
        WorkCounters.compiled(3);
        String after = WorkCounters.statusJson();
        assertThat(Jsonl.longValue(after, "actionsRestored", -1) - Jsonl.longValue(before, "actionsRestored", -1))
                .isEqualTo(1);
        assertThat(Jsonl.longValue(after, "javaSourcesCompiled", -1)
                        - Jsonl.longValue(before, "javaSourcesCompiled", -1))
                .isEqualTo(3);
        assertThat(Jsonl.longValue(after, "filesFingerprinted", -1)).isNotNegative();
        assertThat(Jsonl.longValue(after, "memoHits", -1)).isNotNegative();
        assertThat(Jsonl.longValue(after, "filesHashed", -1)).isNotNegative();
    }
}
