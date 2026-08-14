// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.journal.BuildJournal;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** New-project, templates, project metadata, and dependency graph. */
final class HttpProjectApi {

    /**
     * {@code GET /api/templates} response cache — building the index walks every template root
     * (JK-1455). One immutable holder rather than two volatiles: a reader must never pair the old
     * JSON with the new timestamp and serve stale rows for a full TTL.
     */
    private record TemplatesCache(String json, long atNanos) {}

    private static final long TEMPLATES_TTL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);

    private final BuildJournal journal;
    private volatile @Nullable TemplatesCache templatesCache;

    HttpProjectApi(BuildJournal journal) {
        this.journal = journal;
    }

    /**
     * {@code POST /api/projects} — scaffold a new project under {@code parentDir} (JK-1193). Same
     * {@link cc.jumpkick.scaffold.NewScaffolder} path as {@code jk new}.
     */
    void handleNewProject(HttpExchange exchange) throws IOException {
        String body = new String(
                exchange.getRequestBody().readNBytes(HttpEngineServer.MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String name = cc.jumpkick.plugin.protocol.Jsonl.str(body, "name");
        String parentDir = cc.jumpkick.plugin.protocol.Jsonl.str(body, "parentDir");
        String group = cc.jumpkick.plugin.protocol.Jsonl.str(body, "group");
        String lang = cc.jumpkick.plugin.protocol.Jsonl.str(body, "lang");
        String layout = cc.jumpkick.plugin.protocol.Jsonl.str(body, "layout");
        String template = cc.jumpkick.plugin.protocol.Jsonl.str(body, "template");
        String framework = cc.jumpkick.plugin.protocol.Jsonl.str(body, "framework");
        boolean executable = cc.jumpkick.plugin.protocol.Jsonl.bool(body, "executable", true);
        try {
            var result = cc.jumpkick.engine.runtime.NewProjectOps.create(
                    new cc.jumpkick.engine.runtime.NewProjectOps.Request(
                            name, parentDir, group, lang, layout, template, executable, framework));
            // Resolve the durable projectId so the SPA can route #project/<id> immediately
            // (JK-1775) — an absolute path in the hash 404s (isValidId rejects '/'). The
            // scaffolder writes no lock, so materialize identity.toml under the project home;
            // without it GET /api/project?project=<id> cannot map the id back to the checkout.
            String projectId = null;
            try {
                var identity = cc.jumpkick.builds.ProjectIdentity.resolve(result.path());
                cc.jumpkick.builds.ProjectIdentity.IdentityFile.write(
                        cc.jumpkick.builds.ProjectBuilds.projectHome(identity.id()), identity);
                cc.jumpkick.runtime.ProjectIds.refresh(result.path().toString());
                projectId = identity.id();
            } catch (RuntimeException | IOException e) {
                // Identity resolution/persist is best-effort — creation succeeded; the SPA
                // skips the project route when projectId is absent.
            }
            JsonOut created = JsonOut.object()
                    .put("path", result.path().toString())
                    .put("dir", result.path().toString());
            if (projectId != null) created.put("projectId", projectId);
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
                java.util.Optional.ofNullable(System.getProperty("user.home"))
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
     * template roots (see {@link cc.jumpkick.scaffold.Giter8TemplateIndex}).
     */
    void handleTemplates(HttpExchange exchange) throws IOException {
        TemplatesCache cached = templatesCache;
        if (cached != null && System.nanoTime() - cached.atNanos() < TEMPLATES_TTL_NANOS) {
            HttpEngineServer.sendJson(exchange, 200, cached.json());
            return;
        }
        // Same roots the short-name resolver uses (JK-1458) — the picker must never list a
        // template that then resolves differently, or miss one that would resolve.
        var entries =
                cc.jumpkick.scaffold.Giter8TemplateIndex.build(cc.jumpkick.scaffold.Giter8TemplateIndex.searchRoots());
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
        String projectId = HttpEngineServer.queryParam(q, "project");
        String dir = HttpEngineServer.queryParam(q, "dir");
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
        // Resolve identity BEFORE the jk.toml parse: resolution succeeds without a parseable
        // manifest (lock / identity.toml / hash), so a ?dir= call on a broken or deleted
        // workspace still gets its durable projectId in the fallback branch (JK-1796).
        String resolvedId = projectId;
        try {
            resolvedId =
                    cc.jumpkick.builds.ProjectIdentity.resolve(Path.of(dir)).id();
        } catch (RuntimeException e) {
            // Invalid path — keep whatever the caller supplied (empty for ?dir= calls).
        }
        try {
            var project = cc.jumpkick.config.JkBuildParser.parse(Path.of(dir).resolve("jk.toml"))
                    .project();
            HttpEngineServer.sendJson(
                    exchange,
                    200,
                    JsonOut.object()
                            .put("dir", dir)
                            .put("projectId", resolvedId)
                            .put("coord", project.group() + ":" + project.name())
                            .put("description", project.description())
                            .toString());
        } catch (RuntimeException e) {
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
     * for the Project page ECharts panel (JK-1542).
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
        boolean transitive = parseTruthy(HttpEngineServer.queryParam(query, "transitive"));
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
        HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.plugin.protocol.MiniJson.write(body));
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
        HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.plugin.protocol.MiniJson.write(body));
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
                body.put("content", b.content());
                HttpEngineServer.sendJson(exchange, 200, cc.jumpkick.plugin.protocol.MiniJson.write(body));
            }
        }
    }

    /** Query flag: true for {@code 1}/{@code true}/{@code yes}/{@code on} (case-insensitive). */
    private static boolean parseTruthy(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("on");
    }
}
