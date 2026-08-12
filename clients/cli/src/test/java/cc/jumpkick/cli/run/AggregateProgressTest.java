// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * CLI is a dumb consumer of engine {@link WorkspaceProgressTracker.Snapshot}. Aggregate
 * math lives in {@link WorkspaceProgressTrackerTest}.
 */
class AggregateProgressTest {

    @AfterEach
    void clearLiveProgress() {
        LiveProgress.get().clear();
    }

    @Test
    void apply_snapshot_paints_bar_and_live_progress() {
        LiveProgress.get().clear();
        JkManager cm = JkManager.plan(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        AggregateContext agg = new AggregateContext(cm);
        var snap = new WorkspaceProgressTracker.Snapshot(150, 200, 75.0, "execute", 1, 2, 25_000, 100_000);
        agg.applySnapshot(snap);
        assertThat(cm.numerator()).isEqualTo(150);
        assertThat(cm.denominator()).isEqualTo(200);
        assertThat(LiveProgress.get().percent()).isEqualTo(75.0);
    }

    @Test
    void workspace_member_console_listener_keeps_engine_rider() {
        LiveProgress.get().setPercent(70.0); // engine snapshot already applied
        var lis = new CommandManagerListener(
                new PrintStream(new ByteArrayOutputStream()),
                (ConsoleSpec) null,
                "g:a",
                java.util.List.of(),
                false,
                false);
        lis.planStart(new cc.jumpkick.run.BuildPlanView("build", 1, 10, 1, 0, false));
        lis.progress("compile", 1, new cc.jumpkick.run.BuildPlanView("build", 2, 10, 1, 0, false));
        lis.tickUpdate("compile", 1, new cc.jumpkick.run.BuildPlanView("build", 3, 10, 1, 0, false));
        assertThat(LiveProgress.get().percent()).isEqualTo(70.0);
        lis.planFinish(new cc.jumpkick.run.BuildPlanResult(
                "build",
                true,
                java.time.Duration.ZERO,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                false));
    }

    @Test
    void preflight_only_sets_labels_not_percent() {
        LiveProgress.get().clear();
        JkManager cm = JkManager.plan(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        AggregateContext agg = new AggregateContext(cm);
        agg.preflight("plan", 0, 10, "Preparing…");
        // No snapshot yet → LiveProgress stays unset; bar den may still be 0.
        assertThat(LiveProgress.get().percent()).isNull();
    }
}
