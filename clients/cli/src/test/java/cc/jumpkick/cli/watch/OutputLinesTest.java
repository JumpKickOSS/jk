// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A sidecar's output arrives as the rows a terminal would show, not as every repaint of them. */
class OutputLinesTest {

    private static List<String> lines(String raw) throws Exception {
        List<String> out = new ArrayList<>();
        OutputLines.read(new StringReader(raw), out::add);
        return out;
    }

    @Test
    void line_feeds_and_crlf_end_lines_and_the_tail_is_a_line_too() throws Exception {
        assertThat(lines("one\ntwo\r\nthree")).containsExactly("one", "two", "three");
    }

    @Test
    void a_carriage_return_progress_line_collapses_to_its_final_state() throws Exception {
        assertThat(lines("bundling 10%\rbundling 55%\rbundling 100%\ndone\n")).containsExactly("bundling 100%", "done");
    }

    @Test
    void a_repaint_still_in_flight_at_end_of_stream_reports_its_last_state() throws Exception {
        assertThat(lines("spin -\rspin \\\rspin |")).containsExactly("spin |");
    }

    @Test
    void empty_lines_survive_and_a_lone_trailing_return_adds_nothing() throws Exception {
        assertThat(lines("a\n\nb\r")).containsExactly("a", "", "b");
    }
}
