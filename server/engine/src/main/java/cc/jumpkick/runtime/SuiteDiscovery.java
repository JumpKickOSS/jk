// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.test.SuiteReads;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
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
     * One run's recording: the flag every suite JVM starts with, when the run began, what the stamp
     * already keys for it, and whether it runs the whole suite (not only the classes an edit
     * affects), so that what it reads is all the suite reads.
     */
    record Run(
            String flag,
            long startedMillis,
            Path moduleDir,
            BuildLayout layout,
            TestSelection selection,
            Collection<Path> keyed,
            boolean networkAllowed,
            boolean whole) {

        /** As {@link SuiteDiscovery#settle}, for this run. */
        @Nullable
        String settle(TaskContext ctx, @Nullable String stampKey, Rekey rekey) throws Exception {
            return SuiteDiscovery.settle(ctx, this, stampKey, rekey);
        }
    }

    /** Ready the module's recording for a run that starts now. */
    static Run start(
            BuildLayout layout,
            Path moduleDir,
            TestSelection selection,
            Collection<Path> keyed,
            boolean networkAllowed,
            boolean whole)
            throws IOException {
        long now = Clock.SYSTEM.millis();
        String flag = SuiteReads.prepare(SuiteReads.recordingDir(layout.moduleTargetDir()));
        return new Run(flag, now, moduleDir, layout, selection, List.copyOf(keyed), networkAllowed, whole);
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
        if (!seen.complete()) {
            Log.debug("suite reads: " + moduleDir + " not replayed, a recording is incomplete");
            return null;
        }
        List<Path> roots = new ArrayList<>(run.keyed());
        roots.add(target);
        // Every module's test sandbox, this one's and those of the projects its tests make.
        roots.add(TestHomes.root());
        Path workspace = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        Set<Path> read = SuiteReads.undeclared(seen, roots, workspace, run.startedMillis());
        Set<String> started = SuiteReads.undeclaredCommands(seen, roots, workspace, run.startedMillis());
        boolean reached = seen.network() && !run.networkAllowed();
        SuiteReads.Discovered before = SuiteReads.load(moduleDir, run.selection());
        // A whole run's reads are all the suite reads now: what it no longer reads drops out. A run
        // of only the affected classes adds to what the whole suite was found to read.
        SuiteReads.Discovered after = run.whole()
                ? SuiteReads.Discovered.NONE.with(read, started, reached)
                : before.with(read, started, reached);
        if (after.equals(before)) {
            Log.debug("suite reads: " + moduleDir + " read what it was keyed on"
                    + (reached ? "; not replayed, it reached another host" : stampKey == null ? "; no key" : ""));
            return reached ? null : stampKey;
        }
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
        Path moved = held
                ? moved(fresh, freshCommands, listed(before.files(), workspace), workspace, run.startedMillis())
                : null;
        if (moved != null) held = false;
        if (held) SettledReads.record(moduleDir, readsBefore, readsAfter);
        Log.debug("suite reads: " + moduleDir + " keys " + fresh.size() + " more file(s) and " + freshCommands.size()
                + " more program(s)"
                + (held
                        ? ""
                        : moved != null
                                ? "; not replayed, written during the run: " + moved
                                : "; not replayed, the rest of its key moved during the run"));
        return held ? key : null;
    }

    /** The directories whose listings the stamp keys for {@code files}. */
    private static Set<Path> listed(Set<Path> files, Path workspace) {
        Set<Path> dirs = new TreeSet<>();
        for (Path f : files) {
            Path parent = f.getParent();
            if (parent != null && SuiteReads.keysListing(parent, workspace)) dirs.add(parent);
        }
        return dirs;
    }

    /**
     * Whether every file, the directory listing beside it (unless {@code proven} holds the directory)
     * and every program named by path held still since the run started: what the suite read of them
     * is what they hold now.
     */
    static boolean settledBefore(
            Set<Path> files, Set<String> commands, Set<Path> proven, Path workspace, long startedMillis) {
        return moved(files, commands, proven, workspace, startedMillis) == null;
    }

    /**
     * The first input written once the run began, or null when none was. A file is judged by its
     * modification time: the build hard-links outputs into the workspace, which moves a file's change
     * time and never its bytes. A listing is judged by its entries' birth: one made and removed during
     * the run is gone, and the listing the key hashes is the one the suite started with.
     */
    static @Nullable Path moved(
            Set<Path> files, Set<String> commands, Set<Path> proven, Path workspace, long startedMillis) {
        Set<Path> paths = new TreeSet<>(files);
        for (String c : commands) {
            String program = c.strip().split(" ", 2)[0];
            try {
                Path p = Path.of(program);
                if (p.isAbsolute()) paths.add(p);
            } catch (InvalidPathException bareName) {
                // Keyed by what the PATH resolves it to.
            }
        }
        try {
            for (Path p : paths) {
                FileTime modified = Files.getLastModifiedTime(p);
                if (modified.toMillis() >= startedMillis - slack(modified)) return p;
            }
            for (Path dir : listed(files, workspace)) {
                if (proven.contains(dir)) continue;
                Path born = SuiteReads.bornSince(dir, startedMillis - COARSE_TICK_MILLIS);
                if (born != null) return born;
            }
        } catch (IOException gone) {
            return Path.of(String.valueOf(gone.getMessage()));
        }
        return null;
    }

    /** A tick of slack where the file system stamps whole seconds, none where it stamps finer. */
    private static long slack(FileTime t) {
        return t.to(TimeUnit.NANOSECONDS) % 1_000_000_000L == 0 ? COARSE_TICK_MILLIS : 0;
    }
}
