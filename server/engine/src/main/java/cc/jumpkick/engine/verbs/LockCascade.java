// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.engine.CoalescingLockPackages;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.LockGate;
import cc.jumpkick.runtime.LockPlans;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One lock/update plan that writes the single workspace (or standalone) {@code jk-lock.toml}.
 * Members redirect to the root. Serialized per lock dir (JK-1356).
 */
final class LockCascade {

    private LockCascade() {}

    static void run(
            VerbHost host,
            Path entryDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            boolean sources,
            boolean update,
            @Nullable String platformOverride,
            BufferedWriter writer)
            throws Exception {
        run(host, entryDir, cache, repoUrl, features, withDefaults, sources, update, platformOverride, false, writer);
    }

    static void run(
            VerbHost host,
            Path entryDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            boolean sources,
            boolean update,
            @Nullable String platformOverride,
            boolean conservative,
            BufferedWriter writer)
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
            host.sendQuiet(
                    writer, EngineProtocol.lockFinish(false, Exit.CONFIG, List.of(String.valueOf(e.getMessage())), -1));
            return;
        }

        synchronized (LockGate.monitorFor(lockDir)) {
            if (conservative && !LockFreshness.isStale(lockDir, LockPaths.lockFile(lockDir))) {
                host.sendQuiet(writer, EngineProtocol.lockFinish(true, 0, List.of(), -1));
                return;
            }
            Path dir = lockDir;
            String dirTag = dir.toString();
            host.sendQuiet(writer, EngineProtocol.lockModule(dirTag, coord));

            CoalescingLockPackages lockPkgs = new CoalescingLockPackages(
                    (d, name, ver, total) -> host.sendQuiet(writer, EngineProtocol.lockPackage(d, name, ver, total)));
            ResolveObserver observer = new ResolveObserver() {
                @Override
                public void onTotal(int total) {}

                @Override
                public void onPackage(String module, String version) {
                    lockPkgs.onPackage(dirTag, module, version);
                }
            };
            BuildPlan plan = update
                    ? LockPlans.updateBuildPlan(
                            dir, effective, cache, repoUrl, features, withDefaults, platformOverride)
                    : LockPlans.lockBuildPlan(
                            dir,
                            effective,
                            cache,
                            repoUrl,
                            features,
                            withDefaults,
                            sources,
                            conservative,
                            observer,
                            null);
            for (Task p : plan.steps()) {
                host.sendQuiet(
                        writer,
                        EngineProtocol.planStep(
                                dirTag,
                                p.name(),
                                p.label(),
                                BridgingPlanListener.phaseWire(p.group().orElse(null))));
            }
            host.sendQuiet(writer, EngineProtocol.planDone(1));
            plan.addListener(host.planListener(dirTag, writer, result -> {
                lockPkgs.flush();
                lockPkgs.close();
                Lockfile lock = plan.get(LockPlans.LOCKFILE).orElse(null);
                return EngineProtocol.planFinishLock(
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
                host.sendQuiet(
                        writer, EngineProtocol.lockFinish(false, LockPlans.failureExitCode(result), List.of(), -1));
                return;
            }
        }
        host.sendQuiet(writer, EngineProtocol.lockFinish(true, 0, List.of(), -1));
    }
}
