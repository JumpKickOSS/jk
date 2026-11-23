// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestClassResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A class whose outcome was replayed says so on its class-finish line, with when that outcome was recorded. */
class ClassFinishReplayedTest {

    @Test
    void a_replayed_class_carries_when_it_ran_and_reads_back_as_replayed() {
        TestClassResult replayed =
                new TestClassResult("g:a", "demo.A", 3, 0, 1, 40, "fast", List.of(), false, 1_763_250_000_000L);
        String line = ProtoEvents.classFinish("a/b", "run-tests", replayed);
        assertThat(line).contains("\"replayedFrom\":1763250000000");
        TestClassResult back = ClassFinishEvent.decode(line).result();
        assertThat(back).isEqualTo(replayed);
        assertThat(back.replayed()).isTrue();
    }

    @Test
    void a_class_that_ran_has_no_replayed_field() {
        TestClassResult ran = new TestClassResult("g:a", "demo.A", 3, 0, 1, 40, "fast", List.of());
        String line = ProtoEvents.classFinish("a/b", "run-tests", ran);
        assertThat(line).doesNotContain("replayedFrom");
        assertThat(ClassFinishEvent.decode(line).result().replayed()).isFalse();
    }
}
