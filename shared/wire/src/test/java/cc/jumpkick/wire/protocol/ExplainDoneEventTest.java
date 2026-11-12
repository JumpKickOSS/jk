// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExplainDoneEventTest {

    @Test
    void the_critical_path_rides_explain_done_and_an_empty_one_stays_off_the_wire() {
        String line = ProtoReads.explainDone(3, 12, List.of("/ws/core", "/ws/engine"));
        ExplainDoneEvent back = ExplainDoneEvent.decode(line);
        assertThat(back.maxReadyWidth()).isEqualTo(3);
        assertThat(back.moduleCount()).isEqualTo(12);
        assertThat(back.criticalPath()).containsExactly("/ws/core", "/ws/engine");

        String bare = ProtoReads.explainDone(1, 1);
        assertThat(bare).doesNotContain("criticalPath");
        assertThat(ExplainDoneEvent.decode(bare).criticalPath()).isEmpty();
    }
}
