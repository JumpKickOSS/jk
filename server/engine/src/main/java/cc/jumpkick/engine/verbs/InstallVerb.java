// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;

public final class InstallVerb implements HostedVerb {

    private final VerbHost host;

    public InstallVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.INSTALL_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("install");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-install-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("install");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        String m2 =
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        return ProtoSession.withTrigger(
                ProtoJobs.installRequest(
                        spec.dir(), JkDirs.cache().toString(), m2, null, spec.skipTests(), false, false, false),
                "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                String m2DirStr = Jsonl.str(requestLine, "m2Dir");
                String graalHomeStr = Jsonl.str(requestLine, "graalHome");
                Session session = host.resolveSession(requestLine, cancelToken, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session — see runImage's note on ambient-session capture.
                BuildPlan plan = SessionContext.where(
                        session,
                        () -> InstallPlans.projectInstallBuildPlan(
                                session.workingDir(),
                                session.cacheDir(),
                                Path.of(m2DirStr),
                                skipTests,
                                verbose,
                                graalHomeStr != null ? Path.of(graalHomeStr) : null));
                host.streamSinglePlan(plan, session, writer, result -> {
                    TestSummary testResult = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
                    return testResult == null
                            ? ProtoEvents.planFinish(dir, result.success())
                            : ProtoEvents.planFinish(
                                    dir,
                                    result.success(),
                                    testResult.total(),
                                    testResult.succeeded(),
                                    testResult.failed(),
                                    testResult.skipped());
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
