// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.http.JsonOut;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.util.Objects;
import java.util.Optional;

public final class HistoryShowVerb implements HostedVerb {

    private final VerbHost host;

    public HistoryShowVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.HISTORY_SHOW_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("history-show");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-hist-show-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String id = Jsonl.str(requestLine, "id");
            Optional<BuildRecord> found =
                    id == null ? Optional.empty() : host.journal().get(id);
            if (found.isEmpty()) {
                host.send(
                        writer,
                        JsonOut.object()
                                .put("type", EngineProtocol.ERROR)
                                .put("code", EngineProtocol.ERR_REQUEST_FAILED)
                                .put("message", "no such build: " + id)
                                .toString());
                return;
            }
            BuildRecord r = found.get();
            BuildRecord.Tests t = r.tests();
            BuildRecord.CacheBenefit b = r.benefit();
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_RECORD)
                            .put("id", r.id())
                            .put("kind", r.kind())
                            .put("dir", r.dir())
                            .put("coord", r.coord())
                            .put("startedAt", r.startedAt())
                            .put("finishedAt", r.finishedAt())
                            .put("millis", r.millis())
                            .put("success", r.success())
                            .put("cancelled", r.cancelled())
                            .put("exitCode", r.exitCode())
                            .put("jkVersion", r.jkVersion())
                            .put("testsTotal", t != null ? t.total() : -1)
                            .put("testsSucceeded", t != null ? t.succeeded() : -1)
                            .put("testsFailed", t != null ? t.failed() : -1)
                            .put("testsSkipped", t != null ? t.skipped() : -1)
                            .put("savedMillis", b != null ? b.savedMillis() : -1)
                            .put("estimatedUncachedMillis", b != null ? b.estimatedUncachedMillis() : -1)
                            .put("coveredSkips", b != null ? b.coveredSkips() : -1)
                            .put("totalSkips", b != null ? b.totalSkips() : -1)
                            .toString());
            int stepCount = 0;
            for (BuildRecord.Module m : r.modules()) {
                host.send(
                        writer,
                        JsonOut.object()
                                .put("type", EngineProtocol.HISTORY_MODULE)
                                .put("coord", m.coord())
                                .put("dir", m.dir())
                                .put("success", m.success())
                                .put("exitCode", m.exitCode())
                                .put("millis", m.millis())
                                .toString());
                // Each module's own step chain, tagged with the module so the CLI can group them.
                String label = m.coord() != null ? m.coord() : m.dir();
                for (BuildRecord.Task p : m.steps()) {
                    host.send(writer, stepLine(p, label));
                    stepCount++;
                }
            }
            // Single-plan builds carry their steps at the record's top level (no module rows).
            for (BuildRecord.Task p : r.steps()) {
                host.send(writer, stepLine(p, null));
                stepCount++;
            }
            // Defense in depth (JK-1963): records persisted before write-time redaction (JK-1878)
            // may carry .env secrets in message/stack — re-redact on replay against the record's
            // own dir. One redactor per record; SecretRedactor memoizes by value-set.
            cc.jumpkick.config.SecretRedactor redactor = replayRedactor(r.dir());
            for (BuildRecord.Diag d : r.diagnostics()) {
                host.send(writer, cc.jumpkick.engine.journal.JournalWriter.historyDiagLine(redactDiag(redactor, d)));
            }
            host.send(
                    writer,
                    JsonOut.object()
                            .put("type", EngineProtocol.HISTORY_DONE)
                            .put(
                                    "count",
                                    r.modules().size()
                                            + stepCount
                                            + r.diagnostics().size())
                            .toString());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }

    private static String stepLine(BuildRecord.Task p, String module) {
        return JsonOut.object()
                .put("type", EngineProtocol.HISTORY_TASK)
                .put("module", module)
                .put("name", p.name())
                .put("status", p.status())
                .put("millis", p.millis())
                .toString();
    }

    private static cc.jumpkick.config.SecretRedactor replayRedactor(String dir) {
        try {
            return cc.jumpkick.engine.listen.EventRedaction.redactorFor(dir);
        } catch (RuntimeException e) {
            return cc.jumpkick.config.SecretRedactor.none();
        }
    }

    private static BuildRecord.Diag redactDiag(cc.jumpkick.config.SecretRedactor r, BuildRecord.Diag d) {
        String message = redactSafe(r, d.message());
        String stack = redactSafe(r, d.stack());
        if (Objects.equals(message, d.message()) && Objects.equals(stack, d.stack())) {
            return d;
        }
        return new BuildRecord.Diag(
                d.severity(),
                d.dir(),
                d.step(),
                d.code(),
                message,
                d.test(),
                d.exceptionClass(),
                d.module(),
                d.engine(),
                d.className(),
                d.method(),
                stack,
                d.file(),
                d.line(),
                d.snippetStart(),
                d.snippet(),
                d.worker());
    }

    private static String redactSafe(cc.jumpkick.config.SecretRedactor r, String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return r.redact(text);
        } catch (RuntimeException e) {
            return text;
        }
    }
}
