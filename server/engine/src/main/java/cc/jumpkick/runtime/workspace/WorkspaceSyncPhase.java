// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SYNC phase of every build: {@link SyncPlans#syncBuildPlan}, the plan {@code jk sync} runs,
 * once for the workspace before any module plans. Every locked artifact, plugin, tool and JDK is on
 * disk when it returns, so a module's {@code resolve-deps} only derives its classpaths.
 */
final class WorkspaceSyncPhase {

    private WorkspaceSyncPhase() {}

    /** The errors that failed the sync, or empty when everything the lock pins is on disk. */
    static Optional<List<String>> run(WorkspaceRequest request, WorkspaceBuildListener listener, Clock clock) {
        Path owner = LockPaths.lockOwnerDir(request.entryDir());
        listener.onPreflight("sync", 0, 0, "Syncing dependencies…");
        long started = clock.nanos();
        BuildPlan plan = SyncPlans.syncBuildPlan(
                owner,
                request.cache(),
                request.jdksDir(),
                null,
                false,
                new AtomicInteger(),
                new AtomicInteger(),
                null,
                true,
                false);
        long perf = Perf.start();
        BuildPlanResult result = plan.run();
        Perf.end("ws-sync", perf, "success", result.success());
        for (BuildPlanResult.Diagnostic w : result.warnings()) {
            if (w.message() != null) listener.onNote(w.message());
        }
        if (result.success()) {
            listener.onPreflight("sync", 1, 1, "Dependencies synced");
            return Optional.empty();
        }
        List<String> errors = result.errors().stream()
                .map(d -> d.message() == null ? d.step() + " failed" : d.message())
                .toList();
        if (errors.isEmpty()) errors = List.of("dependency sync failed");
        listener.onPreflightFailed("sync", (clock.nanos() - started) / 1_000_000L, String.join("\n", errors));
        return Optional.of(errors);
    }
}
