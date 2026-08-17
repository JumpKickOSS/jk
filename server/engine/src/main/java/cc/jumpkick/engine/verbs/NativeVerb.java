// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin adapter: Graal homes + native-eligible module dirs become a {@link WorkspaceRequest}
 * ({@code target=NATIVE}) on the shared {@link BuildService#buildWorkspace} path.
 */
public final class NativeVerb implements HostedVerb {

    private final VerbHost host;

    public NativeVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.NATIVE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.workspace("native");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-native-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
            Path cache = Path.of(Jsonl.str(requestLine, "cache"));
            String jdksDirStr = Jsonl.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            String mainClass = Jsonl.str(requestLine, "mainClass");
            boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
            boolean verbose = Jsonl.bool(requestLine, "verbose", false);
            List<String> extraArgs = Jsonl.strArray(requestLine, "extraArgs");
            Map<Path, Path> graalByDir = new HashMap<>();
            Jsonl.strMap(requestLine, "graalHomes").forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
            Set<Path> selected = new LinkedHashSet<>();
            for (String d : Jsonl.strArray(requestLine, "moduleDirs")) {
                if (d != null && !d.isBlank())
                    selected.add(Path.of(d).toAbsolutePath().normalize());
            }
            if (selected.isEmpty()) selected.addAll(graalByDir.keySet());

            JkBuild root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            Session session =
                    host.resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);
            WorkspaceRequest req = new WorkspaceRequest(
                            entryDir, root, cache, jdksDir, 0, null, skipTests, verbose, 0, null, true, true)
                    .withVariant(ProtoSession.variantOf(requestLine), ProtoSession.clientEnvOf(requestLine))
                    .withSpec(WorkspaceSpec.nativeImage(selected, graalByDir, mainClass, extraArgs));

            long rid = host.eventRequestId();
            if (rid > 0) host.putProgressRoot(rid, entryDir.toString());
            WorkspaceResult result = SessionContext.where(
                    session,
                    () -> BuildService.buildWorkspace(req, host.workspaceListener(writer, entryDir.toString())));
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
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(Jsonl.str(requestLine, "dir"), e));
        }
    }
}
