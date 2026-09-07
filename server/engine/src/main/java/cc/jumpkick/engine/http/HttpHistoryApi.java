// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.runtime.ProjectIds;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        String id = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "id");
        if (id != null && !id.isBlank()) {
            var record = journal.recordFile(id);
            if (record.isEmpty()) {
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "no such build: " + id).toString());
                return;
            }
            HttpResponses.sendJson(
                    exchange,
                    200,
                    redactRecordJson(
                            enrichHistoryJson(Files.readString(record.get(), StandardCharsets.UTF_8)),
                            new HashMap<>()));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // HEAD before the journal read: headers are the whole answer, so it must not pay for a
        // 200-row scan it will never send.
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        // Single pass, no parse, streamed out: the kind gate rides into the journal as a lexical
        // filter so exactly one page of survivors is read, and the raw loader never builds a
        // record graph. The "does this row even need enrichment" check is lexical too, so a
        // finished, id-stamped record — the overwhelming majority — is written through verbatim;
        // only in-flight or unstamped rows pay MiniJson. The response is chunked straight to the
        // socket instead of join-then-copy.
        List<String> raw = journal.rawRecords(HISTORY_LIST_LIMIT, HttpHistoryApi::isBuildLikeHistoryJson);
        exchange.sendResponseHeaders(200, 0);
        Map<String, SecretRedactor> redactors = new HashMap<>();
        try (var out = exchange.getResponseBody()) {
            out.write('[');
            int sent = 0;
            for (String r : raw) {
                String part = needsEnrichment(r) ? enrichHistoryJson(r) : r;
                part = redactRecordJson(part, redactors);
                if (sent > 0) out.write(',');
                out.write(part.getBytes(StandardCharsets.UTF_8));
                sent++;
            }
            out.write(']');
        }
    }

    /**
     * Redact a batch of raw journal records with one per-dir redactor cache. This endpoint
     * streams record bodies verbatim, so {@code .env} secrets in message/stack are stripped here.
     * MCP journal suppliers ride this so {@code jk_history view=full} / {@code jk_diagnostics}
     * never serve those secrets.
     */
    static List<String> redactRecords(List<String> raw) {
        Map<String, SecretRedactor> cache = new HashMap<>();
        List<String> out = new ArrayList<>(raw.size());
        for (String r : raw) out.add(redactRecordJson(r, cache));
        return out;
    }

    static String redactRecordJson(String raw, Map<String, SecretRedactor> cache) {
        try {
            String dir = scanStringField(raw, "dir");
            String key = dir == null ? "" : dir;
            SecretRedactor redactor = cache.computeIfAbsent(key, k -> {
                try {
                    return EventRedaction.redactorFor(dir);
                } catch (RuntimeException e) {
                    EventRedaction.warnFailOpen(e);
                    return SecretRedactor.none();
                }
            });
            // The document is escaped JSON: match escaped renderings too, or a secret containing
            // a quote/backslash/control char streams through verbatim.
            return redactor.forEscapedJson().redact(raw);
        } catch (RuntimeException e) {
            EventRedaction.warnFailOpen(e);
            return raw;
        }
    }

    /** True when a journal JSON blob's {@code kind} is a durable project build (lexical scan). */
    private static boolean isBuildLikeHistoryJson(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String kind = scanStringField(raw, "kind");
        return kind != null && BuildHistoryKinds.isBuildLike(kind);
    }

    /**
     * True when {@link #enrichHistoryJson} could change this row: it is in-flight
     * ({@code "running": true}) or lacks a non-blank {@code projectId}. Lexical and
     * conservative — a false positive costs one parse, a finished stamped row costs zero.
     */
    private static boolean needsEnrichment(String raw) {
        String pid = scanStringField(raw, "projectId");
        if (pid == null || pid.isBlank()) return true;
        return scanBooleanTrue(raw, "running");
    }

    /**
     * The first {@code "name"} key's string value, scanned without parsing, or {@code null} when
     * the key is absent or its value is not a string. Journal records write these keys top-level
     * ahead of any free-text payload (see {@code journal.Json}), so the first occurrence is the
     * real key in both compact and pretty output. Escapes are left as-is — callers only read
     * machine-written identifier-shaped values.
     */
    private static @Nullable String scanStringField(String raw, String name) {
        int i = raw.indexOf('"' + name + '"');
        if (i < 0) return null;
        int j = i + name.length() + 2;
        while (j < raw.length() && (raw.charAt(j) == ':' || Character.isWhitespace(raw.charAt(j)))) j++;
        if (j >= raw.length() || raw.charAt(j) != '"') return null;
        int end = j + 1;
        while (end < raw.length()) {
            char c = raw.charAt(end);
            if (c == '\\') end += 2;
            else if (c == '"') break;
            else end++;
        }
        return end < raw.length() ? raw.substring(j + 1, end) : null;
    }

    /** True when any {@code "name"} key is lexically followed by {@code true} (never misses the top-level one). */
    private static boolean scanBooleanTrue(String raw, String name) {
        String key = '"' + name + '"';
        for (int i = raw.indexOf(key); i >= 0; i = raw.indexOf(key, i + 1)) {
            int j = i + key.length();
            while (j < raw.length() && (raw.charAt(j) == ':' || Character.isWhitespace(raw.charAt(j)))) j++;
            if (raw.startsWith("true", j)) return true;
        }
        return false;
    }

    /**
     * Attach live mid-flight state to an in-flight journal record so the dashboard can rebind SSE
     * after refresh with TUI-parity progress / elapsed / phases. Finished records are returned
     * unchanged aside from projectId backfill.
     */
    private String enrichHistoryJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            Object parsed = MiniJson.parse(raw);
            if (!(parsed instanceof Map<?, ?> m0)) return raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) m0;
            // Durable project id for dashboard routing. Rows without projectId resolve through
            // the process memo — a bare resolve is two TOML parses plus up to three git
            // subprocesses per row. resolve() recovers an existing identity.toml id before
            // hashing, so a dead checkout's rows route to its recorded project home instead of
            // minting a fresh unknown:unknown id that 404s on the detail page.
            if (!(m.get("projectId") instanceof String pid) || pid.isBlank()) {
                if (m.get("dir") instanceof String dir && !dir.isBlank()) {
                    String resolved = ProjectIds.idOf(dir);
                    if (resolved != null) m.put("projectId", resolved);
                }
            }
            if (!Boolean.TRUE.equals(m.get("running"))) {
                return MiniJson.write(m);
            }
            HttpLive.Run match = matchLiveRun(m);
            if (match == null) return MiniJson.write(m);
            m.put("jid", match.requestId());
            if (match.startedAt() > 0) {
                m.put("startedAt", match.startedAt());
                // Engine "now" beside engine startedAt — skew-free elapsed for the SPA.
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
            return MiniJson.write(m);
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
            // running" and "failed" — the SPA guessed from task statuses and
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
     * Package-private for direct unit testing of the rebind rules.
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
        // one's stream.
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

    private static long liveLong(@Nullable Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    /**
     * {@code GET /api/history/artifact?id=…&name=…} — a snapshot file served as plain text.
     * {@code name} is whitelisted by the journal.
     */
    void handleHistoryArtifact(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String id = HttpQuery.queryParamLenient(query, "id");
        String name = HttpQuery.queryParamLenient(query, "name");
        var artifact = id == null || name == null ? Optional.<Path>empty() : journal.artifact(id, name);
        if (artifact.isEmpty()) {
            HttpResponses.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such artifact").toString());
            return;
        }
        HttpResponses.sendText(exchange, 200, Files.readString(artifact.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@code DELETE /api/history?id=…} — remove one entry. DELETE is a mutation, so the bearer
     * token is required even on loopback (CSRF defense).
     */
    void handleHistoryDelete(HttpExchange exchange) throws IOException {
        String id = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "id");
        if (id == null || !journal.delete(id)) {
            HttpResponses.sendJson(
                    exchange,
                    404,
                    JsonOut.object().put("error", "no such build").toString());
            return;
        }
        HttpResponses.sendJson(
                exchange, 200, JsonOut.object().put("deleted", true).toString());
    }
}
