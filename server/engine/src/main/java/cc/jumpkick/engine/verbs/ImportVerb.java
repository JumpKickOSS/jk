// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class ImportVerb implements HostedVerb {

    private final VerbHost host;

    public ImportVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.IMPORT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("import");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-import-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path baseDir = Path.of(Jsonl.str(requestLine, "baseDir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String report = Jsonl.str(requestLine, "report");
                Session session = Session.defaults()
                        .withWorkingDir(baseDir)
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.CompatPlans.importBuildPlan(
                        Path.of(Jsonl.str(requestLine, "source")),
                        Path.of(Jsonl.str(requestLine, "out")),
                        baseDir,
                        Path.of(Jsonl.str(requestLine, "tmpDir")),
                        Jsonl.bool(requestLine, "force", false),
                        report != null ? Path.of(report) : null,
                        cache,
                        (kind, text) -> host.sendQuiet(writer, EngineProtocol.importNote(dir, kind, text)));
                host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> EngineProtocol.planFinishImport(
                                dir,
                                result.success(),
                                plan.get(cc.jumpkick.runtime.CompatPlans.EXIT).orElse(1),
                                plan.get(cc.jumpkick.runtime.CompatPlans.WARNINGS)
                                        .orElse(0),
                                plan.get(cc.jumpkick.runtime.CompatPlans.ERROR).orElse(null),
                                plan.get(cc.jumpkick.runtime.CompatPlans.DIAG).orElse(null)));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
