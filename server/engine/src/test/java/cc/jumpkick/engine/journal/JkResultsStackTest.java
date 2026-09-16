// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A clipped stack always keeps the frame inside the test class: the assertion's file and line
 * survive the cut however many framework frames sit above them.
 */
class JkResultsStackTest {

    private static final String TEST = "com.acme.web.UploadTest";
    private static final int MAX = JkResultsMarkdown.MAX_STACK_LINES;

    /** A 40-frame trace with the test's frame at position 30, under a 24-line cut. */
    private static String deepStack() {
        List<String> lines = new ArrayList<>();
        lines.add("java.lang.AssertionError: Expecting path /tmp/x to exist");
        for (int i = 1; i < 29; i++) {
            lines.add("\tat org.assertj.core.internal.Files.frame" + i + "(Files.java:" + (100 + i) + ")");
        }
        lines.add("\tat org.assertj.core.api.AbstractPathAssert.exists(AbstractPathAssert.java:1163)");
        lines.add("\tat com.acme.web.UploadTest.uploadsAFile(UploadTest.java:57)");
        for (int i = 31; i < 40; i++) {
            lines.add(
                    "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:"
                            + i + ")");
        }
        return String.join("\n", lines);
    }

    @Test
    void the_test_frame_past_the_cut_is_kept_with_the_assertion_frame_above_it() {
        String clipped = JkResultsStack.clip(deepStack(), TEST, MAX);

        assertThat(clipped).contains("at com.acme.web.UploadTest.uploadsAFile(UploadTest.java:57)");
        assertThat(clipped).contains("at org.assertj.core.api.AbstractPathAssert.exists(AbstractPathAssert.java:1163)");
        assertThat(clipped).startsWith("java.lang.AssertionError: Expecting path /tmp/x to exist\n");
        assertThat(clipped).contains("\n… 8 frames\n");
        assertThat(clipped).endsWith("\n… 9 frames");
        assertThat(clipped.split("\n")).hasSizeLessThanOrEqualTo(MAX + 1);
    }

    @Test
    void a_nested_class_of_the_test_counts_as_the_test_frame() {
        String stack =
                deepStack().replace("com.acme.web.UploadTest.uploadsAFile", "com.acme.web.UploadTest$Nested.run");

        assertThat(JkResultsStack.clip(stack, TEST, MAX)).contains("UploadTest$Nested.run(UploadTest.java:57)");
    }

    @Test
    void a_frame_in_the_tests_package_is_kept_when_the_class_itself_never_appears() {
        String stack = deepStack().replace("com.acme.web.UploadTest.uploadsAFile", "com.acme.web.Support.check");

        assertThat(JkResultsStack.clip(stack, TEST, MAX)).contains("com.acme.web.Support.check(UploadTest.java:57)");
    }

    @Test
    void a_short_stack_is_untouched_and_an_unknown_class_gets_the_plain_cut() {
        String shortStack = "boom\n\tat com.acme.web.UploadTest.a(UploadTest.java:1)";
        assertThat(JkResultsStack.clip(shortStack, TEST, MAX)).isEqualTo(shortStack);

        String plain = JkResultsStack.clip(deepStack(), null, MAX);
        assertThat(plain).isEqualTo(JkResultsMarkdown.clipLines(deepStack(), MAX));
        assertThat(plain).doesNotContain("UploadTest.java:57");
    }

    @Test
    void a_test_frame_inside_the_cut_leaves_the_plain_cut_alone() {
        String stack =
                "boom\n\tat org.junit.Assert.fail(Assert.java:9)\n\tat com.acme.web.UploadTest.a(UploadTest.java:1)\n"
                        + String.join("\n", Collections.nCopies(40, "\tat java.base/x.Y.z(Y.java:1)"));

        assertThat(JkResultsStack.clip(stack, TEST, MAX)).isEqualTo(JkResultsMarkdown.clipLines(stack, MAX));
    }

    @Test
    void frame_class_reads_the_declaring_class_and_ignores_message_lines() {
        assertThat(JkResultsStack.frameClass("\tat java.base/java.lang.Thread.run(Thread.java:1583)"))
                .isEqualTo("java.lang.Thread");
        assertThat(JkResultsStack.frameClass("at com.acme.web.UploadTest$Nested.run(UploadTest.java:5)"))
                .isEqualTo("com.acme.web.UploadTest$Nested");
        assertThat(JkResultsStack.frameClass("java.lang.AssertionError: at least"))
                .isNull();
        assertThat(JkResultsStack.frameClass("Caused by: java.io.IOException")).isNull();
    }
}
