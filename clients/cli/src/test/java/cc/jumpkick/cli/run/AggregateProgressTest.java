// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.runtime.WorkspaceProgressTracker;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * CLI is a dumb consumer of engine {@link WorkspaceProgressTracker.Snapshot} (JK-1121). Aggregate
 * math lives in {@link WorkspaceProgressTrackerTest}.
 */
class AggregateProgressTest {

    @Test
    void apply_snapshot_paints_bar_and_live_progress() {
        LiveProgress.get().clear();
        CommandManager cm = CommandManager.pipeline(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        AggregateContext agg = new AggregateContext(cm);
        var snap = new WorkspaceProgressTracker.Snapshot(150, 200, 75.0, "execute", 1, 2);
        agg.applySnapshot(snap);
        assertThat(cm.numerator()).isEqualTo(150);
        assertThat(cm.denominator()).isEqualTo(200);
        assertThat(LiveProgress.get().percent()).isEqualTo(75.0);
    }

    @Test
    void preflight_only_sets_labels_not_percent() {
        LiveProgress.get().clear();
        CommandManager cm = CommandManager.pipeline(new PrintStream(new ByteArrayOutputStream()), "Build", false);
        AggregateContext agg = new AggregateContext(cm);
        agg.preflight("plan", 0, 10, "Preparing…");
        // No snapshot yet → LiveProgress stays unset; bar den may still be 0.
        assertThat(LiveProgress.get().percent()).isNull();
    }
}
