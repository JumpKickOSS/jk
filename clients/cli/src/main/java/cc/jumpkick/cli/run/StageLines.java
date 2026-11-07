// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One line per finished stage of a command plan whose steps are its stages ({@code jk import}):
 * {@code ✓ <stage> · <its last detail> · took <time>}. The live view keeps them below its tree; without
 * one, {@link #printing} prints each as its stage ends.
 */
public final class StageLines {

    private StageLines() {}

    /** The line for a stage that ended {@code ok} after {@code took}, last saying {@code detail}. */
    public static String line(String stage, @Nullable String detail, Duration took, boolean ok) {
        Theme t = Theme.active();
        String mark = ok ? Theme.colorize(Glyphs.check(), t.success()) : Theme.colorize(Glyphs.cross(), t.error());
        String body = detail == null || detail.isBlank() ? stage : stage + " · " + detail;
        return mark + " " + body + " " + ConsoleSpec.took(took);
    }

    /** The display name of {@code step} among {@code steps}: its label, else its name. */
    static String display(List<Task> steps, String step) {
        for (Task t : steps) {
            if (t.name().equals(step)) return t.label() != null && !t.label().isEmpty() ? t.label() : t.name();
        }
        return step;
    }

    /** A listener printing each stage's line to {@code out} as the stage ends. */
    public static BuildPlanListener printing(PrintStream out, List<Task> steps) {
        Map<String, String> details = new ConcurrentHashMap<>();
        return new BuildPlanListener() {
            @Override
            public void label(String step, String label) {
                if (label != null && !label.isBlank()) details.put(step, label);
            }

            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                boolean ok = status == TaskStatus.SUCCESS || status == TaskStatus.SKIPPED;
                out.println(line(display(steps, step), details.get(step), duration, ok));
                out.flush();
            }
        };
    }
}
