// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ToolResolveVerb implements HostedVerb {

    private final VerbHost host;

    public ToolResolveVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TOOL_RESOLVE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("tool");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-tool-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String coord = Jsonl.str(requestLine, "coord");
                String bin = Jsonl.str(requestLine, "bin");
                String mainClass = Jsonl.str(requestLine, "mainClass");
                URI repoUrl = LockVerb.repoUrlOf(requestLine);
                Files.createDirectories(cache);
                cc.jumpkick.model.ToolCoordSpec spec = cc.jumpkick.model.ToolCoordSpec.parse(coord);
                List<cc.jumpkick.model.ToolCoordSpec> with = Jsonl.strArray(requestLine, "with").stream()
                        .map(cc.jumpkick.model.ToolCoordSpec::parse)
                        .toList();
                Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Plain g:a[:v] label — coordinate colorization is a client-side concern.
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.ToolPlans.resolveBuildPlan(
                        spec, with, bin, mainClass, repoUrl, cache, coord);
                host.streamSinglePlan(plan, session, writer, result -> {
                    cc.jumpkick.tool.ToolEnv env =
                            plan.get(cc.jumpkick.runtime.ToolPlans.TOOL_ENV).orElse(null);
                    return ProtoSession.planFinishTool(
                            dir,
                            result.success(),
                            env != null ? env.primary().toGav() : null,
                            env != null ? env.mainClass() : null,
                            env != null
                                    ? env.classpath().stream()
                                            .map(Path::toString)
                                            .toList()
                                    : List.of());
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
