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
import cc.jumpkick.runtime.workspace.ModulePipeline;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.InstallRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return new InstallRequest(
                        spec.dir(), JkDirs.cache().toString(), null, m2, null, spec.skipTests(), false, false, false)
                .encode();
    }

    private static Map<Path, Path> graalByDir(Path dir, @Nullable String graalHome) {
        return graalHome == null ? Map.of() : Map.of(dir, Path.of(graalHome));
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                InstallRequest body = InstallRequest.decode(requestLine);
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                WorkspaceRequest request = new WorkspaceRequest(
                                session.workingDir(),
                                session.cacheDir(),
                                session.jdksDir(),
                                session.requestedTestWorkers(),
                                session.profile(),
                                body.skipTests(),
                                body.verbose(),
                                0,
                                null,
                                false,
                                false)
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                        .withSpec(WorkspaceSpec.install(
                                Set.of(), graalByDir(session.workingDir(), body.graalHome()), Path.of(body.m2Dir())));
                BuildPlan plan = SessionContext.where(session, () -> ModulePipeline.standalone(request, null));
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
