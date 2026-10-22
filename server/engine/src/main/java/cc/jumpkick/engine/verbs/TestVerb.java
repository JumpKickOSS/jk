// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.runtime.TestClassMatch;
import cc.jumpkick.runtime.workspace.ModulePipeline;
import cc.jumpkick.runtime.workspace.StandalonePlanTimings;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.TestRequest;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Single-project {@code test-request}. */
public final class TestVerb implements HostedVerb {

    private final VerbHost host;

    public TestVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TEST_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("test");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-test-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            TestRequest body = TestRequest.decode(requestLine);
            String entryDirStr = body.dir();
            String cacheStr = body.cache();
            String jdksDirStr = body.jdksDir();
            int workers = body.workers();
            String profile = body.profile();
            boolean verbose = body.verbose();

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            int workerCount = Math.max(0, workers);
            Session session = ProtoSession.sessionOf(requestLine, cancelToken);
            WorkspaceRequest request = new WorkspaceRequest(
                            entryDir, cache, jdksDir, workerCount, profile, false, verbose, 0, null, false, false)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    .withTestOnly(true);
            BuildPlan plan = SessionContext.where(session, () -> ModulePipeline.standalone(request, null));
            StandalonePlanTimings.attach(plan, entryDir, cache, Clock.SYSTEM);

            PlanBurst.announce(host, plan, writer);
            BuildPlanResult result = SessionContext.where(session, plan::run);
            host.releaseExclusiveSlot();
            host.accTests(
                    host.eventRequestId(), plan.get(BuildPlanner.TEST_RESULT).orElse(null));
            host.accAffected(
                    host.eventRequestId(), plan.get(BuildPlanner.AFFECTED_TESTS).orElse(null));
            if (result.success()) return JobOutcome.ok();
            for (var d : result.errors()) {
                if ("affected-refuse".equals(d.code())) return JobOutcome.failed(Exit.CONFIG);
                if (TestClassMatch.NO_TESTS_MATCHED.equals(d.code())) return JobOutcome.failed(Exit.USAGE);
            }
            return JobOutcome.failed(Exit.FAILURE);
        } catch (ShadowManifests.NotBuiltHere refused) {
            // A shadowed directory Maven would not build here: a configuration refusal, not a crash.
            host.sendQuiet(writer, host.requestFailedLine(null, refused));
            return JobOutcome.failed(Exit.CONFIG);
        } catch (Exception e) {
            // The run threw before it could rule. Declining here would hand the journal a run
            // with no failure rows, which derives green — a test run that never finished,
            // recorded as a success.
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
