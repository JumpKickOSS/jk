// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CoalescingBuildPlanListenerTest {

    @Test
    void coalesces_progress_and_labels_until_flush() {
        List<String> events = new ArrayList<>();
        BuildPlanListener sink = new BuildPlanListener() {
            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                events.add("p:" + step + ":" + delta + ":" + view.numerator());
            }

            @Override
            public void label(String step, String label) {
                events.add("l:" + step + ":" + label);
            }

            @Override
            public void stepStart(String step, String group, int ticks) {
                events.add("start:" + step);
            }
        };
        // Huge cadence so schedule never fires before we flush via stepStart.
        try (CoalescingBuildPlanListener c = new CoalescingBuildPlanListener(sink, 60_000L)) {
            BuildPlanView v1 = new BuildPlanView("p", 1, 100, 1, 0, false);
            BuildPlanView v2 = new BuildPlanView("p", 5, 100, 1, 0, false);
            c.progress("resolve-deps", 1, v1);
            c.progress("resolve-deps", 4, v2);
            c.label("resolve-deps", "Resolving foo:1");
            c.label("resolve-deps", "Resolving bar:2");
            assertThat(events).isEmpty();
            c.stepStart("write-lockfile", null, 1);
            assertThat(events)
                    .containsExactly("p:resolve-deps:5:5", "l:resolve-deps:Resolving bar:2", "start:write-lockfile");
        }
    }

    @Test
    void zero_cadence_is_passthrough() {
        List<String> events = new CopyOnWriteArrayList<>();
        BuildPlanListener sink = new BuildPlanListener() {
            @Override
            public void progress(String step, int delta, BuildPlanView view) {
                events.add("p:" + delta);
            }
        };
        try (CoalescingBuildPlanListener c = new CoalescingBuildPlanListener(sink, 0L)) {
            c.progress("s", 1, new BuildPlanView("p", 1, 10, 1, 0, false));
            c.progress("s", 1, new BuildPlanView("p", 2, 10, 1, 0, false));
            assertThat(events).containsExactly("p:1", "p:1");
        }
    }

    @Test
    void pipeline_finish_flushes() {
        List<String> events = new ArrayList<>();
        BuildPlanListener sink = new BuildPlanListener() {
            @Override
            public void label(String step, String label) {
                events.add("l:" + label);
            }

            @Override
            public void planFinish(BuildPlanResult result) {
                events.add("done");
            }
        };
        CoalescingBuildPlanListener c = new CoalescingBuildPlanListener(sink, 60_000L);
        c.label("s", "hello");
        c.planFinish(new BuildPlanResult("p", true, Duration.ZERO, List.of(), List.of(), List.of(), false, false));
        assertThat(events).containsExactly("l:hello", "done");
    }

    @Test
    void coalesces_output_to_latest_line_until_flush() {
        List<String> events = new ArrayList<>();
        BuildPlanListener sink = new BuildPlanListener() {
            @Override
            public void output(String step, String line) {
                events.add("o:" + step + ":" + line);
            }

            @Override
            public void stepFinish(String step, String group, cc.jumpkick.run.TaskStatus status, Duration duration) {
                events.add("finish:" + step);
            }
        };
        try (CoalescingBuildPlanListener c = new CoalescingBuildPlanListener(sink, 60_000L)) {
            c.output("compile", "line-1");
            c.output("compile", "line-2");
            c.output("compile", "line-3");
            assertThat(events).isEmpty();
            c.stepFinish("compile", "compile", cc.jumpkick.run.TaskStatus.SUCCESS, Duration.ofMillis(10));
            assertThat(events).containsExactly("o:compile:line-3", "finish:compile");
        }
    }
}
