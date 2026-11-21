// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A child's output is kept up to a budget, its end rather than its start. */
class BoundedRunTest {

    @Test
    void output_within_the_budget_is_kept_whole() {
        BoundedRun.Tail tail = new BoundedRun.Tail(100);
        add(tail, "one\ntwo\n");
        assertThat(tail.text()).isEqualTo("one\ntwo\n");
    }

    @Test
    void output_past_the_budget_keeps_its_last_whole_lines_and_says_so() {
        BoundedRun.Tail tail = new BoundedRun.Tail(64);
        for (int i = 0; i < 10_000; i++) add(tail, "line " + i + "\n");

        String text = tail.text();
        assertThat(text).startsWith("… earlier output not kept\nline ").endsWith("line 9999\n");
        assertThat(text.length()).isLessThanOrEqualTo(64 + "… earlier output not kept\n".length());
    }

    /** A child that never ends a line is bounded too: lines are not the unit of memory. */
    @Test
    void one_endless_line_is_bounded() {
        BoundedRun.Tail tail = new BoundedRun.Tail(64);
        for (int i = 0; i < 10_000; i++) add(tail, "x".repeat(100));

        assertThat(tail.text().length()).isLessThanOrEqualTo(64 + "… earlier output not kept\n".length());
    }

    private static void add(BoundedRun.Tail tail, String text) {
        tail.add(text.toCharArray(), text.length());
    }
}
