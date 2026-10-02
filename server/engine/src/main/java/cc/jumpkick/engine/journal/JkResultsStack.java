// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.test.FailureClip;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The stack clip for {@code jk-results.md}: a failed test's trace cut to {@link
 * JkResultsMarkdown#MAX_STACK_LINES}, keeping the frame inside the test class.
 *
 * <p>A framework-heavy test (Spring's MockMvc, a JSON-path matcher, AssertJ over a nested
 * describer) throws from twenty or more library frames deep, so a straight head cut ends before
 * the trace reaches the test's own method — and with it the {@code File.java:NN} an agent needs to
 * open. The clip keeps the head as it is, elides the middle with a count, and then writes the
 * frame the test called (the assertion) and the test's own frame, so that file and line survive
 * however deep the library stack runs. A trace whose test frame already sits inside the cut, or
 * that names no frame of the test class, is cut at the line count alone.
 */
@NullMarked
final class JkResultsStack {

    private JkResultsStack() {}

    /**
     * {@code stack} cut to about {@code maxLines} lines with the first frame of {@code testClass}
     * (or, failing that, of its package) kept along with the frame above it.
     */
    static String clip(String stack, @Nullable String testClass, int maxLines) {
        if (stack == null || stack.isEmpty()) return "";
        String[] lines = stack.trim().split("\n", -1);
        if (lines.length <= maxLines) return stack.trim();
        int test = FailureClip.testFrame(lines, testClass);
        // Inside the head cut already — or absent — so the plain cut keeps it.
        if (test < 0 || test < maxLines) return JkResultsMarkdown.clipLines(stack, maxLines);
        int head = Math.max(1, maxLines - 3);
        int assertion = test - 1;
        List<String> out = new ArrayList<>(maxLines + 1);
        for (int i = 0; i < head; i++) out.add(lines[i]);
        int elided = assertion - head;
        if (elided > 0) out.add("… " + elided + (elided == 1 ? " frame" : " frames"));
        if (assertion >= head) out.add(lines[assertion]);
        out.add(lines[test]);
        int rest = lines.length - 1 - test;
        if (rest > 0) out.add("… " + rest + (rest == 1 ? " frame" : " frames"));
        return String.join("\n", out);
    }
}
