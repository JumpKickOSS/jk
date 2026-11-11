// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Held command output keeps its tail: the last bytes written, from a whole line, under a note. */
class TailBufferTest {

    private static String text(TailBuffer buffer) {
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void output_under_the_cap_is_kept_whole() throws Exception {
        TailBuffer buffer = new TailBuffer(64);
        buffer.write("one\ntwo\n".getBytes(StandardCharsets.UTF_8));

        assertThat(text(buffer)).isEqualTo("one\ntwo\n");
        assertThat(buffer.dropped()).isZero();
    }

    @Test
    void past_the_cap_the_head_goes_and_the_tail_starts_at_a_line() throws Exception {
        TailBuffer buffer = new TailBuffer(16);
        for (int i = 0; i < 10; i++) buffer.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8));

        String kept = text(buffer);

        assertThat(buffer.dropped()).isPositive();
        assertThat(kept).startsWith("(").contains("earlier bytes of output dropped)\n");
        assertThat(kept).endsWith("line 8\nline 9\n");
        assertThat(kept).doesNotContain("line 0");
    }

    @Test
    void one_write_larger_than_the_cap_keeps_its_own_tail() throws Exception {
        TailBuffer buffer = new TailBuffer(8);
        buffer.write("aaaaaaaaaaaa\nbbbbbb\n".getBytes(StandardCharsets.UTF_8));

        assertThat(text(buffer)).endsWith("bbbbbb\n").doesNotContain("aaa");
    }

    @Test
    void a_held_stream_keeps_eight_mebibytes() throws Exception {
        assertThat(TailBuffer.CAP).isEqualTo(8 * 1024 * 1024);
    }
}
