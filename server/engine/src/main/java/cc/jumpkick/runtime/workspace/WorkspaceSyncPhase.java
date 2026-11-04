// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.wire.runtime.TailArgs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * The SYNC phase of every build: {@link SyncPlans#syncBuildPlan}, the plan {@code jk sync} runs,
 * once for the workspace before any module plans. Every locked artifact, plugin, tool and JDK is on
 * disk when it returns, so a module's {@code resolve-deps} only derives its classpaths.
 */
final class WorkspaceSyncPhase {

    private WorkspaceSyncPhase() {}

    /** What the sync settled, or the errors that failed it ({@code errors} empty on success). */
    record Synced(List<String> errors, WorkspaceResult.SyncCounts counts) {
        boolean failed() {
            return !errors.isEmpty();
        }
    }

    /** Everything the lock pins put on disk, or the errors that kept it from being. */
    static Synced run(
            WorkspaceRequest request, WorkspaceBuildListener listener, Clock clock, Set<String> landedBefore) {
        Path owner = LockPaths.lockOwnerDir(request.entryDir());
        listener.onPreflight("sync", 0, 0, SyncStageProgress.LABEL);
        long started = clock.nanos();
        // `jk sync` is this phase as a goal of its own; its arguments reach the same plan.
        TailArgs.Sync args = request.spec() == null ? null : request.spec().syncArgs();
        AtomicInteger fetched = new AtomicInteger();
        AtomicInteger upToDate = new AtomicInteger();
        BuildPlan plan = SyncPlans.syncBuildPlan(
                owner,
                request.cache(),
                request.jdksDir(),
                args == null ? null : args.repoUrl(),
                args != null && args.sources(),
                fetched,
                upToDate,
                null,
                true);
        long perf = Perf.start();
        BuildPlanResult result;
        // The sync stage moves with the files it settles, so a cold build's header advances through
        // its downloads; stopped before the stage's last word below.
        try (SyncStageProgress ignored = SyncStageProgress.start(listener)) {
            result = plan.run();
        }
        Perf.end("ws-sync", perf, "success", result.success());
        for (BuildPlanResult.Diagnostic w : result.warnings()) {
            if (w.message() != null) listener.onNote(w.message());
        }
        if (result.success()) {
            WorkspaceResult.SyncCounts counts = runCounts(
                    fetched.get(), upToDate.get(), plan.get(SyncPlans.LOCKFILE).orElse(null), landedBefore);
            listener.onPreflight("sync", 1, 1, summary(counts.fetched(), counts.upToDate()));
            return new Synced(List.of(), counts);
        }
        List<String> errors = result.errors().stream()
                .map(d -> d.message() == null ? d.step() + " failed" : d.message())
                .toList();
        if (errors.isEmpty()) errors = List.of("dependency sync failed");
        listener.onPreflightFailed("sync", (clock.nanos() - started) / 1_000_000L, String.join("\n", errors));
        return new Synced(errors, new WorkspaceResult.SyncCounts(fetched.get(), upToDate.get()));
    }

    /**
     * What the run settled of the lock's own artifacts: {@code fetched} counts each row the run
     * downloaded, the ones a first lock pinned (and so downloaded) included; {@code upToDate} the
     * rest of what the sync found on disk. A toolchain's or worker's jars the same run staged are
     * not the lock's and are not counted. {@code landedBefore} is the board's artifact labels when
     * the run's preflight began, so only this run's downloads count.
     */
    static WorkspaceResult.SyncCounts runCounts(
            int syncFetched, int syncUpToDate, @Nullable Lockfile lock, Set<String> landedBefore) {
        int lockLanded = 0;
        if (lock != null) {
            Set<String> landed = SessionContext.current().io().transfers().artifactLabelsLanded();
            for (Lockfile.Artifact a : lock.artifacts()) {
                String label = a.displayCoord();
                if (landed.contains(label) && !landedBefore.contains(label)) lockLanded++;
            }
        }
        int fetched = Math.max(syncFetched, lockLanded);
        int upToDate = Math.max(0, syncFetched + syncUpToDate - fetched);
        return new WorkspaceResult.SyncCounts(fetched, upToDate);
    }

    /**
     * The sync stage's last word: {@code 12 fetched, 171 up-to-date}, or {@code already up to date}.
     * {@code jk sync} ends on it, and a build's sync stage says the same.
     */
    static String summary(int fetched, int upToDate) {
        return fetched == 0 && upToDate == 0 ? "already up to date" : fetched + " fetched, " + upToDate + " up-to-date";
    }
}
