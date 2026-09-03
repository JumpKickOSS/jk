// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.ExplainReport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.BufferedWriter;
import java.nio.file.Path;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                ExplainRequest req = ExplainRequest.decode(requestLine);
                Path entryDir = Path.of(req.dir());
                Path cache = Path.of(req.cache());
                // --redo rides the same session flag as jk build --redo so forecast
                // (all steps RUN) and ETA (build:rebuild history) match the live rebuild path.
                JkConfig config = JkConfig.empty().withRebuild(req.rebuild()).withVerbose(req.verbose());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        // The client's resolved test selection, exactly as WorkspaceBuildVerb
                        // applies it. Without it this session carried TestSelection.DEFAULT, whose
                        // empty exclude-tag list is itself a stamp input — so the forecast computed
                        // a run-tests key no build had ever stored and called all 30 modules dirty.
                        .withTestSelection(req.selection());
                JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                String etaJdksDirStr = req.jdksDir();
                int maxModuleConcurrency = req.maxModuleConcurrency();
                if (maxModuleConcurrency <= 0 && req.serial()) {
                    maxModuleConcurrency = 1;
                }
                ExplainReport report = ExplainReport.compute(
                        entryDir,
                        entryBuild,
                        cache,
                        session,
                        new ExplainReport.Knobs(
                                etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null,
                                req.profile(),
                                req.workers(),
                                maxModuleConcurrency,
                                req.parallelTests(),
                                req.skipTests(),
                                req.verbose()));
                ExplainPlan plan = report.plan();
                if (plan.hasErrors()) {
                    for (String err : plan.errors()) {
                        host.sendQuiet(writer, host.requestFailedLine(entryDir.toString(), err));
                    }
                    host.sendQuiet(writer, ProtoReads.explainDone(1, 0));
                    return JobOutcome.declined();
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
        return JobOutcome.declined();
    }
}
