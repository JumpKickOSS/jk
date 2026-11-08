// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.engine.api.CoalescingLockPackages;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockDiff;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.io.IOException;
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
        return run(host, entryDir, cache, repoUrl, features, withDefaults, mode, skipWhenFresh, null, false, writer);
    }

    /**
     * As {@link #run(VerbHost, Path, Path, URI, List, boolean, LockMode, boolean, BufferedWriter)},
     * first moving the declared exact pins {@code rewrite} selects ({@code jk update}): each move
     * streams as an {@code update-rewrite} event, the manifests are written, and the resolve then
     * runs against the rewritten tree. Unless {@code accept}, a move across a minor or a major is a
     * decision for a person: such pins move only to their newest patch, each held move streams as a
     * held {@code update-rewrite} event from the version its pin lands on, and a run notice names
     * the moves held and the command that takes them. When every move is held nothing is written or
     * relocked.
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
            ManifestUpdates.@Nullable Selection rewrite,
            boolean accept,
            @Nullable BufferedWriter writer)
            throws Exception {
        Files.createDirectories(cache);
        Path lockDir;
        JkBuild effective;
        String coord;
        FeatureSelection selection = new FeatureSelection(features, withDefaults);
        try {
            var scope = LockPlans.lockScope(entryDir, selection);
            lockDir = scope.lockDir();
            effective = scope.effective();
            coord = scope.coord();
        } catch (RuntimeException e) {
            return fail(host, writer, Exit.CONFIG, Errors.text(e));
        }

        synchronized (LockGate.monitorFor(lockDir)) {
            // needsRefresh, not isStale: a missing lock is not "stale" (isStale is digest-only)
            // but an invisible freshen must still write one (jk tree / explain / status).
            if (skipWhenFresh && !LockFreshness.needsRefresh(lockDir)) {
                host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
                return JobOutcome.ok();
            }
            @Nullable Lockfile before = LockDiff.current(lockDir);
            if (rewrite != null) {
                try {
                    ManifestUpdates.Plan plan =
                            ManifestUpdates.plan(lockDir, repoUrl, accept ? rewrite : rewrite.holding());
                    String held = held(plan);
                    if (held != null) RunNotices.warnOnce(HELD_NOTICE, () -> held);
                    sendRewrites(host, writer, plan);
                    if (plan.isEmpty() && !plan.held().isEmpty()) {
                        host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
                        return JobOutcome.ok();
                    }
                    ManifestUpdates.apply(plan);
                    // The manifests just changed: the merged model the resolve sees must be theirs.
                    if (!plan.isEmpty())
                        effective = LockPlans.lockScope(entryDir, selection).effective();
                } catch (RuntimeException | IOException e) {
                    return fail(host, writer, Exit.CONFIG, Errors.text(e));
                }
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
                public void onPhase(String label) {
                    host.sendQuiet(writer, ProtoEvents.lockPhase(dirTag, label));
                }

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
                RepoGroup.TrustSummary trust = plan.get(LockPlans.TRUST).orElse(RepoGroup.TrustSummary.NONE);
                List<LockDiff.Change> changes = lock != null ? LockDiff.between(before, lock) : List.of();
                if (rewrite != null) sendChanges(host, writer, dirTag, changes);
                return ProtoEvents.planFinishLock(
                        dirTag,
                        result.success(),
                        lock != null ? lock.artifacts().size() : -1,
                        lock != null ? changes.size() : -1,
                        lock != null
                                ? lock.artifacts().stream()
                                        .filter(a -> a.sourcesChecksum() != null)
                                        .count()
                                : -1,
                        lock != null ? lock.plugins().size() : -1,
                        trust.unverifiedAllowed(),
                        trust.insecureRepos());
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

    /**
     * End the request on {@code error}, a failure no plan step carries: the {@code lock-finish}
     * event names it for the client, and the run's journal records it, so {@code jk-results.md}
     * and the agent report say why the lock failed.
     */
    static JobOutcome fail(VerbHost host, @Nullable BufferedWriter writer, int exit, String error) {
        long rid = host.eventRequestId();
        if (rid > 0) host.accRunErrors(rid, List.of(error));
        host.sendQuiet(writer, ProtoEvents.lockFinish(false, exit, List.of(error), -1));
        return JobOutcome.failed(exit);
    }

    /** One {@code update-change} event per package the relock added, removed or moved. */
    private static void sendChanges(
            VerbHost host, @Nullable BufferedWriter writer, String dirTag, List<LockDiff.Change> changes) {
        for (LockDiff.Change c : changes) {
            host.sendQuiet(writer, ProtoEvents.updateChange(dirTag, c.coordinate(), c.from(), c.to(), c.members()));
        }
    }

    /**
     * The run notice naming {@code plan}'s moves across a minor or a major, held for a person, or
     * null when it has none.
     */
    static @Nullable String held(ManifestUpdates.Plan plan) {
        List<String> crossing = plan.held().stream()
                .map(r -> r.handle() + " " + r.from() + " → " + r.to())
                .toList();
        if (crossing.isEmpty()) return null;
        return "held " + crossing.size() + (crossing.size() == 1 ? " pin" : " pins") + " across a minor or a major ("
                + UPDATE_ACCEPT + " takes them): " + String.join(", ", crossing);
    }

    /** The command that accepts moves across a minor or a major. */
    static final String UPDATE_ACCEPT = "jk update -y";

    /** The {@link RunNotices} key of the held-moves notice. */
    private static final String HELD_NOTICE = "update-held";

    /** One {@code update-rewrite} event per planned pin move, then one held event per move held. */
    static void sendRewrites(VerbHost host, @Nullable BufferedWriter writer, ManifestUpdates.Plan plan) {
        for (ManifestUpdates.Rewrite r : plan.rewrites()) sendRewrite(host, writer, r, false);
        for (ManifestUpdates.Rewrite r : plan.held()) sendRewrite(host, writer, r, true);
    }

    private static void sendRewrite(
            VerbHost host, @Nullable BufferedWriter writer, ManifestUpdates.Rewrite r, boolean held) {
        host.sendQuiet(
                writer,
                ProtoEvents.updateRewrite(
                        r.dir().toString(), r.table(), r.handle(), r.module(), r.from(), r.to(), held));
    }
}
