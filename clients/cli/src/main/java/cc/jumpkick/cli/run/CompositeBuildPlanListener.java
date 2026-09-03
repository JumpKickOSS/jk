// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestFailureInfo;
import java.time.Duration;

/**
 * Fans every {@link BuildPlanListener} callback out to two delegates. Needed because an engine-hosted
 * module's {@code BuildPlan} is a client-side, never-{@code run()} reconstruction (see {@code
 * EngineEventDecoder}) — a listener attached via {@code plan.addListener(...)} is never
 * driven. The listener a caller <em>returns</em> from {@code onModuleStart}, by contrast, is driven
 * driven by both the in-process and engine-hosted paths alike, so composing extra listeners (e.g.
 * a {@link SessionMirrorListener}) into the returned listener is the one place that works either way.
 */
public final class CompositeBuildPlanListener implements BuildPlanListener {

    private final BuildPlanListener a;
    private final BuildPlanListener b;

    private CompositeBuildPlanListener(BuildPlanListener a, BuildPlanListener b) {
        this.a = a;
        this.b = b;
    }

    /**
     * Null-tolerant composition: either side may be {@code null} (e.g. no session transcript).
     * Both null yields a no-op listener — never a
     * composite that would dereference null on the first event.
     */
    public static BuildPlanListener of(BuildPlanListener first, BuildPlanListener second) {
        if (first == null) {
            return second == null ? new BuildPlanListener() {} : second;
        }
        return second == null ? first : new CompositeBuildPlanListener(first, second);
    }

    @Override
    public void planStart(BuildPlanView view) {
        a.planStart(view);
        b.planStart(view);
    }

    @Override
    public void stepStart(String step, String group, int ticks) {
        a.stepStart(step, group, ticks);
        b.stepStart(step, group, ticks);
    }

    @Override
    public void progress(String step, int delta, BuildPlanView view) {
        a.progress(step, delta, view);
        b.progress(step, delta, view);
    }

    @Override
    public void tickUpdate(String step, int delta, BuildPlanView view) {
        a.tickUpdate(step, delta, view);
        b.tickUpdate(step, delta, view);
    }

    @Override
    public void label(String step, String label) {
        a.label(step, label);
        b.label(step, label);
    }

    @Override
    public void output(String step, String line) {
        a.output(step, line);
        b.output(step, line);
    }

    @Override
    public void warn(String step, String code, String message) {
        a.warn(step, code, message);
        b.warn(step, code, message);
    }

    @Override
    public void error(String step, String code, String message) {
        a.error(step, code, message);
        b.error(step, code, message);
    }

    @Override
    public void error(String step, String code, String message, String test, String exceptionClass) {
        a.error(step, code, message, test, exceptionClass);
        b.error(step, code, message, test, exceptionClass);
    }

    @Override
    public void error(String step, String code, String message, TestFailureInfo failure) {
        a.error(step, code, message, failure);
        b.error(step, code, message, failure);
    }

    @Override
    public void stepFinish(String step, String group, TaskStatus status, Duration duration, Duration waited) {
        a.stepFinish(step, group, status, duration, waited);
        b.stepFinish(step, group, status, duration, waited);
    }

    @Override
    public void planFinish(BuildPlanResult result) {
        a.planFinish(result);
        b.planFinish(result);
    }
}
