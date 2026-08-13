// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.JkHttpConfig;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Read-tier status surfaces plus POST build/cancel. */
final class HttpReadApi {

    /** Directory listings above this are truncated — a picker, not a filesystem dump. */
    private static final int MAX_FS_ENTRIES = 400;

    private final JkHttpConfig config;
    private final Path webRoot;
    private final Path logFile;
    private final Supplier<StatusSnapshot> status;
    private final EngineHttpJobs jobs;
    private final Supplier<List<cc.jumpkick.runtime.BuildMetrics.Entry>> metrics;
    private final Supplier<CacheSnapshot> cache;
    private final Supplier<String> url;

    HttpReadApi(
            JkHttpConfig config,
            Path webRoot,
            Path logFile,
            Supplier<StatusSnapshot> status,
            EngineHttpJobs jobs,
            Supplier<List<cc.jumpkick.runtime.BuildMetrics.Entry>> metrics,
            Supplier<CacheSnapshot> cache,
            Supplier<String> url) {
        this.config = config;
        this.webRoot = webRoot;
        this.logFile = logFile;
        this.status = status;
        this.jobs = jobs;
        this.metrics = metrics;
        this.cache = cache;
        this.url = url;
    }

    void handleStatus(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String served = url.get();
        String body = JsonOut.object()
                .put("version", s.version())
                .put("pid", s.pid())
                .put("startedAt", s.startedAtMillis())
                .put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000))
                .put("activeRequests", s.activeRequests())
                .put("activeBuildPlans", s.activeBuildPlans())
                .put("peakActiveRequests", s.peakActiveRequests())
                .put("peakActiveBuildPlans", s.peakActiveBuildPlans())
                .put("heapUsedBytes", s.heapUsedBytes())
                .put("heapCommittedBytes", s.heapCommittedBytes())
                .put("heapMaxBytes", s.heapMaxBytes())
                .put("rssBytes", s.rssBytes())
                .put("aotTrainingPid", s.aotTrainingPid())
                .put("cores", s.cores())
                .put("totalMemoryBytes", s.totalMemoryBytes())
                .put("availableMemoryBytes", s.availableMemoryBytes())
                .put("systemCpuLoad", s.systemCpuLoad())
                .put("systemLoadAverage", s.systemLoadAverage())
                .put("engineEpoch", s.engineEpoch())
                .put("httpUrl", served)
                .put("mcpUrl", config.mcp().enabled() && served != null ? served.replaceAll("/+$", "") + "/mcp" : null)
                .put("maxConcurrentRequests", config.effectiveMaxConcurrentRequests())
                .put("maxEventStreams", config.maxEventStreams())
                .put("mcpEnabled", config.mcp().enabled())
                .put("mcpMaxEventStreams", config.mcp().maxEventStreams())
                .put("webRoot", webRoot.toString())
                .toString();
        HttpEngineServer.sendJson(exchange, 200, body);
    }

    /**
     * {@code GET /api/config} — effective machine {@code config.toml} as key / default / effective
     * rows for the Status Configuration panel.
     */
    void handleConfig(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (cc.jumpkick.config.EffectiveUserConfig.Row r : cc.jumpkick.config.EffectiveUserConfig.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("default", r.defaultValue());
            m.put("value", r.effectiveValue());
            m.put("overridden", r.overridden());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", cc.jumpkick.config.EffectiveUserConfig.configPath().toString());
        body.put("rows", rows);
        HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.plugin.protocol.MiniJson.write(body));
    }

    /**
     * The tail of the engine's own log for the Status view — plain text, newest lines last.
     */
    void handleLog(HttpExchange exchange) throws IOException {
        int requested = 120;
        String param = HttpEngineServer.queryParam(exchange.getRequestURI().getQuery(), "lines");
        if (param != null) {
            try {
                requested = Math.max(1, Math.min(400, Integer.parseInt(param)));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        String tail;
        try {
            tail = tailOf(logFile, requested);
        } catch (IOException e) {
            tail = "";
        }
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Last {@code lines} lines of {@code file}, reading at most the final 256 KiB of it. */
    private static String tailOf(Path file, int lines) throws IOException {
        if (!Files.isRegularFile(file)) return "";
        long size = Files.size(file);
        long from = Math.max(0, size - 256 * 1024);
        var buf = java.nio.ByteBuffer.allocate((int) (size - from));
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            channel.position(from);
            while (buf.hasRemaining() && channel.read(buf) >= 0) {}
        }
        byte[] bytes = buf.array();
        String[] all = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int end = all.length > 0 && all[all.length - 1].isEmpty() ? all.length - 1 : all.length;
        int start = Math.max(0, end - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, start, end));
    }

    /**
     * {@code GET /api/fs?dir=…} — the workspace picker behind the dashboard's Browse button.
     */
    void handleFs(HttpExchange exchange) throws IOException {
        String requested = HttpEngineServer.decode(
                HttpEngineServer.queryParam(exchange.getRequestURI().getQuery(), "dir"));
        Path dir;
        try {
            dir = requested == null || requested.isBlank()
                    ? cc.jumpkick.util.PathUtil.userHome()
                    : cc.jumpkick.util.PathUtil.resolveUserPath(requested);
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", e.getMessage() == null ? "invalid dir" : e.getMessage())
                            .toString());
            return;
        }
        List<String> subdirs = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".") && Files.isDirectory(entry)) subdirs.add(name);
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "not a readable directory: " + dir)
                            .toString());
            return;
        }
        subdirs.sort(String.CASE_INSENSITIVE_ORDER);
        boolean truncated = subdirs.size() > MAX_FS_ENTRIES;
        if (truncated) subdirs = subdirs.subList(0, MAX_FS_ENTRIES);
        Path parent = dir.getParent();
        HttpEngineServer.sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("dir", dir.toString())
                        .put("parent", parent != null ? parent.toString() : null)
                        .put("hasJkToml", Files.isRegularFile(dir.resolve("jk.toml")))
                        .put("truncated", truncated)
                        .putStrings("dirs", subdirs)
                        .toString());
    }

    /**
     * {@code GET /api/metrics[?dir=…]} — running build aggregates as a flat JSON array.
     */
    void handleMetrics(HttpExchange exchange) throws IOException {
        String dirFilter = HttpEngineServer.decode(
                HttpEngineServer.queryParam(exchange.getRequestURI().getQuery(), "dir"));
        StringBuilder body = new StringBuilder("[");
        for (cc.jumpkick.runtime.BuildMetrics.Entry e : metrics.get()) {
            if (dirFilter != null && !e.dir().isEmpty() && !e.dir().equals(dirFilter)) continue;
            if (body.length() > 1) body.append(',');
            body.append(JsonOut.object()
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
                    .put("updated", e.updatedMillis()));
        }
        HttpEngineServer.sendJson(exchange, 200, body.append(']').toString());
    }

    /** {@code GET /api/cache} — cache-directory breakdown for the Status view. */
    void handleCache(HttpExchange exchange) throws IOException {
        HttpEngineServer.sendJson(exchange, 200, cache.get().toJson().toString());
    }

    /** {@code POST /api/build} — acknowledge with a request id; progress streams on {@code /api/events}. */
    void handleBuild(HttpExchange exchange) throws IOException {
        String body = new String(
                exchange.getRequestBody().readNBytes(HttpEngineServer.MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String dir = cc.jumpkick.plugin.protocol.Jsonl.str(body, "dir");
        if (dir == null || dir.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        long requestId;
        try {
            requestId = jobs.triggerBuild(dir);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("already running")) {
                HttpEngineServer.sendJson(
                        exchange, 409, JsonOut.object().put("error", msg).toString());
                return;
            }
            exchange.getResponseHeaders().set("Retry-After", "1");
            HttpEngineServer.sendJson(
                    exchange, 503, JsonOut.object().put("error", msg).toString());
            return;
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        HttpEngineServer.sendJson(
                exchange,
                202,
                JsonOut.object()
                        .put("requestId", requestId)
                        .put("jid", requestId)
                        .put("events", "/api/events")
                        .toString());
    }

    /**
     * {@code POST /api/cancel} — body {@code {"jid":N}} or {@code {"requestId":N}} (alias).
     */
    void handleCancel(HttpExchange exchange) throws IOException {
        String body = new String(
                exchange.getRequestBody().readNBytes(HttpEngineServer.MAX_BODY_BYTES), StandardCharsets.UTF_8);
        long jid = cc.jumpkick.plugin.protocol.Jsonl.longValue(body, "jid", -1);
        if (jid < 0) jid = cc.jumpkick.plugin.protocol.Jsonl.longValue(body, "requestId", -1);
        if (jid < 0) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "missing \"jid\" (or requestId)")
                            .toString());
            return;
        }
        boolean ok = jobs.cancel(jid);
        HttpEngineServer.sendJson(
                exchange,
                ok ? 200 : 404,
                JsonOut.object()
                        .put("jid", jid)
                        .put("requestId", jid)
                        .put("cancelled", ok)
                        .put("note", ok ? "" : "unknown or already finished jid")
                        .toString());
    }
}
