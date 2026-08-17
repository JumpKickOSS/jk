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
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    public List<String> jobKinds() {
        return List.of("image");
    }

    @Override
    public String decodeJob(cc.jumpkick.engine.jobs.JobSpec spec) {
        // No test toggle on the dashboard/agent surface: an image job's deliverable is the image.
        return cc.jumpkick.engine.protocol.ProtoSession.withTrigger(
                cc.jumpkick.engine.protocol.ProtoJobs.imageRequest(
                        spec.dir(),
                        cc.jumpkick.util.JkDirs.cache().toString(),
                        cc.jumpkick.util.JkDirs.jdks().toString(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false,
                        false,
                        false),
                "web");
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
                        Optional.empty(),
                        Optional.empty());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withJdksDir(jdksDir)
                        .withCancel(cancelToken)
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine));
                var wsRoot = cc.jumpkick.config.WorkspaceLocator.findRoot(entryDir);
                if (wsRoot.isPresent()) {
                    JkBuild rootBuild =
                            cc.jumpkick.config.JkBuildParser.parse(wsRoot.get().resolve("jk.toml"));
                    if (rootBuild.isWorkspaceRoot()
                            && !cc.jumpkick.runtime.BuildGraph.canonicalPath(wsRoot.get())
                                    .equals(cc.jumpkick.runtime.BuildGraph.canonicalPath(entryDir))) {
                        // Workspace member: same orchestrator as jk build; image terminal on this
                        // module; prereqs package. Events are workspace-progress (not single-plan).
                        WorkspaceRequest req = new WorkspaceRequest(
                                        wsRoot.get(),
                                        rootBuild,
                                        cache,
                                        jdksDir,
                                        0,
                                        null,
                                        skipTests,
                                        verbose,
                                        0,
                                        null,
                                        true,
                                        true)
                                .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                                .withSpec(WorkspaceSpec.image(
                                        Set.of(entryDir.toAbsolutePath().normalize()),
                                        Jsonl.str(requestLine, "mainClass"),
                                        Jsonl.str(requestLine, "registry"),
                                        Jsonl.str(requestLine, "tag"),
                                        Jsonl.str(requestLine, "tarball"),
                                        Jsonl.str(requestLine, "dockerExecutable")));
                        long rid = host.eventRequestId();
                        if (rid > 0) host.putProgressRoot(rid, wsRoot.get().toString());
                        WorkspaceResult result = SessionContext.where(
                                session,
                                () -> BuildService.buildWorkspace(
                                        req,
                                        host.workspaceListener(
                                                writer, wsRoot.get().toString())));
                        host.releaseExclusiveSlot();
                        boolean cancelled = result.cancelled() || host.effectiveCancelled(rid, cancelToken.cancelled());
                        host.accOutcome(rid, result.success() && !cancelled, result.exitCode());
                        if (rid > 0) {
                            if (result.success() && !cancelled) host.finishProgress(rid);
                            host.emitWorkspaceProgress(rid, writer, true);
                        }
                        host.flushTimeline(rid, writer);
                        host.sendQuiet(
                                writer,
                                ProtoEvents.workspaceFinish(
                                        result.success() && !cancelled, result.exitCode(), result.errors(), cancelled));
                        return;
                    }
                }
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
