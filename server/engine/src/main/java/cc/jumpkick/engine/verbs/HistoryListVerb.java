// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.BuildHistoryKinds;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.util.List;

public final class HistoryListVerb implements HostedVerb {

    private final VerbHost host;

    public HistoryListVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.HISTORY_LIST_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("history-list");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-hist-list-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            int limit = Math.max(1, Jsonl.intValue(requestLine, "limit", 200));
            // Truncate in the journal (synthetic fixtures are already filtered there) rather
            // than materialising every record on disk and then dropping most of them.
            // Oversample then keep only build-like kinds so lock/format/etc. never dilute history.
            List<BuildRecord> records = host.journal().list(Math.max(limit * 4, limit));
            int emitted = 0;
            for (BuildRecord r : records) {
                if (!BuildHistoryKinds.isBuildLike(r.kind())) continue;
                if (emitted >= limit) break;
                emitted++;
                BuildRecord.Tests t = r.tests();
                BuildRecord.CacheBenefit b = r.benefit();
                int failedModules =
                        (int) r.modules().stream().filter(m -> !m.success()).count();
                // Live progress + jid for in-flight rows — match by build number + project dir.
                int progressPct = -1;
                long jid = 0;
                if (r.running()) {
                    for (InFlightBuilds.Hold h : host.inFlightBuilds().list()) {
                        boolean sameRun = r.buildNumber() > 0
                                && r.buildNumber() == h.buildNumber()
                                && r.dir() != null
                                && r.dir().equals(h.dir());
                        boolean sameLocator = h.journalId() != null
                                && (h.journalId().equals(Long.toString(r.buildNumber()))
                                        || h.journalId().equals(r.id()));
                        if (sameRun || sameLocator) {
                            jid = h.requestId();
                            Double p = host.lastProgress(h.requestId());
                            if (p != null && !Double.isNaN(p)) progressPct = (int) Math.round(p);
                            break;
                        }
                    }
                }
                long elapsed =
                        r.running() && r.startedAt() > 0 ? Math.max(0, host.nowMillis() - r.startedAt()) : r.millis();
                var entry = JsonOut.object()
                        .put("type", EngineProtocol.HISTORY_ENTRY)
                        .put("id", r.id())
                        .put("buildNumber", r.buildNumber())
                        .put("kind", r.kind())
                        .put("dir", r.dir())
                        .put("coord", r.coord())
                        .put("startedAt", r.startedAt())
                        .put("finishedAt", r.finishedAt())
                        .put("millis", elapsed)
                        .put("success", r.success())
                        .put("cancelled", r.cancelled())
                        .put("running", r.running())
                        .put("exitCode", r.exitCode())
                        .put("testsTotal", t != null ? t.total() : -1)
                        .put("testsFailed", t != null ? t.failed() : -1)
                        .put("moduleCount", r.modules().size())
                        .put("failedModules", failedModules)
                        .put("savedMillis", b != null ? b.savedMillis() : -1)
                        .put("estimatedUncachedMillis", b != null ? b.estimatedUncachedMillis() : -1);
                if (jid > 0) {
                    entry = entry.put("jid", jid).put("requestId", jid);
                }
                if (progressPct >= 0) entry = entry.put("progress", progressPct);
                host.send(writer, entry.toString());
            }
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_DONE)
                            .put("count", emitted)
                            .toString());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
