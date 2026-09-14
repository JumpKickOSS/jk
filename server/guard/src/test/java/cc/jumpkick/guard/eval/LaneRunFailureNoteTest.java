// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A scanner failure names the frame that threw, so a one-off is diagnosable from the report alone. */
class LaneRunFailureNoteTest {

    @Test
    void a_failure_thrown_in_jk_code_names_that_frame() {
        Throwable t = thrown();
        String note = LaneRun.failureNote(t);
        assertThat(note)
                .startsWith("IllegalStateException: the corpus is short at LaneRunFailureNoteTest.thrown(")
                .contains("LaneRunFailureNoteTest.java:")
                .doesNotContain(" from ");
    }

    @Test
    void a_failure_thrown_in_the_jdk_names_the_jdk_frame_and_the_jk_frame_that_called_it() {
        Throwable t;
        try {
            getSix(List.of());
            throw new AssertionError("unreachable");
        } catch (IndexOutOfBoundsException e) {
            t = e;
        }
        String note = LaneRun.failureNote(t);
        assertThat(note)
                .startsWith("ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 0 at ")
                .doesNotStartWith(
                        "ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 0 at LaneRunFailureNoteTest")
                .contains(" from LaneRunFailureNoteTest.getSix(LaneRunFailureNoteTest.java:");
    }

    @Test
    void a_failure_thrown_by_an_array_access_in_jk_code_names_that_frame_once() {
        Throwable t;
        try {
            indexSix(new String[0]);
            throw new AssertionError("unreachable");
        } catch (ArrayIndexOutOfBoundsException e) {
            t = e;
        }
        assertThat(LaneRun.failureNote(t))
                .startsWith(
                        "ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 0 at LaneRunFailureNoteTest.indexSix(")
                .doesNotContain(" from ");
    }

    @Test
    void a_failure_without_frames_is_the_exception_alone() {
        Throwable t = new RuntimeException("bare");
        t.setStackTrace(new StackTraceElement[0]);
        assertThat(LaneRun.failureNote(t)).isEqualTo("RuntimeException: bare");
    }

    private static Throwable thrown() {
        return new IllegalStateException("the corpus is short");
    }

    private static String getSix(List<String> parts) {
        return parts.get(6);
    }

    private static String indexSix(String[] parts) {
        return parts[6];
    }
}
