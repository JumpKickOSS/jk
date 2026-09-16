// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaptureBufferTest {

    @Test
    void keeps_the_first_and_the_last_lines_and_counts_the_middle() {
        CaptureBuffer buffer = new CaptureBuffer();
        int total = CaptureBuffer.HEAD_LINES + CaptureBuffer.TAIL_LINES + 1_000;
        for (int i = 1; i <= total; i++) buffer.add("line " + i);

        String text = buffer.text();
        assertThat(text).startsWith("line 1\nline 2\n");
        assertThat(text).contains("\nline " + CaptureBuffer.HEAD_LINES + "\n… 1000 lines elided …\n");
        assertThat(text).endsWith("line " + (total - 1) + "\nline " + total);
        assertThat(text.split("\n")).hasSize(CaptureBuffer.HEAD_LINES + CaptureBuffer.TAIL_LINES + 1);
    }

    @Test
    void a_short_capture_is_verbatim() {
        CaptureBuffer buffer = new CaptureBuffer();
        assertThat(buffer.isEmpty()).isTrue();
        buffer.add("jk-test-runner: boom");
        buffer.add(null);
        buffer.add("\tat a.B.c(B.java:1)");
        assertThat(buffer.isEmpty()).isFalse();
        assertThat(buffer.text()).isEqualTo("jk-test-runner: boom\n\tat a.B.c(B.java:1)");
    }
}
