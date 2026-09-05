// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.ImagePlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ImageRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
    public String decodeJob(JobSpec spec) {
        // No test toggle on the dashboard/agent surface: an image job's deliverable is the image.
        return ProtoSession.withTrigger(
                new ImageRequest(
                                spec.dir(),
                                JkDirs.cache().toString(),
                                JkDirs.jdks().toString(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                false,
                                false,
                                false)
                        .encode(),
                "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                ImageRequest body = ImageRequest.decode(requestLine);
                Path entryDir = Path.of(body.dir());
                Path cache = Path.of(body.cache());
                String jdksDirStr = body.jdksDir();
                Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
                JkConfig config = JkConfig.empty()
                        .withOffline(body.offline())
                        .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                        .withVerbose(body.verbose())
                        .withForce(body.force());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withJdksDir(jdksDir)
                        .withCancel(cancelToken)
                        .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                        // The request's toolchain selection belongs on it too: without this the SWITCH tier is
                        // empty and a resident engine ignores both --jdk and JK_JDK.
                        .withToolchainSpecs(
                                ProtoSession.jdkSpecOf(requestLine),
                                ProtoSession.graalSpecOf(requestLine),
                                ProtoSession.graalHomeOf(requestLine));
                var wsRoot = WorkspaceLocator.findRoot(entryDir);
                if (wsRoot.isPresent()) {
                    JkBuild rootBuild = JkBuildParser.parse(wsRoot.get().resolve(ManifestPaths.MANIFEST));
                    if (rootBuild.isWorkspaceRoot()
                            && !BuildGraph.canonicalPath(wsRoot.get()).equals(BuildGraph.canonicalPath(entryDir))) {
                        // Workspace member: same orchestrator as jk build; image terminal on this
                        // module; prereqs package. Events are workspace-progress (not single-plan).
                        WorkspaceRequest req = new WorkspaceRequest(
                                        wsRoot.get(),
                                        cache,
                                        jdksDir,
                                        0,
                                        null,
                                        body.skipTests(),
                                        body.verbose(),
                                        0,
                                        null,
                                        true,
                                        true)
                                .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                                .withSpec(WorkspaceSpec.image(
                                        Set.of(entryDir.toAbsolutePath().normalize()),
                                        body.mainClass(),
                                        body.registry(),
                                        body.tag(),
                                        body.tarball(),
                                        body.dockerExecutable()));
                        long rid = host.eventRequestId();
                        if (rid > 0) host.putProgressRoot(rid, wsRoot.get().toString());
                        WorkspaceResult result = SessionContext.where(
                                session,
                                () -> BuildService.buildWorkspace(
                                        req,
                                        host.workspaceListener(
                                                writer, wsRoot.get().toString())));
                        return WorkspaceTerminal.finish(
                                host, writer, wsRoot.get().toString(), result, cancelToken.cancelled());
                    }
                }
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session: the plan factory's BuildPlanner.Inputs captures the
                // ambient SessionContext at construction, so building it outside where would
                // silently pin this request to the engine's default config (dropping --force et al).
                BuildPlan plan = SessionContext.where(
                        session,
                        () -> ImagePlans.imageBuildPlan(
                                entryDir,
                                cache,
                                jdksDir,
                                body.skipTests(),
                                body.verbose(),
                                body.mainClass(),
                                body.registry(),
                                body.tag(),
                                body.tarball(),
                                body.dockerExecutable()));
                return host.streamSinglePlan(plan, session, writer, result -> {
                    TestSummary testResult = plan.get(BuildPlanner.TEST_RESULT).orElse(null);
                    ImageConfig cfg = plan.get(ImagePlans.CONFIG).orElse(null);
                    Path tarball = plan.get(ImagePlans.TARBALL_PATH).orElse(null);
                    JkBuild project = plan.get(BuildPlanner.PROJECT).orElse(null);
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
                            plan.get(ImagePlans.IMAGE_REF).orElse(null),
                            tarball != null ? tarball.toString() : null,
                            project != null ? project.project().name() : null,
                            project != null ? project.project().version() : null,
                            daemonExe);
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
