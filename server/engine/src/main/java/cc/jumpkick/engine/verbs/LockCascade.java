// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.engine.api.CoalescingLockPackages;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
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
import cc.jumpkick.runtime.workspace.AddedPackages;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * the moves held and the command that takes them. Unless {@code accept}, a move whose relock adds
     * a package to an existing lock is held too, the pin left where it stood: a resolve that reads
     * the moved manifests from memory and writes nothing finds the packages and the moves that reach
     * them ({@link AddedPackages}), and the held event and a run notice name them. Only the moves
     * taken are written, once; when every move is held nothing is written or relocked.
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
                    Map<ManifestUpdates.Rewrite, List<String>> adding = accept
                            ? Map.of()
                            : adding(lockDir, cache, repoUrl, features, withDefaults, mode, before, plan);
                    List<ManifestUpdates.Rewrite> taken = plan.rewrites().stream()
                            .filter(r -> !adding.containsKey(r))
                            .toList();
                    List<ManifestUpdates.Rewrite> held = heldFrom(plan.held(), adding);
                    if (adding.isEmpty()) {
                        ManifestUpdates.apply(plan);
                    } else if (!taken.isEmpty()) {
                        ManifestUpdates.apply(ManifestUpdates.taking(lockDir, taken));
                    }
                    String heldNotice = held(held);
                    if (heldNotice != null) RunNotices.warnOnce(HELD_NOTICE, () -> heldNotice);
                    String addsNotice = heldAdds(adding);
                    if (addsNotice != null) RunNotices.warnOnce(HELD_ADDS_NOTICE, () -> addsNotice);
                    sendRewrites(host, writer, taken, held, adding);
                    if (taken.isEmpty() && !(held.isEmpty() && adding.isEmpty())) {
                        host.sendQuiet(writer, ProtoEvents.lockFinish(true, 0, List.of(), -1));
                        return JobOutcome.ok();
                    }
                    // The manifests just changed: the merged model the resolve sees must be theirs.
                    if (!taken.isEmpty())
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
     * The moves of {@code plan} whose relock adds packages to {@code before}, each mapped to the
     * packages it adds, read off a resolve of the plan's manifest text, written nowhere. Empty when
     * there is no lock yet (a first lock adds every package), no move, or the resolve fails: the
     * relock that follows meets the same failure and reports it.
     */
    private static Map<ManifestUpdates.Rewrite, List<String>> adding(
            Path lockDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode,
            @Nullable Lockfile before,
            ManifestUpdates.Plan plan) {
        if (before == null || plan.isEmpty()) return Map.of();
        try {
            Lockfile probe =
                    AddedPackages.probe(lockDir, plan.contents(), cache, repoUrl, features, withDefaults, mode);
            return AddedPackages.byMove(before, probe, plan.rewrites());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.debug("update: no probe of the packages the moves add", e);
            return Map.of();
        }
    }

    /**
     * {@code held}, each move whose pin's own move {@code adding} holds starting where that pin now
     * stays, its {@code from}.
     */
    static List<ManifestUpdates.Rewrite> heldFrom(
            List<ManifestUpdates.Rewrite> held, Map<ManifestUpdates.Rewrite, List<String>> adding) {
        return held.stream()
                .map(h -> adding.keySet().stream()
                        .filter(m -> m.file().equals(h.file())
                                && m.table().equals(h.table())
                                && m.handle().equals(h.handle())
                                && m.to().equals(h.from()))
                        .findFirst()
                        .map(m -> new ManifestUpdates.Rewrite(
                                h.file(),
                                h.dir(),
                                h.moduleLabel(),
                                h.table(),
                                h.handle(),
                                h.module(),
                                m.from(),
                                h.to()))
                        .orElse(h))
                .toList();
    }

    /**
     * The run notice naming the moves across a minor or a major {@code held} for a person, or null
     * when there are none.
     */
    static @Nullable String held(List<ManifestUpdates.Rewrite> held) {
        List<String> crossing = held.stream()
                .map(r -> r.handle() + " " + r.from() + " → " + r.to())
                .toList();
        if (crossing.isEmpty()) return null;
        return "held " + crossing.size() + (crossing.size() == 1 ? " pin" : " pins") + " across a minor or a major ("
                + UPDATE_ACCEPT + " takes them): " + String.join(", ", crossing);
    }

    /**
     * The run notice naming the moves held because their relock adds packages, and the packages, or
     * null when there are none.
     */
    static @Nullable String heldAdds(Map<ManifestUpdates.Rewrite, List<String>> adding) {
        if (adding.isEmpty()) return null;
        List<String> moves = new ArrayList<>();
        adding.forEach((r, adds) ->
                moves.add(r.handle() + " " + r.from() + " → " + r.to() + " adds " + String.join(", ", adds)));
        return "held " + adding.size() + (adding.size() == 1 ? " pin" : " pins") + " whose relock adds packages ("
                + UPDATE_ACCEPT + " takes " + (adding.size() == 1 ? "it" : "them") + "): " + String.join("; ", moves);
    }

    /** The command that accepts moves across a minor or a major. */
    static final String UPDATE_ACCEPT = "jk update -y";

    /** The {@link RunNotices} key of the held-moves notice. */
    private static final String HELD_NOTICE = "update-held";

    /** The {@link RunNotices} key of the notice on moves held because their relock adds packages. */
    private static final String HELD_ADDS_NOTICE = "update-held-adds";

    /**
     * One {@code update-rewrite} event per move {@code taken}, then one held event per move {@code
     * held} across a minor or a major and per move held because its relock adds the packages {@code
     * adding} names.
     */
    static void sendRewrites(
            VerbHost host,
            @Nullable BufferedWriter writer,
            List<ManifestUpdates.Rewrite> taken,
            List<ManifestUpdates.Rewrite> held,
            Map<ManifestUpdates.Rewrite, List<String>> adding) {
        for (ManifestUpdates.Rewrite r : taken) sendRewrite(host, writer, r, false, List.of());
        for (ManifestUpdates.Rewrite r : held) sendRewrite(host, writer, r, true, List.of());
        adding.forEach((r, adds) -> sendRewrite(host, writer, r, true, adds));
    }

    /** One {@code update-rewrite} event: {@code r}, {@code held} or taken, adding {@code adds}. */
    static void sendRewrite(
            VerbHost host,
            @Nullable BufferedWriter writer,
            ManifestUpdates.Rewrite r,
            boolean held,
            List<String> adds) {
        host.sendQuiet(
                writer,
                ProtoEvents.updateRewrite(
                        r.dir().toString(), r.table(), r.handle(), r.module(), r.from(), r.to(), held, adds));
    }
}
