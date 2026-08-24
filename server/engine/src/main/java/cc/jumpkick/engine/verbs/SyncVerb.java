// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.SyncPlans;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code sync-request}: fetch/up-to-date counts ride plan-finish. */
public final class SyncVerb implements HostedVerb {

    private final VerbHost host;

    public SyncVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.SYNC_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("sync");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-sync-";
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean sources = Jsonl.bool(requestLine, "sources", false);
            boolean refresh = Jsonl.bool(requestLine, "refresh", false);
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Session session =
                    host.resolveSession(requestLine, cancelToken, refresh).withJdksDir(jdksDir);
            URI repoUrl = LockVerb.repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                Files.createDirectories(cache);
                AtomicInteger fetched = new AtomicInteger();
                AtomicInteger upToDate = new AtomicInteger();
                BuildPlan plan = SyncPlans.syncBuildPlan(
                        entryDir, cache, jdksDir, repoUrl, sources, fetched, upToDate, null, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                for (Task p : plan.steps()) {
                    host.sendQuiet(
                            writer,
                            ProtoEvents.planStep(
                                    dir,
                                    p.name(),
                                    p.label(),
                                    BridgingPlanListener.phaseWire(p.group().orElse(null))));
                }
                host.sendQuiet(writer, ProtoEvents.planDone(1));
                plan.addListener(host.planListener(
                        dir,
                        writer,
                        (BuildPlanResult result) ->
                                ProtoEvents.planFinishSync(dir, result.success(), fetched.get(), upToDate.get())));
                BuildPlanResult result = plan.run();
                if (result.success()) host.maybeEnqueuePrune(cache);
                return null;
            });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
