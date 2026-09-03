// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.ToolPlans;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String coord = Jsonl.str(requestLine, "coord");
                String bin = Jsonl.str(requestLine, "bin");
                String mainClass = Jsonl.str(requestLine, "mainClass");
                URI repoUrl = LockVerb.repoUrlOf(requestLine);
                Files.createDirectories(cache);
                ToolCoordSpec spec = ToolCoordSpec.parse(coord);
                List<ToolCoordSpec> with = Jsonl.strArray(requestLine, "with").stream()
                        .map(ToolCoordSpec::parse)
                        .toList();
                Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                // Plain g:a[:v] label — coordinate colorization is a client-side concern.
                BuildPlan plan = ToolPlans.resolveBuildPlan(spec, with, bin, mainClass, repoUrl, cache, coord);
                return host.streamSinglePlan(plan, session, writer, result -> {
                    ToolEnv env = plan.get(ToolPlans.TOOL_ENV).orElse(null);
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
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
