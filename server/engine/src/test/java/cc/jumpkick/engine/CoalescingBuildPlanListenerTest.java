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
    void output_burst_within_one_window_delivers_every_line_in_order() {
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
            // A synchronous burst (test-failure stack, native-image log) inside one cadence
            // window must arrive complete — latest-wins here silently ate failure reports
            // (JK-1833).
            c.output("run-tests", "FooTest.bar FAILED");
            c.output("run-tests", "  at FooTest.bar(FooTest.java:42)");
            c.output("compile", "warning: deprecated");
            assertThat(events).isEmpty();
            c.stepFinish("run-tests", "test", cc.jumpkick.run.TaskStatus.FAIL, Duration.ofMillis(10));
            assertThat(events)
                    .containsExactly(
                            "o:run-tests:FooTest.bar FAILED",
                            "o:run-tests:  at FooTest.bar(FooTest.java:42)",
                            "o:compile:warning: deprecated",
                            "finish:run-tests");
        }
    }

    @Test
    void output_overflow_drops_oldest_and_announces_the_gap() {
        List<String> events = new ArrayList<>();
        BuildPlanListener sink = new BuildPlanListener() {
            @Override
            public void output(String step, String line) {
                events.add(line);
            }
        };
        int cap = CoalescingBuildPlanListener.MAX_PENDING_OUTPUT_LINES;
        try (CoalescingBuildPlanListener c = new CoalescingBuildPlanListener(sink, 60_000L)) {
            for (int i = 0; i < cap + 2; i++) c.output("firehose", "line-" + i);
            c.flush();
        }
        assertThat(events).hasSize(cap + 1);
        assertThat(events.get(0)).isEqualTo("[jk: 2 earlier output lines dropped]");
        assertThat(events.get(1)).isEqualTo("line-2");
        assertThat(events.get(events.size() - 1)).isEqualTo("line-" + (cap + 1));
    }
}
