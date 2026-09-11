// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                ExplainRequest req = ExplainRequest.decode(requestLine);
                Path entryDir = Path.of(req.dir());
                Path cache = Path.of(req.cache());
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve(ManifestPaths.MANIFEST));
                String etaJdksDirStr = req.jdksDir();
                int maxModuleConcurrency = req.maxModuleConcurrency();
                if (maxModuleConcurrency <= 0 && req.serial()) {
                    maxModuleConcurrency = 1;
                }
                Path etaJdksDir = etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null;
                // Announce the bootstrap probe the same way the workspace build does, from the
                // same decision — the client renders "Calibrating host…", it never predicts it.
                Calibration.ensureAnnounced(
                        etaJdksDir,
                        (stage, done, total, label) ->
                                host.sendQuiet(writer, ProtoEvents.preflight(stage, done, total, label)));
                ExplainReport report = ExplainReport.compute(
                        entryDir,
                        entryBuild,
                        cache,
                        session,
                        new ExplainReport.Knobs(req.profile(), maxModuleConcurrency, req.skipTests()));
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
