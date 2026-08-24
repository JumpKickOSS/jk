// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;

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
                ProtoSession.cacheClearRequest(JkDirs.cache().toString(), spec.dir(), false), "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String op = String.valueOf(Jsonl.str(requestLine, "op"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            boolean dryRun = Jsonl.bool(requestLine, "dryRun", false);

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
                                    case "clear" ->
                                        CachePlans.clearBuildPlan(
                                                cache, Path.of(Jsonl.str(requestLine, "dir")), dryRun);
                                    default ->
                                        CachePlans.pruneBuildPlan(
                                                cache, dryRun, Jsonl.bool(requestLine, "includeJkTmp", false));
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        String dir = EngineProtocol.SINGLE_PLAN_DIR;
                        host.streamSinglePlan(plan, session, writer, result -> {
                            // An explicit clean IS a prune — stamp it, or `usage` keeps warning
                            // "Last cleaned: never" right after a successful clean and the idle
                            // scheduler re-runs work the user just did. Same file for
                            // the store tier: its usage footer reads from its own root.
                            if (result.success() && !dryRun && ("prune".equals(op) || "sweep".equals(op))) {
                                CacheMaintenanceLocks.stampLastPruned(
                                        cache,
                                        host.nowMillis(),
                                        plan.get(CachePlans.FINAL_ACTION_BYTES).orElse(-1L));
                            }
                            return ProtoSession.planFinishCache(
                                    dir,
                                    result.success(),
                                    plan.get(CachePlans.FILES).orElse(-1L),
                                    plan.get(CachePlans.BYTES).orElse(-1L));
                        });
                    });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
