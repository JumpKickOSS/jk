// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSelect;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.CompileRequest;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildService;
import cc.jumpkick.runtime.CompilePlans;
import cc.jumpkick.runtime.WorkspaceRequest;
import cc.jumpkick.runtime.WorkspaceResult;
import cc.jumpkick.runtime.WorkspaceSpec;
import cc.jumpkick.util.JkDirs;
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
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        List<String> moduleDirs = List.of();
        if (!spec.modules().isEmpty()) {
            JkBuild entry;
            try {
                entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
            } catch (Exception e) {
                throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
            }
            Set<Path> selected = JobSelect.selected(entryDir, entry, spec.modules());
            if (selected != null) {
                moduleDirs = selected.stream().map(Path::toString).sorted().toList();
            }
        }
        return ProtoSession.withTrigger(
                new CompileRequest(spec.dir(), JkDirs.cache().toString(), null, false, false, false, moduleDirs)
                        .encode(),
                "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                CompileRequest body = CompileRequest.decode(requestLine);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                Path entryDir = session.workingDir();
                // Workspace (root or member): the one-orchestrator COMPILE path — compile-only
                // terminal on the selection, prereqs packaged first via the shared cascade
                // . The client mirrors this condition and expects workspace events.
                var wsRoot = WorkspaceLocator.findRoot(entryDir);
                if (wsRoot.isPresent()) {
                    JkBuild rootBuild = JkBuildParser.parse(wsRoot.get().resolve(ManifestPaths.MANIFEST));
                    if (rootBuild.isWorkspaceRoot()) {
                        Set<Path> selected = new LinkedHashSet<>();
                        List<String> raw = new ArrayList<>();
                        String affected = null;
                        boolean wip = false;
                        for (String d : body.moduleDirs()) {
                            if (d == null || d.isBlank()) continue;
                            if ("affected-wip".equals(d)) wip = true;
                            else if (d.startsWith("affected:")) affected = d.substring("affected:".length());
                            else raw.add(d);
                        }
                        if (!raw.isEmpty() || affected != null || wip) {
                            try {
                                var hit = ModuleSelection.resolveOptional(
                                        entryDir,
                                        rootBuild,
                                        raw.isEmpty() ? null : String.join(",", raw),
                                        affected,
                                        wip);
                                if (hit != null && !hit.ok()) {
                                    host.sendQuiet(
                                            writer,
                                            host.requestFailedLine(
                                                    null, new IllegalArgumentException(hit.errorMessage())));
                                    return JobOutcome.failed(2);
                                }
                                if (hit != null) selected.addAll(hit.moduleDirs());
                            } catch (IllegalArgumentException e) {
                                host.sendQuiet(writer, host.requestFailedLine(null, e));
                                return JobOutcome.failed(2);
                            }
                        }
                        boolean isMember =
                                !BuildGraph.canonicalPath(wsRoot.get()).equals(BuildGraph.canonicalPath(entryDir));
                        if (selected.isEmpty() && isMember) {
                            selected.add(entryDir.toAbsolutePath().normalize());
                        }
                        WorkspaceRequest req = new WorkspaceRequest(
                                        wsRoot.get(),
                                        session.cacheDir(),
                                        session.jdksDir(),
                                        0,
                                        body.profile(),
                                        true,
                                        body.verbose(),
                                        0,
                                        null,
                                        true,
                                        true)
                                .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                                .withSpec(WorkspaceSpec.compile(selected));
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
                // Constructed in-session — see runImage's note on ambient-session capture.
                BuildPlan plan = SessionContext.where(
                        session,
                        () -> CompilePlans.compileBuildPlan(
                                session.workingDir(), session.cacheDir(), body.profile(), body.verbose()));
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
