// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.runtime.workspace.SyncPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.SyncRequest;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            SyncRequest body = SyncRequest.decode(requestLine);
            boolean refresh = body.refresh();
            String jdksDirStr = body.jdksDir();
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Session base = ProtoSession.sessionOf(requestLine, cancelToken);
            // --refresh is sync's spelling of force: re-fetch what the cache already holds.
            Session session = refresh ? base.withConfig(base.config().withForce(true)) : base;
            URI repoUrl = body.repoUrl() == null ? null : URI.create(body.repoUrl());
            return SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                Files.createDirectories(cache);
                AtomicInteger fetched = new AtomicInteger();
                AtomicInteger upToDate = new AtomicInteger();
                BuildPlan plan = SyncPlans.syncBuildPlan(
                        entryDir, cache, jdksDir, repoUrl, body.sources(), fetched, upToDate, null, false);
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
                if (result.userCancelled()) return JobOutcome.cancelled();
                return result.success() ? JobOutcome.ok() : JobOutcome.failed(Exit.FAILURE);
            });
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
