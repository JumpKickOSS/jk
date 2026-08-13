// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class FormatVerb implements HostedVerb {

    private final VerbHost host;

    public FormatVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.FORMAT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("format");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-format-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                boolean check = Jsonl.bool(requestLine, "check", false);
                String javaStyle = Jsonl.str(requestLine, "javaStyle");
                String kotlinStyle = Jsonl.str(requestLine, "kotlinStyle");
                boolean optimizeImports = Jsonl.bool(requestLine, "optimizeImports", true);
                boolean importOrder = Jsonl.bool(requestLine, "importOrder", true);
                boolean removeUnusedImports = Jsonl.bool(requestLine, "removeUnusedImports", true);
                String rewriteConfig = Jsonl.str(requestLine, "rewriteConfig");
                Session session = host.resolveSession(requestLine, cancelToken, false);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.FormatPlans.formatBuildPlan(
                        session.workingDir(),
                        session.cacheDir(),
                        check,
                        javaStyle,
                        kotlinStyle,
                        optimizeImports,
                        importOrder,
                        removeUnusedImports,
                        rewriteConfig != null ? Path.of(rewriteConfig) : null,
                        (path, status, message, index, total) -> host.sendQuiet(
                                writer, ProtoEvents.formatFile(dir, path, status, message, index, total)));
                host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> ProtoEvents.planFinishFormat(
                                dir,
                                result.success(),
                                plan.get(cc.jumpkick.runtime.FormatPlans.CHANGED)
                                        .orElse(-1),
                                plan.get(cc.jumpkick.runtime.FormatPlans.CLEAN).orElse(-1),
                                plan.get(cc.jumpkick.runtime.FormatPlans.ERRORS).orElse(-1),
                                plan.get(cc.jumpkick.runtime.FormatPlans.TOTAL).orElse(-1),
                                plan.get(cc.jumpkick.runtime.FormatPlans.WORKER_EXIT)
                                        .orElse(-1)));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
