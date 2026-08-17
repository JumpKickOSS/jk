// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CompileVerb implements HostedVerb {

    private final VerbHost host;

    public CompileVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.COMPILE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("compile");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-compile-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("compile");
    }

    @Override
    public String decodeJob(cc.jumpkick.engine.jobs.JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        List<String> moduleDirs = List.of();
        if (!spec.modules().isEmpty()) {
            cc.jumpkick.model.JkBuild entry;
            try {
                entry = cc.jumpkick.config.JkBuildParser.parse(entryDir.resolve("jk.toml"));
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
            }
            Set<Path> selected = cc.jumpkick.engine.jobs.JobSelect.selected(entryDir, entry, spec.modules());
            if (selected != null) {
                moduleDirs = selected.stream().map(Path::toString).sorted().toList();
            }
        }
        return cc.jumpkick.engine.protocol.ProtoSession.withTrigger(
                cc.jumpkick.engine.protocol.ProtoJobs.compileRequest(
                        spec.dir(), cc.jumpkick.util.JkDirs.cache().toString(), null, false, false, false, moduleDirs),
                "web");
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String profile = Jsonl.str(requestLine, "profile");
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                Path entryDir = session.workingDir();
                // Workspace (root or member): the one-orchestrator COMPILE path — compile-only
                // terminal on the selection, prereqs packaged first via the shared cascade
                // (JK-2103). The client mirrors this condition and expects workspace events.
                var wsRoot = cc.jumpkick.config.WorkspaceLocator.findRoot(entryDir);
                if (wsRoot.isPresent()) {
                    cc.jumpkick.model.JkBuild rootBuild =
                            cc.jumpkick.config.JkBuildParser.parse(wsRoot.get().resolve("jk.toml"));
                    if (rootBuild.isWorkspaceRoot()) {
                        Set<Path> selected = new LinkedHashSet<>();
                        List<String> raw = new ArrayList<>();
                        String affected = null;
                        for (String d : Jsonl.strArray(requestLine, "moduleDirs")) {
                            if (d == null || d.isBlank()) continue;
                            if (d.startsWith("affected:")) affected = d.substring("affected:".length());
                            else raw.add(d);
                        }
                        if (!raw.isEmpty() || affected != null) {
                            try {
                                var hit = cc.jumpkick.config.ModuleSelection.resolveOptional(
                                        entryDir, rootBuild, raw.isEmpty() ? null : String.join(",", raw), affected);
                                if (hit != null && !hit.ok()) {
                                    host.sendQuiet(
                                            writer,
                                            host.requestFailedLine(
                                                    null, new IllegalArgumentException(hit.errorMessage())));
                                    return cc.jumpkick.engine.jobs.JobOutcome.failed(2);
                                }
                                if (hit != null) selected.addAll(hit.moduleDirs());
                            } catch (IllegalArgumentException e) {
                                host.sendQuiet(writer, host.requestFailedLine(null, e));
                                return cc.jumpkick.engine.jobs.JobOutcome.failed(2);
                            }
                        }
                        boolean isMember = !cc.jumpkick.runtime.BuildGraph.canonicalPath(wsRoot.get())
                                .equals(cc.jumpkick.runtime.BuildGraph.canonicalPath(entryDir));
                        if (selected.isEmpty() && isMember) {
                            selected.add(entryDir.toAbsolutePath().normalize());
                        }
                        cc.jumpkick.runtime.WorkspaceRequest req = new cc.jumpkick.runtime.WorkspaceRequest(
                                        wsRoot.get(),
                                        rootBuild,
                                        session.cacheDir(),
                                        session.jdksDir(),
                                        0,
                                        profile,
                                        true,
                                        verbose,
                                        0,
                                        null,
                                        true,
                                        true)
                                .withVariant(
                                        cc.jumpkick.engine.protocol.ProtoSession.variantOf(requestLine),
                                        cc.jumpkick.engine.protocol.ProtoSession.clientEnvOf(requestLine))
                                .withSpec(cc.jumpkick.runtime.WorkspaceSpec.compile(selected));
                        long rid = host.eventRequestId();
                        if (rid > 0) host.putProgressRoot(rid, wsRoot.get().toString());
                        cc.jumpkick.runtime.WorkspaceResult result = SessionContext.where(
                                session,
                                () -> cc.jumpkick.runtime.BuildService.buildWorkspace(
                                        req,
                                        host.workspaceListener(
                                                writer, wsRoot.get().toString())));
                        host.releaseExclusiveSlot();
                        boolean cancelled = result.cancelled() || host.effectiveCancelled(rid, cancelToken.cancelled());
                        cc.jumpkick.engine.jobs.JobOutcome outcome = cc.jumpkick.engine.jobs.JobOutcome.of(
                                result.success() && !cancelled, result.exitCode());
                        if (rid > 0) {
                            if (result.success() && !cancelled) host.finishProgress(rid);
                            host.emitWorkspaceProgress(rid, writer, true);
                        }
                        host.flushTimeline(rid, writer);
                        host.sendQuiet(
                                writer,
                                ProtoEvents.workspaceFinish(
                                        result.success() && !cancelled, result.exitCode(), result.errors(), cancelled));
                        return outcome;
                    }
                }
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Constructed in-session — see runImage's note on ambient-session capture.
                cc.jumpkick.run.BuildPlan plan = SessionContext.where(
                        session,
                        () -> cc.jumpkick.runtime.CompilePlans.compileBuildPlan(
                                session.workingDir(), session.cacheDir(), profile, verbose));
                host.streamSinglePlan(plan, session, writer, result -> ProtoEvents.planFinish(dir, result.success()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
