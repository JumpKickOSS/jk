// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.savings.Savings;
import cc.jumpkick.engine.savings.SavingsLedger;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.StatsRequest;
import java.io.BufferedWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** {@code jk stats}: one {@code stats} line per {@link Savings} row, then {@code stats-done}. */
public final class StatsVerb implements HostedVerb {

    private final VerbHost host;

    public StatsVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.STATS_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("stats");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-stats-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            long since = StatsRequest.decode(requestLine).since();
            List<Savings> rows =
                    SavingsLedger.read(host.journal(), host.sessionLog(), host.agentUsage(), since, host.nowMillis());
            for (Savings row : rows) host.send(writer, line(row));
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.STATS_DONE)
                            .put("count", rows.size())
                            .toString());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }

    /** {@code row} as one {@code stats} wire line: the {@link Savings#toMap} fields after its type. */
    static String line(Savings row) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", EngineProtocol.STATS);
        fields.putAll(row.toMap());
        return JsonOut.rawObject(fields).toString();
    }
}
