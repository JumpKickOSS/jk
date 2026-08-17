// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.plugin.protocol.Jsonl;
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
    public String decodeJob(cc.jumpkick.engine.jobs.JobSpec spec) {
        String m2 =
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        return cc.jumpkick.engine.protocol.ProtoSession.withTrigger(
                cc.jumpkick.engine.protocol.ProtoJobs.installRequest(
                        spec.dir(),
                        cc.jumpkick.util.JkDirs.cache().toString(),
                        m2,
                        null,
                        spec.skipTests(),
                        false,
                        false,
                        false),
                "web");
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
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
                cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                        session,
                        () -> cc.jumpkick.runtime.InstallPlans.projectInstallBuildPlan(
                                session.workingDir(),
                                session.cacheDir(),
                                Path.of(m2DirStr),
                                skipTests,
                                verbose,
                                graalHomeStr != null ? Path.of(graalHomeStr) : null));
                host.streamSinglePlan(plan, session, writer, result -> {
                    cc.jumpkick.run.TestSummary testResult = plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT)
                            .orElse(null);
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
