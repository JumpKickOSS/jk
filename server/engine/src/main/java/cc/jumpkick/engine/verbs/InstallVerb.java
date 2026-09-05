// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.InstallRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
                new InstallRequest(
                                spec.dir(), JkDirs.cache().toString(), m2, null, spec.skipTests(), false, false, false)
                        .encode(),
                "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                InstallRequest body = InstallRequest.decode(requestLine);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session — see runImage's note on ambient-session capture.
                BuildPlan plan = SessionContext.where(
                        session,
                        () -> InstallPlans.projectInstallBuildPlan(
                                session.workingDir(),
                                session.cacheDir(),
                                Path.of(body.m2Dir()),
                                body.skipTests(),
                                body.verbose(),
                                body.graalHome() != null ? Path.of(body.graalHome()) : null));
                return host.streamSinglePlan(plan, session, writer, result -> {
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
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
