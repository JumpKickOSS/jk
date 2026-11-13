// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.MetricsRequest;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            String dirFilter = MetricsRequest.decode(requestLine).dir();
            int n = 0;
            for (BuildMetrics.Entry e : host.metrics().entries()) {
                if (dirFilter != null && !e.dir().isEmpty() && !BuildMetrics.sameDir(dirFilter, e.dir())) {
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
        return JobOutcome.declined();
    }

    private static String metricsEntryJson(BuildMetrics.Entry e) {
        return metricsFields(JsonOut.object().put("type", EngineProtocol.METRICS_ENTRY), e)
                .toString();
    }

    /** The one metrics-row field map — wire lines and {@code GET /api/metrics} both encode here. */
    public static JsonOut metricsFields(JsonOut o, BuildMetrics.Entry e) {
        return o.put("scope", e.scope())
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
                .put("updated", e.updatedMillis());
    }
}
