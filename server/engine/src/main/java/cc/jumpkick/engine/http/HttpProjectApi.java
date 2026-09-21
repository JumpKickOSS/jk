// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyGraphModel;
import cc.jumpkick.runtime.base.ProjectCard;
import cc.jumpkick.scaffold.NewGroupGuess;
import cc.jumpkick.scaffold.NewParentDirGuess;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        String body = HttpRequests.body(exchange);
        String name = Jsonl.str(body, "name");
        String parentDir = Jsonl.str(body, "parentDir");
        String group = Jsonl.str(body, "group");
        String lang = Jsonl.str(body, "lang");
        String layout = Jsonl.str(body, "layout");
        String template = Jsonl.str(body, "template");
        boolean executable = Jsonl.bool(body, "executable", true);
        try {
            // The SPA routes #project/<id> immediately, so identity materializes with creation.
            var result = NewProjectOps.createWithIdentity(
                    new NewProjectOps.Request(name, parentDir, group, lang, layout, template, executable));
            JsonOut created = JsonOut.object()
                    .put("path", result.path().toString())
                    .put("dir", result.path().toString());
            if (result.projectId() != null) created.put("projectId", result.projectId());
            HttpResponses.sendJson(exchange, 201, created.toString());
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IllegalStateException e) {
            HttpResponses.sendJson(
                    exchange, 409, JsonOut.object().put("error", e.getMessage()).toString());
        } catch (IOException e) {
            HttpResponses.sendJson(
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
        String group = NewGroupGuess.guess();
        List<Path> historyDirs = new ArrayList<>();
        try {
            for (var rec : journal.list()) {
                if (rec != null && rec.dir() != null && !rec.dir().isBlank()) {
                    historyDirs.add(Path.of(rec.dir()));
                }
            }
        } catch (RuntimeException e) {
            // journal empty / unreadable — parent guess still works without it
            Log.debug("handleProjectDefaults: journal empty / unreadable", e);
        }
        Path parent = NewParentDirGuess.guess(
                Optional.ofNullable(System.getProperty("user.home"))
                        .map(Path::of)
                        .orElse(null),
                historyDirs);
        HttpResponses.sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("group", group)
                        .put("parentDir", parent.toString())
                        .toString());
    }

    /**
     * {@code GET /api/templates} — unified catalog for the new-project picker
     * ({@link cc.jumpkick.giter8.Giter8TemplateIndex}).
     */
    void handleTemplates(HttpExchange exchange) throws IOException {
        TemplatesCache cached = templatesCache;
        if (cached != null && System.nanoTime() - cached.atNanos() < TEMPLATES_TTL_NANOS) {
            HttpResponses.sendJson(exchange, 200, cached.json());
            return;
        }
        var entries = Giter8TemplateIndex.picker(Giter8TemplateIndex.searchRoots());
        var arr = new StringBuilder("[");
        boolean first = true;
        for (var e : entries) {
            if (!first) arr.append(',');
            first = false;
            JsonOut row = JsonOut.object()
                    .put("id", e.id())
                    .put("name", e.name())
                    .put("language", e.language())
                    .put("framework", e.framework())
                    .put("description", e.description())
                    .putStrings("layouts", e.layouts())
                    .put("source", e.source());
            if (e.pluginId() != null) row.put("pluginId", e.pluginId());
            arr.append(row.toString());
        }
        arr.append(']');
        String json = arr.toString();
        templatesCache = new TemplatesCache(json, System.nanoTime());
        HttpResponses.sendJson(exchange, 200, json);
    }

    /**
     * {@code GET /api/project?project=&lt;id&gt;[&amp;dir=…]} or {@code ?dir=…} — live workspace
     * metadata. {@code project=} is the durable identity; an id with one live checkout implies it,
     * an id with several answers only the {@code checkouts} list until {@code dir=} names one.
     * {@code dir=} alone remains for direct checkout ops.
     */
    void handleProject(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId;
        String dir;
        try {
            projectId = HttpQuery.queryParam(q, "project");
            dir = HttpQuery.queryParam(q, "dir");
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding is the client's error, not a 500.
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if ((projectId == null || projectId.isBlank()) && (dir == null || dir.isBlank())) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object()
                            .put("error", "missing \"project\" or \"dir\"")
                            .toString());
            return;
        }
        List<ProjectIdentity.Checkout> checkouts = List.of();
        if (projectId != null && !projectId.isBlank()) {
            checkouts = ProjectIdentity.isValidId(projectId) ? ProjectIdentity.checkoutsForId(projectId) : List.of();
            if (checkouts.isEmpty()) {
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object()
                                .put("error", "unknown project id or no live checkout: " + projectId)
                                .put("projectId", projectId)
                                .toString());
                return;
            }
            if (dir != null && !dir.isBlank()) {
                Optional<ProjectIdentity.Checkout> picked = ProjectIdentity.selectCheckout(checkouts, dir);
                if (picked.isEmpty()) {
                    HttpResponses.sendJson(exchange, 404, notACheckout(projectId, dir, checkouts));
                    return;
                }
                dir = picked.get().path().toString();
            } else if (checkouts.size() == 1) {
                dir = checkouts.getFirst().path().toString();
            } else {
                // Several live checkouts and no selector: name them; the card is per checkout.
                Map<String, Object> listing = new LinkedHashMap<>();
                listing.put("projectId", projectId);
                ProjectIdentity.homeForId(projectId)
                        .flatMap(ProjectIdentity.IdentityFile::read)
                        .map(ProjectIdentity.IdentityFile::coord)
                        .ifPresent(coord -> listing.put("coord", coord));
                listing.put("checkouts", checkoutRows(checkouts));
                HttpResponses.sendJson(exchange, 200, MiniJson.write(listing));
                return;
            }
        }
        // One card, one parse path (shared with MCP jk_project): identity resolves without a
        // parseable manifest, so a ?dir= call on a broken workspace still gets its durable id.
        ProjectCard card = ProjectCard.of(Path.of(dir));
        String resolvedId = card.projectId() != null ? card.projectId() : projectId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dir", dir);
        body.put("projectId", resolvedId == null ? "" : resolvedId);
        if (card.coord() != null) {
            body.put("coord", card.coord());
            body.put("description", card.description());
        }
        if (!checkouts.isEmpty()) body.put("checkouts", checkoutRows(checkouts));
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
    }

    /**
     * The checkout an id-routed file request works in, or {@code null} after answering the
     * error: 404 for an id with no live checkout or a {@code dir} that is none of them, 400 when
     * the id has several live checkouts and the request named none.
     */
    private static @Nullable Path checkoutRoot(HttpExchange exchange, String projectId, @Nullable String dir)
            throws IOException {
        switch (WorkspaceFileAccess.resolveRoot(projectId, dir)) {
            case WorkspaceFileAccess.Root.Ok ok -> {
                return ok.root();
            }
            case WorkspaceFileAccess.Root.Unknown ignored ->
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object()
                                .put("error", "unknown project id or no live checkout: " + projectId)
                                .put("projectId", projectId)
                                .toString());
            case WorkspaceFileAccess.Root.Ambiguous several -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(
                        "error",
                        "project " + projectId + " has " + several.checkouts().size()
                                + " live checkouts; pass \"dir\" to name one");
                body.put("projectId", projectId);
                body.put("checkouts", checkoutRows(ProjectIdentity.checkoutsForId(projectId)));
                HttpResponses.sendJson(exchange, 400, MiniJson.write(body));
            }
            case WorkspaceFileAccess.Root.NotACheckout wrong ->
                HttpResponses.sendJson(
                        exchange, 404, notACheckout(projectId, wrong.dir(), ProjectIdentity.checkoutsForId(projectId)));
        }
        return null;
    }

    private static String notACheckout(String projectId, String dir, List<ProjectIdentity.Checkout> checkouts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "not a live checkout of project " + projectId + ": " + dir);
        body.put("projectId", projectId);
        body.put("dir", dir);
        body.put("checkouts", checkoutRows(checkouts));
        return MiniJson.write(body);
    }

    /** {@code [{ dir, lastBuilt }]} — the id's live checkouts as the SPA and MCP list them. */
    static List<Map<String, Object>> checkoutRows(List<ProjectIdentity.Checkout> checkouts) {
        List<Map<String, Object>> rows = new ArrayList<>(checkouts.size());
        for (ProjectIdentity.Checkout c : checkouts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dir", c.path().toString());
            if (c.lastBuilt() != null) row.put("lastBuilt", c.lastBuilt().toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * {@code GET /api/project/graph?dir=…[&scopes=main,test][&transitive=0|1]} — dependency graph
     * for the Project page ECharts panel.
     */
    void handleProjectGraph(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        Path projectDir;
        List<Scope> scopes;
        try {
            String dir = HttpQuery.queryParam(query, "dir");
            if (dir == null || dir.isBlank()) {
                HttpResponses.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", "missing \"dir\"").toString());
                return;
            }
            projectDir = Path.of(dir);
            scopes = DependencyGraphModel.parseScopes(HttpQuery.queryParam(query, "scopes"));
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        boolean transitive = EnvValues.parseBool(HttpQuery.queryParamLenient(query, "transitive"))
                .orElse(false);
        DependencyGraphModel.Graph data;
        try {
            data = DependencyGraphModel.forProjectDir(projectDir, scopes, transitive);
        } catch (IOException | JkBuildParseException e) {
            String msg = e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
            HttpResponses.sendJson(
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
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
    }

    /**
     * {@code GET /api/project/files?project=&lt;id&gt;[&amp;dir=…]} — allow-listed source paths
     * under one of the id's live checkouts. {@code dir} is required when the id has several; it
     * never widens the sandbox to a tree the id does not record.
     */
    void handleProjectFiles(HttpExchange exchange) throws IOException {
        String projectId;
        String dir;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            projectId = HttpQuery.queryParam(q, "project");
            dir = HttpQuery.queryParam(q, "dir");
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        WorkspaceFileAccess.FileList list;
        try {
            list = WorkspaceFileAccess.list(root);
        } catch (IOException e) {
            HttpResponses.sendJson(
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
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
    }

    /**
     * {@code GET /api/project/file?project=&lt;id&gt;&amp;path=&lt;rel&gt;[&amp;dir=…]} — UTF-8
     * source body for one allow-listed path, in the checkout {@link #checkoutRoot} selects.
     */
    void handleProjectFile(HttpExchange exchange) throws IOException {
        String projectId;
        String path;
        String dir;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            projectId = HttpQuery.queryParam(q, "project");
            path = HttpQuery.queryParam(q, "path");
            dir = HttpQuery.queryParam(q, "dir");
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        switch (WorkspaceFileAccess.read(root, path)) {
            case WorkspaceFileAccess.ReadResult.BadRequest bad ->
                HttpResponses.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.ReadResult.NotFound ignored ->
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.ReadResult.TooLarge too ->
                HttpResponses.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.ReadResult.Binary ignored ->
                HttpResponses.sendJson(
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
                HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
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
        String dir;
        try {
            String q = exchange.getRequestURI().getRawQuery();
            projectId = HttpQuery.queryParam(q, "project");
            path = HttpQuery.queryParam(q, "path");
            dir = HttpQuery.queryParam(q, "dir");
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJson(
                    exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        switch (WorkspaceFileAccess.readRaw(root, path)) {
            case WorkspaceFileAccess.RawResult.BadRequest bad ->
                HttpResponses.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.RawResult.NotFound ignored ->
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.RawResult.TooLarge too ->
                HttpResponses.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.RawResult.Ok ok ->
                HttpResponses.sendBytes(
                        exchange, 200, ok.body().contentType(), ok.body().bytes());
        }
    }

    /**
     * {@code PUT /api/project/file} — replace a text-servable file in one of the id's checkouts.
     * Body JSON: {@code { "project", "path", "content", "etag"?, "dir"? }}; {@code dir} is required
     * when the id has several live checkouts. When {@code etag} is present it
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
            HttpResponses.sendJson(
                    exchange,
                    413,
                    JsonOut.object()
                            .put("error", "request body too large")
                            .put("maxBytes", maxBody)
                            .toString());
            return;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        String projectId = Jsonl.topStr(body, "project");
        String path = Jsonl.topStr(body, "path");
        String content = Jsonl.topStr(body, "content");
        String etag = Jsonl.topStr(body, "etag");
        String encoding = Jsonl.topStr(body, "encoding");
        String dir = Jsonl.topStr(body, "dir");
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        if (path == null || path.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"path\"").toString());
            return;
        }
        if (content == null) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"content\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        switch (WorkspaceFileAccess.write(root, path, content, etag, encoding)) {
            case WorkspaceFileAccess.WriteResult.BadRequest bad ->
                HttpResponses.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceFileAccess.WriteResult.NotFound ignored ->
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceFileAccess.WriteResult.TooLarge too ->
                HttpResponses.sendJson(
                        exchange,
                        413,
                        JsonOut.object()
                                .put("error", "file too large")
                                .put("bytes", too.bytes())
                                .put("maxBytes", too.maxBytes())
                                .toString());
            case WorkspaceFileAccess.WriteResult.NotWritable nw ->
                HttpResponses.sendJson(
                        exchange, 415, JsonOut.object().put("error", nw.error()).toString());
            case WorkspaceFileAccess.WriteResult.Conflict conflict ->
                HttpResponses.sendJson(
                        exchange,
                        409,
                        JsonOut.object()
                                .put("error", "file changed on disk")
                                .put("etag", conflict.currentEtag())
                                .toString());
            case WorkspaceFileAccess.WriteResult.Failed failed ->
                HttpResponses.sendJson(
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
                if (fileName.equals(ManifestPaths.MANIFEST) || fileName.equals(ManifestPaths.LIBRARIES)) {
                    resp.put("lockStale", Boolean.TRUE);
                }
                HttpResponses.sendJson(exchange, 200, MiniJson.write(resp));
            }
        }
    }
}
