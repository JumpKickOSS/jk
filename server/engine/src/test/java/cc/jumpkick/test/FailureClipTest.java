// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureClipTest {

    /**
     * The shape of a harness setup failure: an eight-kilobyte message naming the whole test
     * classpath, repeated in the stack's header and its cause. Clipped, one failure is a few
     * kilobytes, and the head and the innermost cause survive.
     */
    @Test
    void a_failure_repeating_a_long_message_in_its_stack_is_a_few_kilobytes() {
        String message = "jenkins-war-*.war was not in " + "/home/u/.m2/repository/x/y/z-1.0.jar:".repeat(220);
        StringBuilder stack =
                new StringBuilder("java.lang.AssertionError: ").append(message).append('\n');
        for (int i = 0; i < 120; i++)
            stack.append("\tat org.jvnet.hudson.test.Frame").append(i).append("(F.java:1)\n");
        stack.append("Caused by: java.lang.IllegalStateException: ")
                .append(message)
                .append('\n');
        stack.append("\tat org.jvnet.hudson.test.Root.run(Root.java:9)\n");

        String clippedMessage = FailureClip.message(message);
        String clippedStack = FailureClip.stack(stack.toString());

        assertThat(message.length()).isGreaterThan(8_000);
        assertThat(clippedMessage.length()).isLessThanOrEqualTo(FailureClip.MAX_MESSAGE_CHARS + 64);
        assertThat(clippedStack.length()).isLessThan(8_000);
        assertThat(clippedStack)
                .startsWith("java.lang.AssertionError: jenkins-war-*.war was not in")
                .contains(FailureClip.LINE_TRUNCATION_MARKER)
                .contains("\tat org.jvnet.hudson.test.Frame62(F.java:1)")
                .doesNotContain("Frame63(")
                .contains("\t… 57 lines")
                .contains("Caused by: java.lang.IllegalStateException: jenkins-war");
    }

    @Test
    void a_short_failure_is_kept_as_is() {
        String stack = "java.lang.AssertionError: nope\n\tat C.c(C.java:1)\n";
        assertThat(FailureClip.stack(stack)).isSameAs(stack);
        assertThat(FailureClip.message("nope")).isEqualTo("nope");
        assertThat(FailureClip.stack("")).isEmpty();
    }

    @Test
    void a_deep_recursion_is_cut_to_its_head() {
        String stack = "java.lang.StackOverflowError\n" + "\tat C.recurse(C.java:2)\n".repeat(20_000);
        String clipped = FailureClip.stack(stack);
        assertThat(clipped.length()).isLessThanOrEqualTo(FailureClip.MAX_STACK_CHARS);
        assertThat(clipped).startsWith("java.lang.StackOverflowError\n").endsWith(" lines");
    }

    @Test
    void a_long_message_is_cut_with_its_remainder_and_never_inside_a_surrogate_pair() {
        String message = "expected: <" + "x".repeat(3_000_000) + "> but was: <y>";
        String cut = FailureClip.message(message);
        assertThat(cut.length()).isLessThanOrEqualTo(FailureClip.MAX_MESSAGE_CHARS + 64);
        assertThat(cut).contains(JUnitLauncher.MESSAGE_TRUNCATION_MARKER).endsWith(" more chars)");

        String astral = "a".repeat(FailureClip.MAX_MESSAGE_CHARS - 1) + "😀tail";
        String cutAstral = FailureClip.message(astral);
        assertThat(cutAstral).doesNotContain("😀");
        assertThat(Character.isHighSurrogate(cutAstral.charAt(cutAstral.indexOf(" ... message truncated") - 1)))
                .isFalse();
    }

    /** The runner cut the message already; a second cut would replace its remainder count with the marker's length. */
    @Test
    void a_runner_capped_message_keeps_its_remainder_count() {
        String runnerCapped = "x".repeat(FailureClip.MAX_MESSAGE_CHARS)
                + JUnitLauncher.MESSAGE_TRUNCATION_MARKER
                + "3000000 more chars)";
        assertThat(FailureClip.message(runnerCapped)).isSameAs(runnerCapped);
        String quoting = "y".repeat(20_000) + JUnitLauncher.MESSAGE_TRUNCATION_MARKER + "12 more chars)";
        assertThat(FailureClip.message(quoting).length()).isLessThanOrEqualTo(FailureClip.MAX_MESSAGE_CHARS + 64);
    }

    /**
     * The live event keeps less than the reports: a short message, the head of the trace, the test's
     * own frame however deep the library stack runs, and every cause header, in a few kilobytes.
     */
    @Test
    void the_live_event_keeps_the_head_the_test_frame_and_the_causes_in_a_few_kilobytes() {
        String message = "jenkins-war-*.war was not in " + "/home/u/.m2/repository/x/y/z-1.0.jar:".repeat(220);
        StringBuilder stack =
                new StringBuilder("java.lang.AssertionError: ").append(message).append('\n');
        for (int i = 0; i < 60; i++)
            stack.append("\tat org.jvnet.hudson.test.Frame").append(i).append("(F.java:1)\n");
        stack.append("\tat org.junit.Assert.fail(Assert.java:89)\n");
        stack.append("\tat hudson.model.ComputerTest.dumpExportTable(ComputerTest.java:42)\n");
        for (int i = 0; i < 40; i++)
            stack.append("\tat org.junit.Runner").append(i).append("(R.java:1)\n");
        stack.append("Caused by: java.lang.IllegalStateException: ")
                .append(message)
                .append('\n');

        String eventMessage = FailureClip.eventMessage(message);
        String eventStack = FailureClip.eventStack(stack.toString(), "hudson.model.ComputerTest");

        assertThat(eventMessage.length()).isLessThanOrEqualTo(FailureClip.EVENT_MESSAGE_CHARS + 64);
        assertThat(eventMessage.length() + eventStack.length()).isLessThan(4_096);
        assertThat(eventStack)
                .startsWith("java.lang.AssertionError: jenkins-war")
                .contains("\tat org.jvnet.hudson.test.Frame22(F.java:1)")
                .doesNotContain("Frame23(")
                .contains("\tat org.junit.Assert.fail(Assert.java:89)")
                .contains("\tat hudson.model.ComputerTest.dumpExportTable(ComputerTest.java:42)")
                .contains("Caused by: java.lang.IllegalStateException: jenkins-war")
                .doesNotContain("Runner39");
        assertThat(FailureClip.eventStack("java.lang.AssertionError: no\n\tat a.B.c(B.java:1)", "a.B"))
                .isEqualTo("java.lang.AssertionError: no\n\tat a.B.c(B.java:1)");
    }
}
