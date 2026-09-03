// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.CachePruneRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class CacheMaintenanceVerb implements HostedVerb {

    private final VerbHost host;

    public CacheMaintenanceVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.CACHE_PRUNE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.maintenance("cache");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.CacheMaint();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-cache-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("clean");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        return ProtoSession.withTrigger(
                new CachePruneRequest("clear", JkDirs.cache().toString(), spec.dir(), false, false).encode(), "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        // The maintenance body runs under two locks and cannot hand its verdict back through a
        // void Runnable; this is where it lands.
        AtomicReference<PlanBurst.Outcome> finished = new AtomicReference<>();
        AtomicReference<BuildPlan> ranPlan = new AtomicReference<>();
        try {
            CachePruneRequest req = CachePruneRequest.decode(requestLine);
            String op = String.valueOf(req.op());
            Path cache = Path.of(req.cache());
            boolean dryRun = req.dryRun();

            CacheMaintenanceLocks.exclusively(
                    host.cacheGate(),
                    cache,
                    () -> host.sendQuiet(writer, ProtoSession.pruneWait(host.activePlanCount(), false)),
                    () -> host.sendQuiet(writer, ProtoSession.pruneWait(0, true)),
                    () -> {
                        BuildPlan plan =
                                switch (op) {
                                    case "purge" -> CachePlans.purgeBuildPlan(cache);
                                    case "sweep" -> CachePlans.sweepBuildPlan(cache, dryRun);
                                    case "clear" -> CachePlans.clearBuildPlan(cache, Path.of(req.dir()), dryRun);
                                    default -> CachePlans.pruneBuildPlan(cache, dryRun, req.includeJkTmp());
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        ranPlan.set(plan);
                        PlanBurst.Outcome out = PlanBurst.streamWithoutFinish(host, plan, session, writer);
                        // An explicit clean IS a prune — stamp it, or `usage` keeps warning
                        // "Last cleaned: never" right after a successful clean and the idle
                        // scheduler re-runs work the user just did. Same file for
                        // the store tier: its usage footer reads from its own root. The stamp is
                        // a write under the cache root, so it belongs under the lock.
                        if (out.result().success() && !dryRun && ("prune".equals(op) || "sweep".equals(op))) {
                            CacheMaintenanceLocks.stampLastPruned(
                                    cache,
                                    host.nowMillis(),
                                    plan.get(CachePlans.FINAL_ACTION_BYTES).orElse(-1L));
                        }
                        finished.set(out);
                    });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
        // The terminal is sent only after both locks are released: the nuke client deletes the
        // cache root — the .prune.lock in it included — the moment it reads this line, and on
        // Windows the engine's still-open lock handle would make that delete fail.
        PlanBurst.Outcome out = finished.get();
        BuildPlan plan = ranPlan.get();
        host.sendQuiet(
                writer,
                ProtoSession.planFinishCache(
                        EngineProtocol.SINGLE_PLAN_DIR,
                        out.result().success(),
                        plan.get(CachePlans.FILES).orElse(-1L),
                        plan.get(CachePlans.BYTES).orElse(-1L)));
        return out.outcome();
    }
}
