// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The run-tests step's half of {@link SuiteReads}: start the recording before the suite, and after
 * it decide whether its green marker may be stored.
 */
final class SuiteDiscovery {

    private SuiteDiscovery() {}

    /**
     * One run's recording: the flag every suite JVM starts with, when the run began, and what the
     * stamp already keys for it.
     */
    record Run(
            String flag,
            long startedMillis,
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Collection<Path> keyed,
            boolean networkAllowed) {

        /** As {@link SuiteDiscovery#settle}, for this run. */
        @Nullable
        String settle(TaskContext ctx, @Nullable String stampKey) {
            return SuiteDiscovery.settle(ctx, this, stampKey);
        }
    }

    /** Ready the module's recording for a run that starts now. */
    static Run start(
            BuildLayout layout, Path moduleDir, TestSelection selection, Collection<Path> keyed, boolean networkAllowed)
            throws IOException {
        long now = Clock.SYSTEM.millis();
        String flag = SuiteReads.prepare(SuiteReads.recordingDir(layout.moduleTargetDir()));
        return new Run(flag, now, moduleDir, layout, selection, List.copyOf(keyed), networkAllowed);
    }

    static @Nullable String settle(TaskContext ctx, Run run, @Nullable String stampKey) {
        Path moduleDir = run.moduleDir();
        BuildLayout layout = run.layout();
        TestSelection selection = run.selection();
        boolean networkAllowed = run.networkAllowed();
        Path target = layout.moduleTargetDir();
        SuiteReads.Observed seen = SuiteReads.collect(SuiteReads.recordingDir(target), moduleDir);
        if (!seen.complete()) return null;
        List<Path> roots = new ArrayList<>(run.keyed());
        roots.add(target);
        // Every module's test sandbox, this one's and those of the projects its tests make.
        roots.add(TestHomes.root());
        Path workspace = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        Set<Path> read = SuiteReads.undeclared(seen, roots, workspace, run.startedMillis());
        Set<String> started = SuiteReads.undeclaredCommands(seen, roots, workspace, run.startedMillis());
        boolean reached = seen.network() && !networkAllowed;
        SuiteReads.Discovered before = SuiteReads.load(moduleDir, selection);
        if (before.covers(read, started, reached)) {
            return reached ? null : stampKey;
        }
        SuiteReads.save(moduleDir, selection, before.with(read, started, reached));
        List<String> named = new ArrayList<>();
        for (Path p : read) {
            if (!before.files().contains(p)) named.add(String.valueOf(moduleDir.relativize(p)));
            if (named.size() == 3) break;
        }
        for (String c : started) if (!before.commands().contains(c) && named.size() < 3) named.add(c);
        if (reached && !before.network()) named.add("the network");
        ctx.warn(
                "undeclared-test-inputs",
                TaskNames.RUN_TESTS + " read inputs its key did not cover (" + String.join(", ", named)
                        + (reached
                                ? "); a suite that reaches past this host runs on every build unless [test] network"
                                        + " = \"allowed\""
                                : "); they key the suite from the next build on"));
        return null;
    }
}
