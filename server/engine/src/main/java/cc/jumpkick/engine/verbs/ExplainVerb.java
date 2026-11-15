// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.ReleaseSession;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExplainRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.List;
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
                Session session = ReleaseSession.sessionOf(requestLine, cancelToken);
                // Announce the bootstrap probe the same way the workspace build does, from the
                // same decision — the client renders "Calibrating host…", it never predicts it.
                Forecasts.Forecast forecast = Forecasts.forecast(
                        req,
                        session,
                        (stage, done, total, label) ->
                                host.sendQuiet(writer, ProtoEvents.preflight(stage, done, total, label)));
                if (forecast instanceof Forecasts.Forecast.Refused(List<String> errors)) {
                    for (String err : errors) {
                        host.sendQuiet(writer, host.requestFailedLine(String.valueOf(req.dir()), err));
                    }
                    host.sendQuiet(writer, ProtoReads.explainDone(1, 0));
                    return JobOutcome.declined();
                }
                ExplainReport report = ((Forecasts.Forecast.Ready) forecast).report();
                ExplainPlan plan = report.plan();
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
                                    m.producesImage(),
                                    m.reason()));
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
                host.sendQuiet(
                        writer,
                        ProtoEvents.eta(
                                report.etaMillis(),
                                report.fullMillis(),
                                report.lowMillis(),
                                report.highMillis(),
                                report.confidence()));
                host.sendQuiet(
                        writer,
                        ProtoReads.explainDone(
                                plan.maxReadyWidth(),
                                plan.modules().size(),
                                report.criticalPath().stream()
                                        .map(Path::toString)
                                        .toList()));
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
