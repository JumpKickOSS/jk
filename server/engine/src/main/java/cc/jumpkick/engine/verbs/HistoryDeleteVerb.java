// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.HistoryDeleteRequest;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

public final class HistoryDeleteVerb implements HostedVerb {

    private final VerbHost host;

    public HistoryDeleteVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.HISTORY_DELETE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("history-delete");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-hist-del-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            String id = HistoryDeleteRequest.decode(requestLine).id();
            boolean deleted = id != null && host.journal().delete(id);
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_DELETED)
                            .put("id", id)
                            .put("deleted", deleted)
                            .toString());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
