// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.lock.ManifestPaths;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The files of a project's checkout the dashboard's files pane reads and edits: a directory page,
 * a path search, one file's text or bytes, and a write guarded by its etag. Every route names the
 * project by id and works only inside one of that id's live checkouts.
 */
final class HttpProjectFilesApi {

    private HttpProjectFilesApi() {}

    /**
     * Cap on a {@code PUT /api/project/file} body: up to 1 MiB of content plus JSON quoting, past the
     * engine-wide 64 KiB. Factor 6, not 3: {@code JSON.stringify} escapes each control character to
     * six bytes, and the binary probe rejects only NUL, so a legal file under the write cap can
     * escape past 3x.
     */
    static final int MAX_FILE_PUT_BODY_BYTES = WorkspaceFileAccess.MAX_FILE_BYTES * 6 + 4096;

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
                body.put("checkouts", HttpProjectApi.checkoutRows(several.checkouts()));
                HttpResponses.sendJson(exchange, 400, MiniJson.write(body));
            }
            case WorkspaceFileAccess.Root.NotACheckout wrong ->
                HttpResponses.sendJson(
                        exchange, 404, HttpProjectApi.notACheckout(projectId, wrong.dir(), wrong.checkouts()));
        }
        return null;
    }

    /**
     * {@code GET /api/project/dir?project=&lt;id&gt;[&amp;dir=…][&amp;path=&lt;rel&gt;][&amp;cursor=…][&amp;limit=…]}
     * — one page of one directory under one of the id's live checkouts ({@link WorkspaceTree#list}).
     * {@code path} is blank for the checkout root. {@code dir} is required when the id has several
     * checkouts; it never widens the sandbox to a tree the id does not record.
     */
    static void handleDir(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId = HttpQuery.queryParam(q, "project");
        String dir = HttpQuery.queryParam(q, "dir");
        String path = HttpQuery.queryParam(q, "path");
        String cursor = HttpQuery.queryParam(q, "cursor");
        int limit = intParam(q, "limit");
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        switch (WorkspaceTree.list(root, path, cursor, limit)) {
            case WorkspaceTree.ListResult.BadRequest bad ->
                HttpResponses.sendJson(
                        exchange,
                        400,
                        JsonOut.object().put("error", bad.error()).toString());
            case WorkspaceTree.ListResult.NotFound ignored ->
                HttpResponses.sendJson(
                        exchange,
                        404,
                        JsonOut.object().put("error", "not found").toString());
            case WorkspaceTree.ListResult.Ok ok -> {
                WorkspaceTree.Page page = ok.page();
                List<Map<String, Object>> entries =
                        new ArrayList<>(page.entries().size());
                for (WorkspaceTree.Entry e : page.entries()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.name());
                    row.put("path", e.path());
                    row.put("dir", e.dir());
                    if (e.lang() != null) row.put("lang", e.lang());
                    if (e.output()) row.put("output", true);
                    if (e.empty()) row.put("empty", true);
                    entries.add(row);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("projectId", projectId);
                body.put("dir", page.root().toString());
                body.put("path", page.path());
                body.put("total", page.total());
                body.put("next", page.next());
                body.put("partial", page.partial());
                body.put("entries", entries);
                HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
            }
        }
    }

    /**
     * {@code GET /api/project/find?project=&lt;id&gt;&amp;q=&lt;terms&gt;[&amp;dir=…][&amp;limit=…]} —
     * files whose path holds every term ({@link WorkspaceTree#find}), for the files pane's filter.
     */
    static void handleFind(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId = HttpQuery.queryParam(q, "project");
        String dir = HttpQuery.queryParam(q, "dir");
        String query = HttpQuery.queryParam(q, "q");
        int limit = intParam(q, "limit");
        if (projectId == null || projectId.isBlank()) {
            HttpResponses.sendJson(
                    exchange,
                    400,
                    JsonOut.object().put("error", "missing \"project\"").toString());
            return;
        }
        Path root = checkoutRoot(exchange, projectId, dir);
        if (root == null) return;
        if (HttpResponses.answeredHead(exchange)) return;
        WorkspaceTree.Matches found = WorkspaceTree.find(root, query == null ? "" : query, limit);
        List<Map<String, Object>> files = new ArrayList<>(found.files().size());
        for (WorkspaceTree.Found f : found.files()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", f.path());
            row.put("lang", f.lang());
            files.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("dir", found.root().toString());
        body.put("more", found.more());
        body.put("partial", found.partial());
        body.put("files", files);
        HttpResponses.sendJson(exchange, 200, MiniJson.write(body));
    }

    /**
     * A non-negative integer query parameter; 0 when absent, which the callee reads as its default.
     * {@link HttpRequests.BadRequest} when it is not one.
     */
    private static int intParam(@Nullable String rawQuery, String name) {
        String v = HttpQuery.queryParam(rawQuery, name);
        if (v == null || v.isBlank()) return 0;
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new HttpRequests.BadRequest("\"" + name + "\" is not a number");
        }
        if (n < 0) throw new HttpRequests.BadRequest("\"" + name + "\" must not be negative");
        return n;
    }

    /**
     * {@code GET /api/project/file?project=&lt;id&gt;&amp;path=&lt;rel&gt;[&amp;dir=…]} — UTF-8
     * source body for one servable path, in the checkout {@link #checkoutRoot} selects.
     */
    static void handleFile(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId = HttpQuery.queryParam(q, "project");
        String path = HttpQuery.queryParam(q, "path");
        String dir = HttpQuery.queryParam(q, "dir");
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
     * servable file (images for the Preview pane). Same sandbox as the JSON body endpoint.
     * Clients must {@code fetch} with the bearer token and build a blob URL — a bare
     * {@code <img src>} cannot send Authorization.
     */
    static void handleFileRaw(HttpExchange exchange) throws IOException {
        String q = exchange.getRequestURI().getRawQuery();
        String projectId = HttpQuery.queryParam(q, "project");
        String path = HttpQuery.queryParam(q, "path");
        String dir = HttpQuery.queryParam(q, "dir");
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
    static void handleFilePut(HttpExchange exchange) throws IOException {
        String body = HttpRequests.body(exchange, MAX_FILE_PUT_BODY_BYTES);
        String projectId = Jsonl.str(body, "project");
        String path = Jsonl.str(body, "path");
        String content = Jsonl.str(body, "content");
        String etag = Jsonl.str(body, "etag");
        String encoding = Jsonl.str(body, "encoding");
        String dir = Jsonl.str(body, "dir");
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
            case WorkspaceFileAccess.WriteResult.Refused refused ->
                HttpResponses.sendJson(
                        exchange,
                        422,
                        JsonOut.object().put("error", refused.error()).toString());
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
