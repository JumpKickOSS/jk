// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records per-step complete events into a {@link ChromeTimeline}. Flushes on {@link
 * #pipelineFinish} when {@code flushOnFinish} is true (single-module runs). Workspace builds share
 * one timeline and flush once from the workspace listener.
 */
public final class ChromeTimelineListener implements PipelineListener {

    private final ChromeTimeline timeline;
    private final String module;
    private final boolean flushOnFinish;
    private final Map<String, Long> starts = new ConcurrentHashMap<>();

    public ChromeTimelineListener(ChromeTimeline timeline, String module, boolean flushOnFinish) {
        this.timeline = timeline;
        this.module = module == null ? "_" : module;
        this.flushOnFinish = flushOnFinish;
    }

    /** Single-module helper: open default path under {@code projectDir}, flush at end. */
    public static ChromeTimelineListener forProject(java.nio.file.Path projectDir, String module) {
        ChromeTimeline t = ChromeTimeline.open(projectDir);
        if (t == null) return null;
        String mod = module != null && !module.isBlank()
                ? module
                : projectDir.getFileName().toString();
        return new ChromeTimelineListener(t, mod, true);
    }

    /** Workspace helper: shared session, no per-module flush. */
    public static ChromeTimelineListener forModule(ChromeTimeline session, String module) {
        return new ChromeTimelineListener(session, module, false);
    }

    public ChromeTimeline timeline() {
        return timeline;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        starts.put(step, System.nanoTime());
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        long end = System.nanoTime();
        Long start = starts.remove(step);
        if (start == null) {
            // duration from the pipeline when start was missed
            long durNanos = duration != null ? duration.toNanos() : 0L;
            start = end - durNanos;
        }
        String st = status != null ? status.name() : "UNKNOWN";
        timeline.complete(module, step, st, start, end);
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (flushOnFinish) timeline.flush();
    }
}
