// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CoalescingPipelineListenerTest {

    @Test
    void coalesces_progress_and_labels_until_flush() {
        List<String> events = new ArrayList<>();
        PipelineListener sink = new PipelineListener() {
            @Override
            public void progress(String step, int delta, PipelineView view) {
                events.add("p:" + step + ":" + delta + ":" + view.numerator());
            }

            @Override
            public void label(String step, String label) {
                events.add("l:" + step + ":" + label);
            }

            @Override
            public void stepStart(String step, cc.jumpkick.plugin.build.Phase phase, int ticks) {
                events.add("start:" + step);
            }
        };
        // Huge cadence so schedule never fires before we flush via stepStart.
        try (CoalescingPipelineListener c = new CoalescingPipelineListener(sink, 60_000L)) {
            PipelineView v1 = new PipelineView("p", 1, 100, 1, 0, false);
            PipelineView v2 = new PipelineView("p", 5, 100, 1, 0, false);
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
        PipelineListener sink = new PipelineListener() {
            @Override
            public void progress(String step, int delta, PipelineView view) {
                events.add("p:" + delta);
            }
        };
        try (CoalescingPipelineListener c = new CoalescingPipelineListener(sink, 0L)) {
            c.progress("s", 1, new PipelineView("p", 1, 10, 1, 0, false));
            c.progress("s", 1, new PipelineView("p", 2, 10, 1, 0, false));
            assertThat(events).containsExactly("p:1", "p:1");
        }
    }

    @Test
    void pipeline_finish_flushes() {
        List<String> events = new ArrayList<>();
        PipelineListener sink = new PipelineListener() {
            @Override
            public void label(String step, String label) {
                events.add("l:" + label);
            }

            @Override
            public void pipelineFinish(PipelineResult result) {
                events.add("done");
            }
        };
        CoalescingPipelineListener c = new CoalescingPipelineListener(sink, 60_000L);
        c.label("s", "hello");
        c.pipelineFinish(new PipelineResult("p", true, Duration.ZERO, List.of(), List.of(), List.of(), false, false));
        assertThat(events).containsExactly("l:hello", "done");
    }
}
