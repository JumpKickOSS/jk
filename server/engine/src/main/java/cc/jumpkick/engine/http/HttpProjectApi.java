// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.journal.BuildJournal;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** New-project, templates, project metadata, and dependency graph. */
final class HttpProjectApi {

    /**
     * {@code GET /api/templates} response cache — building the index walks every template root
     *. One immutable holder rather than two volatiles: a reader must never pair the old
     * JSON with the new timestamp and serve stale rows for a full TTL.
     */
    private record TemplatesCache(String json, long atNanos) {}

    private static final long TEMPLATES_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final BuildJournal journal;
    private volatile @Nullable TemplatesCache templatesCache;

    HttpProjectApi(BuildJournal journal) {
        this.journal = journal;
    }

    /**
     * {@code POST /api/projects} — scaffold a new project under {@code parentDir}. Same
     * {@link cc.jumpkick.scaffold.NewScaffolder} path as {@code jk new}.
     */
    void handleNewProject(HttpExchange exchange) throws IOException {
        String body = new String(
                exchange.getRequestBody().readNBytes(HttpEngineServer.MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String name = cc.jumpkick.jsonl.Jsonl.str(body, "name");
        String parentDir = cc.jumpkick.jsonl.Jsonl.str(body, "parentDir");
        String group = cc.jumpkick.jsonl.Jsonl.str(body, "group");
        String lang = cc.jumpkick.jsonl.Jsonl.str(body, "lang");
        String layout = cc.jumpkick.jsonl.Jsonl.str(body, "layout");
        String template = cc.jumpkick.jsonl.Jsonl.str(body, "template");
        String kind = cc.jumpkick.jsonl.Jsonl.str(body, "kind");
        boolean executable = cc.jumpkick.jsonl.Jsonl.bool(body, "executable", true);
        try {
            // The SPA routes #project/<id> immediately, so identity materializes with creation.
            var result = cc.jumpkick.engine.runtime.NewProjectOps.createWithIdentity(
                    new cc.jumpkick.engine.runtime.NewProjectOps.Request(
                            name, parentDir, group, lang, layout, template, kind, executable));
            JsonOut created = JsonOut.object()
                    .put("path", result.path().toString())
                    .put("dir", result.path().toString());
            if (result.projectId() != null) created.put("projectId", result.projectId());
            HttpEngineServer.sendJson(exchange, 201, created.toString());
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IllegalStateException e) {
            HttpEngineServer.sendJson(
                    exchange, 409, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IOException e) {
            HttpEngineServer.sendJson(
                    exchange,
                    500,
                    JsonOut.object()
                            .put("error", e.getMessage() == null ? "scaffold failed" : e.getMessage())
                            .toString());
        }
    }

    /**
     * {@code GET /api/projects/defaults} — educated guesses for the New project modal (group from
     * git email like {@code jk new}, parent dir from history / well-known roots / git clusters).
     */
    void handleProjectDefaults(HttpExchange exchange) throws IOException {
        String group = cc.jumpkick.scaffold.NewGroupGuess.guess();
        List<Path> historyDirs = new ArrayList<>();
        try {
            for (var rec : journal.list()) {
                if (rec != null && rec.dir() != null && !rec.dir().isBlank()) {
                    historyDirs.add(Path.of(rec.dir()));
                }
            }
        } catch (RuntimeException ignored) {
            // journal empty / unreadable — parent guess still works without it
        }
        Path parent = cc.jumpkick.scaffold.NewParentDirGuess.guess(
                Optional.ofNullable(System.getProperty("user.home"))
                        .map(Path::of)
                        .orElse(null),
                historyDirs);
        HttpEngineServer.sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("group", group)
                        .put("parentDir", parent.toString())
                        .toString());
    }

    /**
     * {@code GET /api/templates} — short-name catalog for the new-project picker. Official
     * catalog rows are merged with on-disk {@code jk_languages}/{@code jk_layout} from local
     * template roots (see {@link cc.jumpkick.giter8.Giter8TemplateIndex}).
     */
    void handleTemplates(HttpExchange exchange) throws IOException {
        TemplatesCache cached = templatesCache;
        if (cached != null && System.nanoTime() - cached.atNanos() < TEMPLATES_TTL_NANOS) {
            HttpEngineServer.sendJson(exchange, 200, cached.json());
            return;
        }
        // Same roots the short-name resolver uses — the picker must never list a
        // template that then resolves differently, or miss one that would resolve.
        var entries =
                cc.jumpkick.giter8.Giter8TemplateIndex.build(cc.jumpkick.giter8.Giter8TemplateIndex.searchRoots());
        var arr = new StringBuilder("[");
        boolean first = true;
        for (var e : entries) {
            if (!first) arr.append(',');
            first = false;
            arr.append(JsonOut.object()
                    .put("id", e.id())
                    .put("description", e.description())
                    .putStrings("languages", e.languages())
                    .put("layout", e.layout())
                    .toString());
        }
        arr.append(']');
        String json = arr.toString();
        templatesCache = new TemplatesCache(json, System.nanoTime());
        HttpEngineServer.sendJson(exchange, 200, json);
    }

    /**
     * {@code GET /api/project?project=&lt;id&gt;} or {@code ?dir=…} — live workspace metadata.
     * Prefer {@code project=} (durable identity); {@code dir=} remains for direct checkout ops.
     */
    void handleProject(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId;
        String dir;
        try {
            projectId = HttpEngineServer.queryParam(q, "project");
            dir = HttpEngineServer.queryParam(q, "dir");
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding is the client's error, not a 500.
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if ((projectId == null || projectId.isBlank()) && (dir == null || dir.isBlank())) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "missing \"project\" or \"dir\"")
                            .toString());
            return;
        }
        if (projectId != null && !projectId.isBlank()) {
            var path = cc.jumpkick.builds.ProjectIdentity.pathForId(projectId);
            if (path.isEmpty()) {
                HttpEngineServer.sendJson(
                        exchange,
                        404,
                        JsonOut.object()
                                .put("error", "unknown project id or checkout path missing: " + projectId)
                                .put("projectId", projectId)
                                .toString());
                return;
            }
            dir = path.get().toString();
        }
        // One card, one parse path (shared with MCP jk_project): identity resolves without a
        // parseable manifest, so a ?dir= call on a broken workspace still gets its durable id.
        cc.jumpkick.runtime.ProjectCard card = cc.jumpkick.runtime.ProjectCard.of(Path.of(dir));
        String resolvedId = card.projectId() != null ? card.projectId() : projectId;
        if (card.coord() != null) {
            HttpEngineServer.sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("dir", dir)
                            .put("projectId", resolvedId)
                            .put("coord", card.coord())
                            .put("description", card.description())
                            .toString());
        } else {
            HttpEngineServer.sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("dir", dir)
                            .put("projectId", resolvedId == null ? "" : resolvedId)
                            .toString());
        }
    }

    /**
     * {@code GET /api/project/graph?dir=…[&scopes=main,test][&transitive=0|1]} — dependency graph
     * for the Project page ECharts panel.
     */
    void handleProjectGraph(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        Path projectDir;
        List<cc.jumpkick.model.Scope> scopes;
        try {
            String dir = HttpEngineServer.queryParam(query, "dir");
            if (dir == null || dir.isBlank()) {
                HttpEngineServer.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", "missing \"dir\"").toString());
                return;
            }
            projectDir = Path.of(dir);
            scopes =
                    cc.jumpkick.resolver.DependencyGraphModel.parseScopes(HttpEngineServer.queryParam(query, "scopes"));
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        boolean transitive = parseTruthy(HttpEngineServer.queryParamLenient(query, "transitive"));
        cc.jumpkick.resolver.DependencyGraphModel.Graph data;
        try {
            data = cc.jumpkick.resolver.DependencyGraphModel.forProjectDir(projectDir, scopes, transitive);
        } catch (IOException | cc.jumpkick.config.JkBuildParseException e) {
            String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            HttpEngineServer.sendJson(
                    exchange, 422, JsonOut.object().put("error", msg).toString());
            return;
        }
        List<Map<String, Object>> nodes = new ArrayList<>(data.nodes().size());
        for (var n : data.nodes()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.id());
            row.put("label", n.label());
            row.put("kind", n.kind());
            if (n.version() != null) row.put("version", n.version());
            if (n.path() != null) row.put("path", n.path());
            nodes.add(row);
        }
        List<Map<String, Object>> edges = new ArrayList<>(data.edges().size());
        for (var e : data.edges()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", e.from());
            row.put("to", e.to());
            if (e.scope() != null) row.put("scope", e.scope());
            edges.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dir", projectDir.toAbsolutePath().normalize().toString());
        body.put("workspace", data.workspace());
        body.put("scopes", data.scopes());
        body.put("transitive", data.transitive());
        body.put("truncated", data.truncated());
        body.put("availableScopes", data.availableScopes());
        body.put("nodes", nodes);
        body.put("edges", edges);
        HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.jsonl.MiniJson.write(body));
    }

    /**
     * {@code GET /api/project/files?project=&lt;id&gt;} — allow-listed source paths under the
     * identity checkout. No {@code dir=} fallback.
     */
    void handleProjectFiles(HttpExchange exchange) throws IOException {
        String projectId;
        try {
            projectId = HttpEngineServer.queryParam(exchange.getRequestURI().getRawQuery(), "project");
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        var root = WorkspaceFileAccess.resolveRoot(projectId);
        if (root.isEmpty()) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object()
                            .put("error", "unknown project id or checkout path missing: " + projectId)
                            .put("projectId", projectId)
                            .toString());
            return;
        }
        WorkspaceFileAccess.FileList list;
        try {
            list = WorkspaceFileAccess.list(root.get());
        } catch (IOException e) {
            HttpEngineServer.sendJson(
                    exchange,
                    500,
                    JsonOut.object()
                            .put("error", e.getMessage() == null ? "list failed" : e.getMessage())
                            .toString());
            return;
        }
        List<Map<String, Object>> files = new ArrayList<>(list.files().size());
        for (var f : list.files()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", f.path());
            row.put("lang", f.lang());
            files.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("dir", list.root().toString());
        body.put("truncated", list.truncated());
        body.put("files", files);
        HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.jsonl.MiniJson.write(body));
    }

    /**
     * {@code GET /api/project/file?project=&lt;id&gt;&amp;path=&lt;rel&gt;} — UTF-8 source body
     * for one allow-listed path. Identity-scoped; no {@code dir=}.
     */
    void handleProjectFile(HttpExchange exchange) throws IOException {
        String projectId;
        String path;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            projectId = HttpEngineServer.queryParam(q, "project");
            path = HttpEngineServer.queryParam(q, "path");
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        var root = WorkspaceFileAccess.resolveRoot(projectId);
        if (root.isEmpty()) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object()
                            .put("error", "unknown project id or checkout path missing: " + projectId)
                            .put("projectId", projectId)
                            .toString());
            return;
        }
        switch (WorkspaceFileAccess.read(root.get(), path)) {
            case WorkspaceFileAccess.ReadResult.BadRequest bad ->
                HttpEngineServer.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.ReadResult.NotFound ignored ->
                HttpEngineServer.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.ReadResult.TooLarge too ->
                HttpEngineServer.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.ReadResult.Binary ignored ->
                HttpEngineServer.sendJson(
                        exchange,
                        415,
                        JsonOut.object().put("error", "binary file").toString());
            case WorkspaceFileAccess.ReadResult.Ok ok -> {
                var b = ok.body();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("projectId", projectId);
                body.put("dir", b.root().toString());
                body.put("path", b.path());
                body.put("lang", b.lang());
                body.put("bytes", b.bytes());
                body.put("lines", b.lines());
                body.put("encoding", b.encoding());
                body.put("etag", b.etag());
                body.put("content", b.content());
                HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.jsonl.MiniJson.write(body));
            }
        }
    }

    /**
     * {@code GET /api/project/file/raw?project=&lt;id&gt;&amp;path=&lt;rel&gt;} — raw bytes of one
     * allow-listed file (images for the Preview pane). Same sandbox as the JSON body endpoint.
     * Clients must {@code fetch} with the bearer token and build a blob URL — a bare
     * {@code <img src>} cannot send Authorization.
     */
    void handleProjectFileRaw(HttpExchange exchange) throws IOException {
        String projectId;
        String path;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            projectId = HttpEngineServer.queryParam(q, "project");
            path = HttpEngineServer.queryParam(q, "path");
        } catch (IllegalArgumentException e) {
            HttpEngineServer.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        var root = WorkspaceFileAccess.resolveRoot(projectId);
        if (root.isEmpty()) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object()
                            .put("error", "unknown project id or checkout path missing: " + projectId)
                            .put("projectId", projectId)
                            .toString());
            return;
        }
        switch (WorkspaceFileAccess.readRaw(root.get(), path)) {
            case WorkspaceFileAccess.RawResult.BadRequest bad ->
                HttpEngineServer.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.RawResult.NotFound ignored ->
                HttpEngineServer.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.RawResult.TooLarge too ->
                HttpEngineServer.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.RawResult.Ok ok ->
                HttpEngineServer.sendBytes(
                        exchange, 200, ok.body().contentType(), ok.body().bytes());
        }
    }

    /**
     * {@code PUT /api/project/file} — replace a text-servable file under the identity checkout.
     * Body JSON: {@code { "project", "path", "content", "etag"? }}. When {@code etag} is present it
     * must match the current on-disk SHA-256 or the write is {@code 409}. Cap matches {@link
     * WorkspaceFileAccess#MAX_FILE_BYTES} (not the smaller global POST body limit).
     */
    void handleProjectFilePut(HttpExchange exchange) throws IOException {
        // File write can be up to 1 MiB of content plus JSON quoting overhead; read past the
        // engine-wide 64 KiB mutation cap used for build/cancel/scaffold. Factor 6, not 3:
        // JSON.stringify escapes each control char to six bytes (backslash-u form), and the
        // binary probe only rejects NUL, so a legal control-char-heavy file under the 1 MiB
        // write cap can escape past 3x.
        int maxBody = WorkspaceFileAccess.MAX_FILE_BYTES * 6 + 4096;
        byte[] raw = exchange.getRequestBody().readNBytes(maxBody + 1);
        if (raw.length > maxBody) {
            HttpEngineServer.sendJson(
                    exchange,
                    413,
                    JsonOut.object()
                            .put("error", "request body too large")
                            .put("maxBytes", maxBody)
                            .toString());
            return;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        String projectId = cc.jumpkick.jsonl.Jsonl.topStr(body, "project");
        String path = cc.jumpkick.jsonl.Jsonl.topStr(body, "path");
        String content = cc.jumpkick.jsonl.Jsonl.topStr(body, "content");
        String etag = cc.jumpkick.jsonl.Jsonl.topStr(body, "etag");
        String encoding = cc.jumpkick.jsonl.Jsonl.topStr(body, "encoding");
        if (projectId == null || projectId.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        if (content == null) {
            HttpEngineServer.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"content\"").toString());
            return;
        }
        var root = WorkspaceFileAccess.resolveRoot(projectId);
        if (root.isEmpty()) {
            HttpEngineServer.sendJson(
                    exchange,
                    404,
                    JsonOut.object()
                            .put("error", "unknown project id or checkout path missing: " + projectId)
                            .put("projectId", projectId)
                            .toString());
            return;
        }
        switch (WorkspaceFileAccess.write(root.get(), path, content, etag, encoding)) {
            case WorkspaceFileAccess.WriteResult.BadRequest bad ->
                HttpEngineServer.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.WriteResult.NotFound ignored ->
                HttpEngineServer.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.WriteResult.TooLarge too ->
                HttpEngineServer.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.WriteResult.NotWritable nw ->
                HttpEngineServer.sendJson(
                        exchange, 415, JsonOut.object().put("error", nw.error()).toString());
            case WorkspaceFileAccess.WriteResult.Conflict conflict ->
                HttpEngineServer.sendJson(
                        exchange,
                        409,
                        JsonOut.object()
                                .put("error", "file changed on disk")
                                .put("etag", conflict.currentEtag())
                                .toString());
            case WorkspaceFileAccess.WriteResult.Failed failed ->
                HttpEngineServer.sendJson(
                        exchange,
                        500,
                        JsonOut.object().put("error", failed.error()).toString());
            case WorkspaceFileAccess.WriteResult.Ok ok -> {
                var w = ok.body();
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("projectId", projectId);
                resp.put("dir", w.root().toString());
                resp.put("path", w.path());
                resp.put("lang", w.lang());
                resp.put("bytes", w.bytes());
                resp.put("lines", w.lines());
                resp.put("etag", w.etag());
                // A manifest edit stales the lock's manifests-sha256 stamp: the next build pays a
                // full re-resolve. Tell the pane so the user is not surprised.
                String fileName = w.path().substring(w.path().lastIndexOf('/') + 1);
                if (fileName.equals("jk.toml") || fileName.equals("jk-libs.toml")) {
                    resp.put("lockStale", Boolean.TRUE);
                }
                HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.jsonl.MiniJson.write(resp));
            }
        }
    }

    /** Query flag: true for {@code 1}/{@code true}/{@code yes}/{@code on} (case-insensitive). */
    private static boolean parseTruthy(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("on");
    }
}
