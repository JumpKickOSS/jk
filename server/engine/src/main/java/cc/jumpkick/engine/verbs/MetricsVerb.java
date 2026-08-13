// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.http.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.runtime.BuildMetrics;
import java.io.BufferedWriter;

public final class MetricsVerb implements HostedVerb {

    private final VerbHost host;

    public MetricsVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.METRICS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("metrics");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-metrics-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String dirFilter = Jsonl.str(requestLine, "dir");
            int n = 0;
            for (BuildMetrics.Entry e : BuildMetrics.load(host.metricsFile()).entries()) {
                // Project rows are stored as bare dir and dirty-count shapes (dir#dN). Match
                // the project's base path so `jk status` sees the folded project tier.
                if (dirFilter != null && !e.dir().isEmpty() && !BuildMetrics.sameBaseDir(dirFilter, e.dir())) {
                    continue;
                }
                host.send(writer, metricsEntryJson(e));
                n++;
            }
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.METRICS_DONE)
                            .put("count", n)
                            .toString());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }

    private static String metricsEntryJson(BuildMetrics.Entry e) {
        return JsonOut.object()
                .put("type", EngineProtocol.METRICS_ENTRY)
                .put("scope", e.scope())
                .put("kind", e.kind())
                .put("dir", e.dir())
                .put("coord", e.coord())
                .put("task", e.step())
                .put("okCount", e.ok().count())
                .put("okTotalMillis", e.ok().totalMillis())
                .put("okMinMillis", e.ok().minMillis())
                .put("okMaxMillis", e.ok().maxMillis())
                .put("okAvgMillis", e.ok().avgMillis())
                .put("failCount", e.failed().count())
                .put("failTotalMillis", e.failed().totalMillis())
                .put("failMinMillis", e.failed().minMillis())
                .put("failMaxMillis", e.failed().maxMillis())
                .put("cancelledCount", e.cancelled().count())
                .put("cancelledTotalMillis", e.cancelled().totalMillis())
                .put("cancelledMinMillis", e.cancelled().minMillis())
                .put("cancelledMaxMillis", e.cancelled().maxMillis())
                .put("updated", e.updatedMillis())
                .toString();
    }
}
