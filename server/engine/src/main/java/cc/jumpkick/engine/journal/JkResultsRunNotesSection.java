// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.lock.JumpKickPins;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.test.SuiteReads;
import java.util.HashSet;
import java.util.Set;

/**
 * The run notes of the agent's results ({@link JkResultsAgent}): the lines that say why a run waited,
 * retried or will run a step again, shown on a green run too.
 */
final class JkResultsRunNotesSection {

    private JkResultsRunNotesSection() {}

    /**
     * A memory wait, a heap retry, a worker far over its lease, and a warning that says why the next
     * build runs a step again (a suite that reached another host or whose recording could not be read, an input written while it ran, a
     * JumpKick pin behind the running jk), one line each, including on a green run. A wait is once per module and step; a second note of the
     * same text on that step is dropped. A workspace names the module: {@code W jk-engine run-tests:
     * retried with …}.
     */
    static final Set<String> RUN_NOTES = Set.of(
            "heap-retry",
            "memory-wait",
            "memory-over-lease",
            SuiteReads.NETWORK_WARNING,
            SuiteReads.UNREAD_WARNING,
            InputGuard.MOVED);

    /** The run notes of {@code record}, one {@code W} line each, into {@code sb}. */
    static void append(StringBuilder sb, BuildRecord record) {
        Set<String> seen = new HashSet<>();
        for (BuildRecord.Diag d : record.diagnostics()) {
            if (d == null || d.message() == null || d.message().isBlank()) continue;
            String code = d.code();
            if (!RUN_NOTES.contains(code) && !JumpKickPins.BEHIND.equals(code)) continue;
            String line = d.message();
            int nl = line.indexOf('\n');
            if (nl >= 0) line = line.substring(0, nl);
            line = line.strip();
            if (line.isEmpty()) continue;
            String where = d.dir() == null ? "" : d.dir();
            String step = d.step() == null ? "" : d.step();
            String key = code + "\0" + where + "\0" + step;
            if (!"memory-wait".equals(code)) key = key + "\0" + line;
            if (!seen.add(key)) continue;
            String module = JkResultsMarkdown.warningModule(record, d);
            if (!module.isEmpty()) {
                sb.append("W ").append(module);
                if (!step.isBlank()) sb.append(' ').append(step.strip());
                sb.append(": ");
            }
            sb.append(line).append('\n');
        }
    }
}
