// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
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

    /** Ready the module's recording and return the flag every suite JVM starts with. */
    static String recordFlag(BuildLayout layout) throws IOException {
        return SuiteReads.prepare(SuiteReads.recordingDir(layout.moduleTargetDir()));
    }

    /**
     * {@code stampKey} when the run read, started and reached nothing beyond the inputs its key was
     * taken from; null otherwise, after keeping what it found so the next key covers it. {@code
     * keyed} are the roots the stamp already hashes; the module's outputs and sandbox are added here.
     */
    static @Nullable String settle(
            TaskContext ctx,
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Collection<Path> keyed,
            @Nullable String stampKey) {
        Path target = layout.moduleTargetDir();
        SuiteReads.Observed seen = SuiteReads.collect(SuiteReads.recordingDir(target), moduleDir);
        List<Path> roots = new ArrayList<>(keyed);
        roots.add(target);
        roots.add(TestHomes.slotFor(moduleDir));
        Set<Path> read = SuiteReads.undeclared(seen, roots);
        SuiteReads.Discovered before = SuiteReads.load(moduleDir, selection);
        if (before.covers(read, seen.commands(), seen.network())) {
            return seen.network() ? null : stampKey;
        }
        SuiteReads.save(moduleDir, selection, before.with(read, seen.commands(), seen.network()));
        List<String> named = new ArrayList<>();
        for (Path p : read) {
            if (!before.files().contains(p)) named.add(String.valueOf(moduleDir.relativize(p)));
            if (named.size() == 3) break;
        }
        for (String c : seen.commands()) if (!before.commands().contains(c) && named.size() < 3) named.add(c);
        if (seen.network() && !before.network()) named.add("the network");
        ctx.warn(
                "undeclared-test-inputs",
                TaskNames.RUN_TESTS + " read inputs its key did not cover (" + String.join(", ", named)
                        + (seen.network() ? "" : "); they key the suite from the next build on")
                        + (seen.network() ? "); a suite that reaches past this host runs on every build" : ""));
        return null;
    }
}
