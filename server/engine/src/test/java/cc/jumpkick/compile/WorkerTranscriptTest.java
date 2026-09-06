// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkerTranscriptTest {

    @Test
    void keeps_the_head_and_the_tail_and_counts_what_fell_between() {
        WorkerTranscript t = new WorkerTranscript();
        for (int i = 0; i < 100; i++) t.record("line " + i);
        String text = t.render();
        assertThat(text).startsWith("line 0\nline 1\n");
        assertThat(text).contains("line 24\n... 35 lines elided ...\nline 60\n");
        assertThat(text).endsWith("line 99");
        assertThat(text).doesNotContain("line 59\n");
    }

    @Test
    void reset_makes_the_next_item_own_the_head() {
        // Without the reset, the head filled by the first item's JVM chatter would front every
        // later crash and the exception that actually ended the second item would be in the tail
        // or gone.
        WorkerTranscript t = new WorkerTranscript();
        for (int i = 0; i < 80; i++) t.record("startup chatter " + i);
        t.reset();
        assertThat(t.isEmpty()).isTrue();
        t.record("Exception in thread \"main\" java.lang.OutOfMemoryError");
        t.record("\tat scala.tools.nsc.Global.compile(Global.scala:1)");
        assertThat(t.render())
                .isEqualTo("Exception in thread \"main\" java.lang.OutOfMemoryError\n"
                        + "\tat scala.tools.nsc.Global.compile(Global.scala:1)");
    }

    @Test
    void empty_until_a_line_lands() {
        WorkerTranscript t = new WorkerTranscript();
        assertThat(t.isEmpty()).isTrue();
        t.record("x");
        assertThat(t.isEmpty()).isFalse();
        assertThat(t.render()).isEqualTo("x");
    }
}
