// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Optional;

public final class ImageVerb implements HostedVerb {

    private final VerbHost host;

    public ImageVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.IMAGE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("image");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-image-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
                Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                JkConfig config = new JkConfig(
                        Optional.empty(),
                        Optional.of(Jsonl.bool(requestLine, "offline", false)),
                        Optional.of(Jsonl.bool(requestLine, "rebuild", false)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(verbose),
                        Optional.empty(),
                        Optional.of(Jsonl.bool(requestLine, "force", false)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withJdksDir(jdksDir)
                        .withCancel(cancelToken)
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session: the plan factory's BuildPlanner.Inputs captures the
                // ambient SessionContext at construction, so building it outside where would
                // silently pin this request to the engine's default config (dropping --force et al).
                cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                        session,
                        () -> cc.jumpkick.runtime.ImagePlans.imageBuildPlan(
                                entryDir,
                                cache,
                                jdksDir,
                                skipTests,
                                verbose,
                                Jsonl.str(requestLine, "mainClass"),
                                Jsonl.str(requestLine, "registry"),
                                Jsonl.str(requestLine, "tag"),
                                Jsonl.str(requestLine, "tarball"),
                                Jsonl.str(requestLine, "dockerExecutable")));
                host.streamSinglePlan(plan, session, writer, result -> {
                    cc.jumpkick.run.TestSummary testResult = plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT)
                            .orElse(null);
                    cc.jumpkick.image.ImageConfig cfg =
                            plan.get(cc.jumpkick.runtime.ImagePlans.CONFIG).orElse(null);
                    Path tarball = plan.get(cc.jumpkick.runtime.ImagePlans.TARBALL_PATH)
                            .orElse(null);
                    JkBuild project =
                            plan.get(cc.jumpkick.runtime.BuildPlanner.PROJECT).orElse(null);
                    boolean daemonMode = tarball == null
                            && (cfg == null
                                    || cfg.registry() == null
                                    || cfg.registry().isBlank());
                    String daemonExe = !daemonMode
                            ? null
                            : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
                    return ProtoEvents.planFinishImage(
                            dir,
                            result.success(),
                            testResult != null ? testResult.total() : -1,
                            testResult != null ? testResult.succeeded() : -1,
                            testResult != null ? testResult.failed() : -1,
                            testResult != null ? testResult.skipped() : -1,
                            plan.get(cc.jumpkick.runtime.ImagePlans.IMAGE_REF).orElse(null),
                            tarball != null ? tarball.toString() : null,
                            project != null ? project.project().name() : null,
                            project != null ? project.project().version() : null,
                            daemonExe);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
