// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.SlowestTests;
import cc.jumpkick.run.TestClassResult;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@code jk test} run's slowest classes and methods as the terminal shows them above the result
 * line, and the listener that ranks them from the run's class-finish events. Empty when nothing
 * reached {@link SlowestTests#SLOW_MS}, so a quick suite prints nothing.
 */
public final class SlowestTestsBlock {

    private SlowestTestsBlock() {}

    /** A listener that folds every finished class into {@code into}. */
    public static BuildPlanListener ranking(SlowestTests into) {
        return new BuildPlanListener() {
            @Override
            public void testClass(String step, TestClassResult result) {
                into.add(result);
            }
        };
    }

    /**
     * A standalone project's ranking: folds every finished class into {@code into} and, when the
     * plan finishes, emits the {@code slowest-tests} event — to stdout under {@code -O jsonl}, to
     * the transcript always — and prints the block under {@code --verbose}, whose result line
     * follows. A live console prints the block itself, from {@link ConsoleSpec#above}; compose
     * this listener ahead of the console so the event precedes {@code plan-finish}.
     */
    public static BuildPlanListener standalone(SlowestTests into, BuildPlanConsole.Mode mode) {
        return new BuildPlanListener() {
            @Override
            public void testClass(String step, TestClassResult result) {
                into.add(result);
            }

            @Override
            public void planFinish(BuildPlanResult result) {
                SlowestTests.Ranking ranking = into.ranking();
                if (ranking.isEmpty()) return;
                JsonlShape.emitJsonl(JsonlShape.slowest(ranking), mode == BuildPlanConsole.Mode.JSON);
                if (mode == BuildPlanConsole.Mode.VERBOSE) {
                    for (String line : lines(ranking)) CliOutput.out(line);
                }
            }
        };
    }

    /**
     * The block, one line per row:
     *
     * <pre>
     * Slowest tests
     *   classes   12.3s  EngineE2eTest           g:engine · fast
     *   methods    4.2s  EngineE2eTest#boots     g:engine · fast
     * </pre>
     */
    public static List<String> lines(SlowestTests.Ranking ranking) {
        if (ranking.isEmpty()) return List.of();
        List<String[]> rows = new ArrayList<>();
        add(rows, "classes", ranking.classes());
        add(rows, "methods", ranking.methods());
        int[] width = new int[3];
        for (String[] r : rows) {
            for (int i = 0; i < width.length; i++) width[i] = Math.max(width[i], r[i].length());
        }
        Theme theme = Theme.active();
        List<String> out = new ArrayList<>();
        out.add(Theme.paint("Slowest tests", theme.focused()));
        for (String[] r : rows) {
            out.add("  "
                    + (r[0].isEmpty() ? pad("", width[0]) : Theme.paint(pad(r[0], width[0]), theme.dim()))
                    + "  "
                    + " ".repeat(width[1] - r[1].length())
                    + r[1]
                    + "  "
                    + pad(r[2], width[2])
                    + "  "
                    + Theme.paint(r[3], theme.dim()));
        }
        return out;
    }

    private static void add(List<String[]> rows, String kind, List<SlowestTests.Row> ranked) {
        boolean first = true;
        for (SlowestTests.Row r : ranked) {
            String test = simpleName(r.className()) + (r.method().isEmpty() ? "" : "#" + r.method());
            String where = r.tier().isEmpty() ? r.module() : r.module() + " · " + r.tier();
            rows.add(new String[] {first ? kind : "", SlowestTests.duration(r.millis()), test, where});
            first = false;
        }
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(width - s.length());
    }
}
