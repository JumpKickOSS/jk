// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.FormatStyles;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.FormatPlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;

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
    public List<String> jobKinds() {
        return List.of("format");
    }

    @Override
    public String decodeJob(JobSpec spec) {
        Path entryDir = Path.of(spec.dir());
        JkBuild entry;
        try {
            entry = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot parse jk.toml in " + entryDir + ": " + e.getMessage());
        }
        // Same style/hygiene precedence as `jk format` without CLI flags: the entry [format]
        // table, then the built-in defaults — one verb, one result across entry points.
        FormatStyles.Resolved styles = FormatStyles.resolve(null, null, null, null, null, null, entry.format());
        return ProtoSession.withTrigger(
                ProtoJobs.formatRequest(
                        spec.dir(),
                        JkDirs.cache().toString(),
                        false,
                        styles.java(),
                        styles.kotlin(),
                        styles.optimizeImports(),
                        styles.importOrder(),
                        styles.removeUnusedImports(),
                        null,
                        false,
                        false),
                "web");
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
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
                BuildPlan plan = FormatPlans.formatBuildPlan(
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
                                plan.get(FormatPlans.CHANGED).orElse(-1),
                                plan.get(FormatPlans.CLEAN).orElse(-1),
                                plan.get(FormatPlans.ERRORS).orElse(-1),
                                plan.get(FormatPlans.TOTAL).orElse(-1),
                                plan.get(FormatPlans.WORKER_EXIT).orElse(-1)));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
