// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.engine.CoalescingLockPackages;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.LockGate;
import cc.jumpkick.runtime.LockMode;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One lock/update plan that writes the single workspace (or standalone) {@code jk-lock.toml}.
 * Members redirect to the root. Serialized per lock dir.
 */
final class LockCascade {

    private LockCascade() {}

    /**
     * @param skipWhenFresh an invisible freshen only: report success without resolving when the
     *     lock is already current — a concurrent job won the flight while this one waited on the
     *     lock gate. An explicit {@code jk lock} always resolves and rewrites.
     */
    static JobOutcome run(
            VerbHost host,
            Path entryDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode,
            boolean skipWhenFresh,
            @Nullable BufferedWriter writer)
            throws Exception {
        Files.createDirectories(cache);
        Path lockDir;
        JkBuild effective;
        String coord;
        try {
            var scope = LockPlans.lockScope(entryDir);
            lockDir = scope.lockDir();
            effective = scope.effective();
            coord = scope.coord();
        } catch (RuntimeException e) {
            host.sendQuiet(writer, ProtoEvents.lockFinish(false, Exit.CONFIG, List.of(Errors.text(e)), -1));
            return JobOutcome.failed(Exit.CONFIG);
        }

        synchronized (LockGate.monitorFor(lockDir)) {
            // needsRefresh, not isStale: a missing lock is not "stale" (isStale is digest-only)
            // but an invisible freshen must still write one (jk tree / explain / status).
            if (skipWhenFresh && !LockFreshness.needsRefresh(lockDir)) {
                host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
                return JobOutcome.ok();
            }
            Path dir = lockDir;
            String dirTag = dir.toString();
            host.sendQuiet(writer, ProtoEvents.lockModule(dirTag, coord));

            CoalescingLockPackages lockPkgs = new CoalescingLockPackages(
                    (d, name, ver, total) -> host.sendQuiet(writer, ProtoEvents.lockPackage(d, name, ver, total)));
            ResolveObserver observer = new ResolveObserver() {
                @Override
                public void onTotal(int total) {}

                @Override
                public void onPackage(String module, String version) {
                    lockPkgs.onPackage(dirTag, module, version);
                }
            };
            BuildPlan plan =
                    LockPlans.plan(dir, effective, cache, repoUrl, features, withDefaults, mode, observer, null);
            for (Task p : plan.steps()) {
                host.sendQuiet(
                        writer,
                        ProtoEvents.planStep(
                                dirTag,
                                p.name(),
                                p.label(),
                                BridgingPlanListener.phaseWire(p.group().orElse(null))));
            }
            host.sendQuiet(writer, ProtoEvents.planDone(1));
            plan.addListener(host.planListener(dirTag, writer, result -> {
                lockPkgs.flush();
                lockPkgs.close();
                Lockfile lock = plan.get(LockPlans.LOCKFILE).orElse(null);
                return ProtoEvents.planFinishLock(
                        dirTag,
                        result.success(),
                        lock != null ? lock.artifacts().size() : -1,
                        lock != null
                                ? lock.artifacts().stream()
                                        .filter(a -> a.sourcesChecksum() != null)
                                        .count()
                                : -1,
                        lock != null ? lock.plugins().size() : -1);
            }));

            BuildPlanResult result = plan.run();
            lockPkgs.close();
            if (!result.success()) {
                int exit = LockPlans.failureExitCode(result);
                host.sendQuiet(writer, ProtoEvents.lockFinish(false, exit, List.of(), -1));
                return result.userCancelled() ? JobOutcome.cancelled() : JobOutcome.failed(exit);
            }
        }
        host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
        return JobOutcome.ok();
    }
}
