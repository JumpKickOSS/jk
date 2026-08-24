// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.runtime.ExplainReport;
import cc.jumpkick.runtime.TaskForecast;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Optional;

public final class ExplainVerb implements HostedVerb {

    private final VerbHost host;

    public ExplainVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.EXPLAIN_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("explain");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-explain-";
    }

    @Override
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String entryDirStr = Jsonl.str(requestLine, "dir");
                String cacheStr = Jsonl.str(requestLine, "cache");
                Path entryDir = Path.of(entryDirStr);
                Path cache = Path.of(cacheStr);
                // --redo rides the same session flag as jk build --redo so forecast
                // (all steps RUN) and ETA (build:rebuild history) match the live rebuild path.
                boolean rebuild = Jsonl.bool(requestLine, "rebuild", false);
                boolean force = Jsonl.bool(requestLine, "force", false);
                boolean offline = Jsonl.bool(requestLine, "offline", false);
                boolean verbose = Jsonl.bool(requestLine, "verbose", false);
                boolean skipTests = Jsonl.bool(requestLine, "skipTests", false);
                JkConfig config = new JkConfig(
                        Optional.empty(),
                        Optional.of(offline),
                        Optional.of(rebuild),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(verbose),
                        Optional.empty(),
                        Optional.of(force),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache);
                JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                String etaJdksDirStr = Jsonl.str(requestLine, "jdksDir");
                int workers = Jsonl.intValue(requestLine, "workers", 0); // 0 = auto (bare jk build)
                int maxModuleConcurrency = Jsonl.intValue(requestLine, "maxModuleConcurrency", 0);
                if (maxModuleConcurrency <= 0 && Jsonl.bool(requestLine, "serial", false)) {
                    maxModuleConcurrency = 1;
                }
                ExplainReport report = ExplainReport.compute(
                        entryDir,
                        entryBuild,
                        cache,
                        session,
                        new ExplainReport.Knobs(
                                etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null,
                                Jsonl.str(requestLine, "profile"),
                                workers,
                                maxModuleConcurrency,
                                // Same default as the build surfaces (JK-2213).
                                Jsonl.bool(requestLine, "parallelTests", true),
                                skipTests,
                                verbose));
                ExplainPlan plan = report.plan();
                if (plan.hasErrors()) {
                    for (String err : plan.errors()) {
                        host.sendQuiet(writer, host.requestFailedLine(entryDir.toString(), err));
                    }
                    host.sendQuiet(writer, ProtoReads.explainDone(1, 0));
                    return null;
                }
                for (TaskForecast.Module m : plan.modules()) {
                    String dir = m.dir().toString();
                    host.sendQuiet(
                            writer,
                            ProtoReads.explainModule(
                                    dir,
                                    m.coord(),
                                    m.sourceCount(),
                                    m.testCount(),
                                    m.producesJar(),
                                    m.producesImage()));
                    for (TaskForecast.Task p : m.steps()) {
                        host.sendQuiet(
                                writer,
                                ProtoReads.explainStep(dir, p.name(), p.status().name(), p.text(), p.key()));
                    }
                }
                for (var e : plan.edges().entrySet()) {
                    for (Path dep : e.getValue()) {
                        host.sendQuiet(writer, ProtoReads.explainEdge(e.getKey().toString(), dep.toString()));
                    }
                }
                // Schedule-aware ETA; 0 = fully cached. Same estimateEtaMillis as jk build countdown.
                // fullMillis prices the same graph as a full rebuild (--redo) so explain can report
                // rebuild effort as remaining/full (weight/time, not a count average).
                host.sendQuiet(writer, ProtoEvents.eta(report.etaMillis(), report.fullMillis()));
                host.sendQuiet(
                        writer,
                        ProtoReads.explainDone(
                                plan.maxReadyWidth(), plan.modules().size()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                host.sendQuiet(writer, ProtoReads.explainDone(0, 0));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
