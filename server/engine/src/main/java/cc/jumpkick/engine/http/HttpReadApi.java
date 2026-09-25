// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.EffectiveUserConfig;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.api.LockFloor;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.verbs.MetricsVerb;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.BuildMetrics;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Read-tier status surfaces plus POST build/cancel. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class HttpReadApi {

    /** Directory listings above this are truncated — a picker, not a filesystem dump. */
    private static final int MAX_FS_ENTRIES = 400;

    private final JkHttpConfig config;
    private final Path webRoot;
    private final Path logFile;
    private final Supplier<StatusSnapshot> status;
    private final EngineHttpJobs jobs;
    private final Supplier<List<BuildMetrics.Entry>> metrics;
    private final Supplier<CacheSnapshot> cache;
    private final Supplier<String> url;

    void handleStatus(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String served = url.get();
        // Vitals come from StatusSnapshot.toJson() — the one serializer, shared with the SSE
        // `status` frame. Only the fields below it are REST-only: URLs and config limits that do
        // not change on a 2s tick and so never ride the live stream.
        String body = s.toJson()
                .put("httpUrl", served)
                .put("mcpUrl", config.mcp().enabled() && served != null ? served.replaceAll("/+$", "") + "/mcp" : null)
                .put("maxConcurrentRequests", config.effectiveMaxConcurrentRequests())
                .put("maxEventStreams", config.maxEventStreams())
                .put("mcpEnabled", config.mcp().enabled())
                .put("mcpMaxEventStreams", config.mcp().maxEventStreams())
                .put("webRoot", webRoot.toString())
                .toString();
        HttpResponses.sendJson(exchange, 200, body);
    }

    /**
     * {@code GET /api/config} — effective machine {@code config.toml} as key / default / effective
     * rows for the Status Configuration panel.
     */
    void handleConfig(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EffectiveUserConfig.Row r : EffectiveUserConfig.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", r.key());
            m.put("default", r.defaultValue());
            m.put("value", r.effectiveValue());
            m.put("overridden", r.overridden());
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", EffectiveUserConfig.configPath().toString());
        body.put("rows", rows);
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
    }

    /**
     * The tail of the engine's own log for the Status view — plain text, newest lines last.
     */
    void handleLog(HttpExchange exchange) throws IOException {
        int requested = 120;
        String param = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "lines");
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
        var buf = ByteBuffer.allocate((int) (size - from));
        try (var channel = FileChannel.open(file)) {
            channel.position(from);
            while (buf.hasRemaining() && channel.read(buf) >= 0) {}
        }
        byte[] bytes = buf.array();
        String[] all = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int end = all.length > 0 && all[all.length - 1].isEmpty() ? all.length - 1 : all.length;
        int start = Math.max(0, end - lines);
        return String.join("\n", Arrays.copyOfRange(all, start, end));
    }

    /**
     * {@code GET /api/fs?dir=…} — the workspace picker behind the dashboard's Browse button.
     */
    void handleFs(HttpExchange exchange) throws IOException {
        String requested = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "dir");
        Path dir;
        try {
            dir = requested == null || requested.isBlank() ? PathUtil.userHome() : PathUtil.resolveUserPath(requested);
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
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
        } catch (IOException | DirectoryIteratorException e) {
            HttpResponses.sendJson(
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
        HttpResponses.sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("dir", dir.toString())
                        .put("parent", parent != null ? parent.toString() : null)
                        .put("hasJkToml", Files.isRegularFile(ManifestPaths.manifestIn(dir)))
                        .put("truncated", truncated)
                        .putStrings("dirs", subdirs)
                        .toString());
    }

    /**
     * {@code GET /api/metrics[?dir=…]} — running build aggregates as a flat JSON array.
     */
    void handleMetrics(HttpExchange exchange) throws IOException {
        String dirFilter = HttpQuery.queryParamLenient(exchange.getRequestURI().getRawQuery(), "dir");
        StringBuilder body = new StringBuilder("[");
        for (BuildMetrics.Entry e : metrics.get()) {
            // Same base-dir filter semantics as the wire metrics verb (project rows fold dir#dN).
            if (dirFilter != null && !e.dir().isEmpty() && !BuildMetrics.sameBaseDir(dirFilter, e.dir())) {
                continue;
            }
            if (body.length() > 1) body.append(',');
            body.append(MetricsVerb.metricsFields(JsonOut.object(), e));
        }
        HttpResponses.sendJson(exchange, 200, body.append(']').toString());
    }

    /** {@code GET /api/cache} — cache-directory breakdown for the Status view. */
    void handleCache(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, cache.get().toJson().toString());
    }

    /**
     * {@code POST /api/build} — acknowledge with a request id; progress streams on
     * {@code /api/events}. Optional {@code kind} (default {@code build}) starts any HTTP-exposed
     * job kind (test, lock, …) through the same admission point MCP {@code run} uses. Optional
     * {@code deadlineMs} bounds the job's wall time ({@code 0} = none); absent, the engine's
     * detached default applies.
     */
    void handleBuild(HttpExchange exchange) throws IOException {
        String body = HttpRequests.body(exchange);
        String dir = Jsonl.str(body, "dir");
        if (dir == null || dir.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        String kind = Jsonl.str(body, "kind");
        long requestId;
        try {
            JobSpec spec = JobSpec.of(kind == null ? "build" : kind, dir);
            if (Jsonl.has(body, "deadlineMs")) {
                spec = spec.withDeadlineMs(Jsonl.longValue(body, "deadlineMs", -1L));
            }
            requestId = jobs.trigger(spec);
        } catch (JobEnvelope.AlreadyRunning e) {
            HttpResponses.sendJson(
                    exchange,
                    409,
                    JsonOut.object()
                            .put("error", e.getMessage())
                            .put("jid", e.jid())
                            .toString());
            return;
        } catch (LockFloor.LockFloorRefused e) {
            HttpResponses.sendJson(
                    exchange,
                    409,
                    JsonOut.object()
                            .put("error", e.getMessage())
                            .put("requiredVersion", e.requiredVersion())
                            .toString());
            return;
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            exchange.getResponseHeaders().set("Retry-After", "1");
            HttpResponses.sendJson(
                    exchange, 503, JsonOut.object().put("error", msg).toString());
            return;
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        HttpResponses.sendJson(
                exchange,
                202,
                JsonOut.object()
                        .put("jid", requestId)
                        .put("events", "/api/events")
                        .toString());
    }

    /**
     * {@code POST /api/cancel} — body {@code {"jid":N}}, or {@code {"dir":"…"}} to cancel every
     * live job for a checkout (the wire's dir-scoped cancel, now on every surface).
     */
    void handleCancel(HttpExchange exchange) throws IOException {
        String body = HttpRequests.body(exchange);
        long jid = Jsonl.longValue(body, "jid", -1);
        if (jid < 0) {
            String dir = Jsonl.str(body, "dir");
            if (dir != null && !dir.isBlank()) {
                int n = jobs.cancelDir(dir);
                HttpResponses.sendJson(
                        exchange,
                        n > 0 ? 200 : 404,
                        JsonOut.object()
                                .put("dir", dir)
                                .put("cancelled", n)
                                .put("note", n > 0 ? "" : "no running jobs for dir")
                                .toString());
                return;
            }
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"jid\" or \"dir\"").toString());
            return;
        }
        boolean ok = jobs.cancel(jid);
        HttpResponses.sendJson(
                exchange,
                ok ? 200 : 404,
                JsonOut.object()
                        .put("jid", jid)
                        .put("cancelled", ok)
                        .put("note", ok ? "" : "unknown or already finished jid")
                        .toString());
    }
}
