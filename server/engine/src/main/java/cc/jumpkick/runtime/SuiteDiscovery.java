// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The run-tests step's half of {@link SuiteReads}: start the recording before the suite, and after
 * it decide which key its green marker may be stored under.
 */
final class SuiteDiscovery {

    /** A file system whose clock ticks in whole seconds can stamp a write in the run before it began. */
    private static final long COARSE_TICK_MILLIS = 1_000;

    private SuiteDiscovery() {}

    /** The run-tests stamp key as it stands now, folding in the discovered inputs kept so far. */
    @FunctionalInterface
    interface Rekey {
        @Nullable
        String key() throws Exception;
    }

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
        String settle(TaskContext ctx, @Nullable String stampKey, Rekey rekey) throws Exception {
            return SuiteDiscovery.settle(ctx, this, stampKey, rekey);
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

    /**
     * The key the run's green marker is stored under, or null when none may be. A run that read
     * nothing new keeps {@code stampKey}. A run that read new inputs keeps them, and its marker is
     * stored under the key that folds them in when they held still while it ran and the rest of the
     * key did too — then the next build replays it instead of running the suite again. A suite that
     * reached past this host is never stored.
     */
    static @Nullable String settle(TaskContext ctx, Run run, @Nullable String stampKey, Rekey rekey) throws Exception {
        Path moduleDir = run.moduleDir();
        Path target = run.layout().moduleTargetDir();
        SuiteReads.Observed seen = SuiteReads.collect(SuiteReads.recordingDir(target), moduleDir);
        if (!seen.complete()) return null;
        List<Path> roots = new ArrayList<>(run.keyed());
        roots.add(target);
        // Every module's test sandbox, this one's and those of the projects its tests make.
        roots.add(TestHomes.root());
        Path workspace = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        Set<Path> read = SuiteReads.undeclared(seen, roots, workspace, run.startedMillis());
        Set<String> started = SuiteReads.undeclaredCommands(seen, roots, workspace, run.startedMillis());
        boolean reached = seen.network() && !run.networkAllowed();
        SuiteReads.Discovered before = SuiteReads.load(moduleDir, run.selection());
        if (before.covers(read, started, reached)) {
            return reached ? null : stampKey;
        }
        SuiteReads.Discovered after = before.with(read, started, reached);
        if (reached) {
            SuiteReads.save(moduleDir, run.selection(), after);
            if (!before.network()) {
                ctx.warn(
                        "suite-network",
                        "tests talked to another host, so a pass cannot be replayed and they run on every"
                                + " build; set [test] network = \"allowed\" to replay them anyway");
            }
            return null;
        }
        Set<Path> fresh = new TreeSet<>(read);
        fresh.removeAll(before.files());
        Set<String> freshCommands = new TreeSet<>(started);
        freshCommands.removeAll(before.commands());
        // The key over everything found so far, then the key over what the stamp already held: the
        // second equal to the one taken before the run proves those inputs, their listings included,
        // did not move up to now. A new input is proved by its own stamp: last written before the
        // run began, so what the first key hashed of it is what the suite read.
        SuiteReads.save(moduleDir, run.selection(), after);
        String key = stampKey == null ? null : rekey.key();
        String readsAfter = SuiteReads.digest(moduleDir, run.selection(), workspace);
        SuiteReads.save(moduleDir, run.selection(), before);
        boolean held = stampKey != null && key != null && stampKey.equals(rekey.key());
        String readsBefore = SuiteReads.digest(moduleDir, run.selection(), workspace);
        SuiteReads.save(moduleDir, run.selection(), after);
        if (held && !settledBefore(fresh, freshCommands, listed(before.files()), run.startedMillis())) held = false;
        if (held) SettledReads.record(moduleDir, readsBefore, readsAfter);
        Log.debug("suite reads: " + moduleDir + " keys " + fresh.size() + " more file(s) and " + freshCommands.size()
                + " more program(s)" + (held ? "" : "; they moved during the run, so it is not replayed"));
        return held ? key : null;
    }

    /** The directories whose listings the stamp keys for {@code files}. */
    private static Set<Path> listed(Set<Path> files) {
        Set<Path> dirs = new TreeSet<>();
        for (Path f : files) {
            Path parent = f.getParent();
            if (parent != null) dirs.add(parent);
        }
        return dirs;
    }

    /**
     * Whether every file, the directory listing beside it (unless {@code proven} holds the directory)
     * and every program named by path was last written before the run started: what the suite read
     * of them is what they hold now.
     */
    static boolean settledBefore(Set<Path> files, Set<String> commands, Set<Path> proven, long startedMillis) {
        Set<Path> paths = new TreeSet<>(files);
        for (Path dir : listed(files)) if (!proven.contains(dir)) paths.add(dir);
        for (String c : commands) {
            String program = c.strip().split(" ", 2)[0];
            try {
                Path p = Path.of(program);
                if (p.isAbsolute()) paths.add(p);
            } catch (InvalidPathException bareName) {
                // Keyed by what the PATH resolves it to.
            }
        }
        for (Path p : paths) {
            try {
                StatIdentity id = StatIdentity.of(p);
                long touched = Math.max(id.mtimeMillis(), Math.floorDiv(id.ctimeNanos(), 1_000_000L));
                if (touched >= startedMillis - slack(id)) return false;
            } catch (IOException gone) {
                return false;
            }
        }
        return true;
    }

    /** A tick of slack where the file system stamps whole seconds, none where it stamps finer. */
    private static long slack(StatIdentity id) {
        boolean coarse = id.mtimeNanos() % 1_000_000_000L == 0
                && (id.ctimeNanos() == 0 || id.ctimeNanos() % 1_000_000_000L == 0);
        return coarse ? COARSE_TICK_MILLIS : 0;
    }
}
