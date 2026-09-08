// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.ToolCoordSpec;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.base.ToolPlans;
import cc.jumpkick.tool.ToolEnv;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.ToolResolveRequest;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                ToolResolveRequest req = ToolResolveRequest.decode(requestLine);
                Path cache = Path.of(req.cache());
                String coord = req.coord();
                String bin = req.bin();
                String mainClass = req.mainClass();
                URI repoUrl = req.repoUrl() == null ? null : URI.create(req.repoUrl());
                Files.createDirectories(cache);
                ToolCoordSpec spec = ToolCoordSpec.parse(Objects.requireNonNull(coord, "coord"));
                List<ToolCoordSpec> with =
                        req.with().stream().map(ToolCoordSpec::parse).toList();
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
