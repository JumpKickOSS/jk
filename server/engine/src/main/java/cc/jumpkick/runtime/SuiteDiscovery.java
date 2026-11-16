// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.InputGuard;
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

        /** Keep each class's record of this run ({@link ClassRecording}). */
        void recordClasses(
                TestClassLedger ledger,
                String moduleLabel,
                Path testClasses,
                Path mainClasses,
                List<Path> runtimeCp,
                @Nullable Path javaHome,
                List<String> stampExtras,
                List<Path> resourceDirs,
                @Nullable String stampKey) {
            ClassRecording.record(
                    moduleDir,
                    layout,
                    keyed,
                    ledger,
                    moduleLabel,
                    testClasses,
                    mainClasses,
                    runtimeCp,
                    javaHome,
                    stampExtras,
                    resourceDirs,
                    stampKey);
        }

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
     * nothing new keeps {@code stampKey} when the key, taken again, is still {@code stampKey}. A run
     * that read new inputs keeps them, and its marker is stored under the key that folds them in when
     * they held still while it ran and the rest of the key did too — then the next build replays it
     * instead of running the suite again. Any input that moved while the suite ran stores no marker
     * and warns {@link InputGuard#MOVED}: the suite read it at an unknown moment, so neither the key
     * before nor the key after names what it read. A suite that reached past this host is never stored.
     */
    static @Nullable String settle(TaskContext ctx, Run run, @Nullable String stampKey, Rekey rekey) throws Exception {
        Path moduleDir = run.moduleDir();
        Path target = run.layout().moduleTargetDir();
        SuiteReads.Observed seen = SuiteReads.collect(SuiteReads.recordingDir(target), moduleDir);
        if (!seen.complete()) {
            // Never silent: a suite that cannot replay runs on every build, so the result line, the
            // build's record, the forecast and doctor all say why.
            String why = seen.unread() == null ? "a recording could not be read" : seen.unread();
            ctx.warn(SuiteReads.UNREAD_WARNING, SuiteReads.unreadMessage(why));
            SuiteReads.noteUnread(moduleDir, why);
            return null;
        }
        SuiteReads.clearUnread(moduleDir);
        List<Path> roots = new ArrayList<>(run.keyed());
        roots.add(target);
        // Every module's test sandbox, this one's and those of the projects its tests make.
        roots.add(TestHomes.root());
        Path workspace = WorkspaceScan.findRoot(moduleDir).orElse(moduleDir);
        Set<Path> read = SuiteReads.undeclared(seen, roots, workspace, run.startedMillis());
        Set<Path> jkState = SuiteReads.under(read, JkDirs.state());
        if (!jkState.isEmpty()) {
            read.removeAll(jkState);
            ctx.warn(JK_STATE, jkStateWarning(jkState, JkDirs.state()));
        }
        Set<String> started = SuiteReads.undeclaredCommands(seen, roots, workspace, run.startedMillis());
        Set<String> hosts = run.networkAllowed() ? Set.of() : seen.hosts();
        boolean reached = !hosts.isEmpty();
        SuiteReads.Discovered before = SuiteReads.load(moduleDir, run.selection());
        // A whole run's reads are all the suite reads now: what it no longer reads drops out. A run
        // of only the affected classes adds to what the whole suite was found to read.
        SuiteReads.Discovered after =
                run.whole() ? SuiteReads.Discovered.NONE.with(read, started, hosts) : before.with(read, started, hosts);
        // Every run that stores no marker for it says so, not only the one that first found it.
        if (reached) {
            ctx.warn(SuiteReads.NETWORK_WARNING, networkWarning(hosts));
            SettledReads.unreplayable(moduleDir);
        }
        if (reached) {
            if (!after.equals(before)) SuiteReads.save(moduleDir, run.selection(), after);
            return null;
        }
        if (after.equals(before)) {
            if (stampKey == null) {
                Log.debug("suite reads: " + moduleDir + " read what it was keyed on; no key");
                return null;
            }
            // The key taken again equal to the one taken before the run proves every input it names,
            // the discovered files and their listings included, held still up to now.
            String now = rekey.key();
            if (stampKey.equals(now)) return stampKey;
            if (now != null) {
                warnMoved(
                        ctx,
                        moved(before.files(), before.commands(), Set.of(), workspace, run.startedMillis()),
                        workspace);
            }
            Log.debug("suite reads: " + moduleDir + " not replayed, its key moved during the run");
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
        Path moved = null;
        if (held) {
            moved = moved(fresh, freshCommands, listed(before.files(), workspace), workspace, run.startedMillis());
            if (moved != null) {
                held = false;
                warnMoved(ctx, moved, workspace);
            }
        } else if (key != null) {
            moved = moved(before.files(), before.commands(), Set.of(), workspace, run.startedMillis());
            warnMoved(ctx, moved, workspace);
        }
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

    /**
     * The warning for a run whose marker is not stored because its tests reached {@code hosts}, the
     * hosts past this one they read from or wrote to.
     */
    static String networkWarning(Set<String> hosts) {
        String first = hosts.iterator().next();
        String more = hosts.size() > 1 ? " (+" + (hosts.size() - 1) + ")" : "";
        return "tests reached " + first + more + ", so they rerun; [test] network = \"allowed\" caches them";
    }

    /**
     * The warning for a suite that read jk's own state directory (engine endpoints, pids, logs)
     * outside its sandbox. That state moves with every engine generation, so it is never keyed:
     * keying it would rerun the suite after every engine restart and prove nothing.
     */
    static String jkStateWarning(Set<Path> read, Path state) {
        Path first = read.iterator().next();
        Path root = real(state);
        Path p = real(first);
        String shown = p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString();
        String more = read.size() > 1 ? " (+" + (read.size() - 1) + ")" : "";
        return "tests read jk's own state, not keyed: " + shown + more;
    }

    /** The warning code of {@link #jkStateWarning}. */
    static final String JK_STATE = "suite-jk-state";

    private static void warnMoved(TaskContext ctx, @Nullable Path moved, Path workspace) {
        ctx.warn(InputGuard.MOVED, movedWarning(moved, workspace));
    }

    /**
     * The warning for a green run whose marker is not stored because {@code moved}, an input it was
     * found to read or an entry beside one, was written once it began; null when the key moved and
     * no input it was found to read carries a write since then.
     */
    static String movedWarning(@Nullable Path moved, Path workspace) {
        if (moved == null)
            return "an input of " + TaskNames.RUN_TESTS + " changed while it ran; the next build runs it again";
        Path p = real(moved);
        Path root = real(workspace);
        String shown = p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : p.toString();
        return "an input of " + TaskNames.RUN_TESTS + " changed while it ran: " + shown
                + "; the next build runs it again";
    }

    /** {@code p} with its links resolved (macOS's /var is /private/var), or as given once gone. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException gone) {
            return p.toAbsolutePath().normalize();
        }
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
            try {
                // A recorded command is its program alone (SuiteReads.executable), spaces and all.
                Path p = Path.of(c);
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
                Path born = SuiteReads.bornSince(dir, startedMillis);
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
