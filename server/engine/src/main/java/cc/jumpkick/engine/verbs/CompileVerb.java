// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;

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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String profile = Jsonl.str(requestLine, "profile");
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                Session session = host.resolveSession(requestLine, cancelToken, false);
                java.nio.file.Path entryDir = session.workingDir();
                // Workspace (root or member): the one-orchestrator COMPILE path — compile-only
                // terminal on the selection, prereqs packaged first via the shared cascade
                // (JK-2103). The client mirrors this condition and expects workspace events.
                var wsRoot = cc.jumpkick.config.WorkspaceLocator.findRoot(entryDir);
                if (wsRoot.isPresent()) {
                    cc.jumpkick.model.JkBuild rootBuild =
                            cc.jumpkick.config.JkBuildParser.parse(wsRoot.get().resolve("jk.toml"));
                    if (rootBuild.isWorkspaceRoot()) {
                        java.util.Set<java.nio.file.Path> selected = new java.util.LinkedHashSet<>();
                        for (String d : Jsonl.strArray(requestLine, "moduleDirs")) {
                            if (d != null && !d.isBlank())
                                selected.add(java.nio.file.Path.of(d).toAbsolutePath().normalize());
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
                                        req, host.workspaceListener(writer, wsRoot.get().toString())));
                        host.releaseExclusiveSlot();
                        boolean cancelled =
                                result.cancelled() || host.effectiveCancelled(rid, cancelToken.cancelled());
                        host.accOutcome(rid, result.success() && !cancelled, result.exitCode());
                        if (rid > 0) {
                            if (result.success() && !cancelled) host.finishProgress(rid);
                            host.emitWorkspaceProgress(rid, writer, true);
                        }
                        host.flushTimeline(rid, writer);
                        host.sendQuiet(
                                writer,
                                ProtoEvents.workspaceFinish(
                                        result.success() && !cancelled,
                                        result.exitCode(),
                                        result.errors(),
                                        cancelled));
                        return;
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
    }
}
