// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.TrainPlans;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class TrainVerb implements HostedVerb {

    private final VerbHost host;

    public TrainVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TRAIN_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("train");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-train-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                boolean force = Jsonl.bool(requestLine, "force", false);
                String profile = Jsonl.str(requestLine, "profile");
                String graalHomeStr = Jsonl.str(requestLine, "graalHome");
                String jdksDirStr = Jsonl.str(requestLine, ProtoJobs.JDKS_DIR);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                Path graalHome = graalHomeStr != null && !graalHomeStr.isBlank() ? Path.of(graalHomeStr) : null;
                Path jdksDir = jdksDirStr != null && !jdksDirStr.isBlank() ? Path.of(jdksDirStr) : null;
                Path javaHome = Path.of(System.getProperty("java.home"));
                BuildPlan plan = SessionContext.where(session, () -> {
                    JkBuild module = JkBuildParser.parse(session.workingDir().resolve(ManifestPaths.MANIFEST));
                    return TrainPlans.moduleBuildPlan(
                            session.workingDir(),
                            module,
                            session.cacheDir(),
                            jdksDir,
                            graalHome,
                            javaHome,
                            profile,
                            force,
                            skipTests,
                            verbose);
                });
                return host.streamSinglePlan(
                        plan, session, writer, result -> ProtoEvents.planFinish(dir, result.success()));
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
