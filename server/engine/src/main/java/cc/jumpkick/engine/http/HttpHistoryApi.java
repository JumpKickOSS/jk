// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.journal.BuildJournal;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** {@code /api/history} list, live enrichment, artifact, and delete. */
final class HttpHistoryApi {

    /** Cap on the {@code GET /api/history} list — a picker of recent runs, not a full dump. */
    private static final int HISTORY_LIST_LIMIT = 200;

    private final BuildJournal journal;
    private final Supplier<List<HttpLive.Run>> liveRuns;

    HttpHistoryApi(BuildJournal journal, Supplier<List<HttpLive.Run>> liveRuns) {
        this.journal = journal;
        this.liveRuns = liveRuns;
    }

    /**
     * {@code GET /api/history} — the persisted build journal (survives engine restarts). With no
     * {@code ?id=}, a JSON array of the newest entries' full records; with {@code ?id=}, that one
     * entry's {@code record.json}. Finished records stream verbatim (a cheap {@code "running":true}
     * pre-check skips the parse); in-flight ones are MiniJson-parsed once to attach live
     * {@code requestId}/{@code progress}.
     */
    void handleHistory(HttpExchange exchange) throws IOException {
        String id = HttpEngineServer.decode(
                HttpEngineServer.queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id != null && !id.isBlank()) {
            var record = journal.recordFile(id);
            if (record.isEmpty()) {
                HttpEngineServer.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "no such build: " + id).toString());
                return;
            }
            HttpEngineServer.sendJson(
                    exchange, 200, enrichHistoryJson(Files.readString(record.get(), StandardCharsets.UTF_8)));
            return;
        }
        List<String> raw = journal.rawRecords(Math.max(HISTORY_LIST_LIMIT * 4, HISTORY_LIST_LIMIT));
        List<String> parts = new ArrayList<>(HISTORY_LIST_LIMIT);
        for (String r : raw) {
            if (!isBuildLikeHistoryJson(r)) continue;
            parts.add(enrichHistoryJson(r));
            if (parts.size() >= HISTORY_LIST_LIMIT) break;
        }
        HttpEngineServer.sendJson(exchange, 200, "[" + String.join(",", parts) + "]");
    }

    /** True when a journal JSON blob's {@code kind} is a durable project build. */
    private static boolean isBuildLikeHistoryJson(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            Object parsed = cc.jumpkick.plugin.protocol.MiniJson.parse(raw);
            if (!(parsed instanceof Map<?, ?> m)) return false;
            Object k = m.get("kind");
            return k instanceof String s && cc.jumpkick.engine.BuildHistoryKinds.isBuildLike(s);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Attach live mid-flight state to an in-flight journal record so the dashboard can rebind SSE
     * after refresh with TUI-parity progress / elapsed / phases. Finished records are returned
     * unchanged aside from projectId backfill.
     */
    private String enrichHistoryJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            Object parsed = cc.jumpkick.plugin.protocol.MiniJson.parse(raw);
            if (!(parsed instanceof Map<?, ?> m0)) return raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) m0;
            // Durable project id for dashboard routing (JK-1727+). New rows are stamped at
            // journal.begin (JK-1750); only legacy rows resolve here, through the process memo —
            // a bare resolve is two TOML parses plus up to three git subprocesses per row.
            // resolve() recovers an existing identity.toml id before hashing (JK-1794), so a
            // dead checkout's rows route to its recorded project home instead of minting a
            // fresh unknown:unknown id that 404s on the detail page.
            if (!(m.get("projectId") instanceof String pid) || pid.isBlank()) {
                if (m.get("dir") instanceof String dir && !dir.isBlank()) {
                    String resolved = cc.jumpkick.runtime.ProjectIds.idOf(dir);
                    if (resolved != null) m.put("projectId", resolved);
                }
            }
            if (!Boolean.TRUE.equals(m.get("running"))) {
                return cc.jumpkick.plugin.protocol.MiniJson.write(m);
            }
            HttpLive.Run match = matchLiveRun(m);
            if (match == null) return cc.jumpkick.plugin.protocol.MiniJson.write(m);
            m.put("requestId", match.requestId());
            m.put("jid", match.requestId());
            if (match.startedAt() > 0) {
                m.put("startedAt", match.startedAt());
                // Engine "now" beside engine startedAt — skew-free elapsed for the SPA (JK-1839).
                m.put("serverNow", System.currentTimeMillis());
            }
            if (!Double.isNaN(match.progress())) m.put("progress", match.progress());
            if (match.remainingMs() >= 0) m.put("remainingMs", match.remainingMs());
            if (match.r0Ms() > 0) m.put("R0", match.r0Ms());
            if (match.denominator() > 0) {
                m.put("numerator", match.numerator());
                m.put("denominator", match.denominator());
            }
            // Prefer live phase chains (journal stub is empty until complete).
            if (!match.modules().isEmpty()) {
                m.put("modules", liveModulesJson(match.modules()));
                m.put("tasks", List.of());
            } else if (!match.tasks().isEmpty()) {
                m.put("tasks", liveTasksJson(match.tasks()));
            }
            return cc.jumpkick.plugin.protocol.MiniJson.write(m);
        } catch (RuntimeException e) {
            return raw; // best-effort — never break the list for a bad row
        }
    }

    private static List<Object> liveModulesJson(List<HttpLive.Module> modules) {
        List<Object> out = new ArrayList<>(modules.size());
        for (HttpLive.Module mod : modules) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("dir", mod.dir() == null ? "" : mod.dir());
            if (mod.coord() != null && !mod.coord().isBlank()) mm.put("coord", mod.coord());
            // Explicit lifecycle bit: success=false alone was ambiguous between "still
            // running" and "failed" (JK-1846) — the SPA guessed from task statuses and
            // misclassified module-level failures with no FAIL task.
            mm.put("finished", mod.finished());
            mm.put("success", mod.finished() && mod.success());
            mm.put("millis", mod.millis());
            if (mod.finished()) mm.put("didWork", mod.didWork());
            mm.put("tasks", liveTasksJson(mod.tasks()));
            out.add(mm);
        }
        return out;
    }

    private static List<Object> liveTasksJson(List<HttpLive.Task> tasks) {
        List<Object> out = new ArrayList<>(tasks.size());
        for (HttpLive.Task t : tasks) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", t.name() == null ? "?" : t.name());
            sm.put("stage", t.stage() == null ? "" : t.stage());
            sm.put("status", t.status() == null ? "RUN" : t.status());
            sm.put("millis", t.millis());
            out.add(sm);
        }
        return out;
    }

    /**
     * Package-private for direct unit testing of the rebind rules (JK-1522).
     */
    HttpLive.@Nullable Run matchLiveRun(Map<String, Object> rec) {
        List<HttpLive.Run> live = liveRuns.get();
        if (live == null || live.isEmpty()) return null;
        long buildNumber = liveLong(rec.get("buildNumber"));
        String dir = rec.get("dir") instanceof String s ? s : null;
        String id = rec.get("id") instanceof String s ? s : null;
        for (HttpLive.Run h : live) {
            boolean sameRun = buildNumber > 0 && buildNumber == h.buildNumber() && dir != null && dir.equals(h.dir());
            boolean sameJournal = id != null && h.journalId() != null && id.equals(h.journalId());
            if (sameRun || sameJournal) return h;
        }
        // Single live job with matching dir — only for records that carry no buildNumber (older
        // stubs). A record WITH a buildNumber that failed the strict match is a different run
        // (e.g. a stale running stub from a crashed engine) and must not rebind to the current
        // one's stream (JK-1522).
        if (dir != null && buildNumber <= 0) {
            HttpLive.Run only = null;
            for (HttpLive.Run h : live) {
                if (dir.equals(h.dir())) {
                    if (only != null) return null; // ambiguous
                    only = h;
                }
            }
            return only;
        }
        return null;
    }

    private static long liveLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    /**
     * {@code GET /api/history/artifact?id=…&name=…} — a snapshot file served as plain text.
     * {@code name} is whitelisted by the journal.
     */
    void handleHistoryArtifact(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        var artifact = journal.artifact(
                HttpEngineServer.decode(HttpEngineServer.queryParam(query, "id")),
                HttpEngineServer.decode(HttpEngineServer.queryParam(query, "name")));
        if (artifact.isEmpty()) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such artifact").toString());
            return;
        }
        HttpEngineServer.sendText(exchange, 200, Files.readString(artifact.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@code DELETE /api/history?id=…} — remove one entry. DELETE is a mutation, so the bearer
     * token is required even on loopback (CSRF defense).
     */
    void handleHistoryDelete(HttpExchange exchange) throws IOException {
        String id = HttpEngineServer.decode(
                HttpEngineServer.queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id == null || !journal.delete(id)) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such build").toString());
            return;
        }
        HttpEngineServer.sendJson(
                exchange, 200, JsonOut.object().put("deleted", true).toString());
    }
}
