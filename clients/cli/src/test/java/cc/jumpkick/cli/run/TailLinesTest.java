// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A module's held output lines keep their newest bytes, opening with how many lines went. */
class TailLinesTest {

    @Test
    void lines_under_the_cap_are_kept_as_written() {
        TailLines lines = new TailLines(1024);
        lines.add("a");
        lines.add("b");

        assertThat(lines).containsExactly("a", "b");
        assertThat(lines.dropped()).isZero();
    }

    @Test
    void past_the_cap_the_oldest_lines_go_and_a_note_counts_them() {
        TailLines lines = new TailLines(12);
        for (int i = 0; i < 6; i++) lines.add("line" + i); // 6 bytes each with its newline

        assertThat(lines.dropped()).isEqualTo(4);
        assertThat(List.copyOf(lines)).containsExactly("(4 earlier lines of output dropped)", "line4", "line5");
    }

    @Test
    void the_newest_line_is_kept_even_when_it_alone_is_over_the_cap() {
        TailLines lines = new TailLines(4);
        lines.add("x");
        lines.add("a much longer line");

        assertThat(List.copyOf(lines)).containsExactly("(1 earlier lines of output dropped)", "a much longer line");
    }

    @Test
    void many_drops_keep_the_list_consistent() {
        TailLines lines = new TailLines(100);
        for (int i = 0; i < 5_000; i++) lines.add("l" + i);

        assertThat(lines.getLast()).isEqualTo("l4999");
        assertThat(lines.size()).isLessThan(100);
        lines.clear();
        assertThat(lines).isEmpty();
    }

    @Test
    void a_held_module_keeps_eight_mebibytes() {
        assertThat(TailLines.CAP).isEqualTo(8L * 1024 * 1024);
    }
}
