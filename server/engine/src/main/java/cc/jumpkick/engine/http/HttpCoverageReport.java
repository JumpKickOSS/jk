// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.CoverageRollup;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /report/<token>/<run id>/<path>} — a run's coverage HTML report, served from the
 * checkout the run measured. The bearer token rides in the path because a report is a tree of
 * pages the browser follows by relative link, and a link can carry neither a header nor a query
 * string into the next page. An empty path redirects to the report's entry page: the one module's
 * {@code index.html}, or the workspace roll-up. Only files under a report directory the record
 * names are served, each in a sandboxed opaque origin so a report cannot script the dashboard.
 */
@NullMarked
final class HttpCoverageReport {

    static final String PREFIX = "/report/";

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("gif", "image/gif"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private final BuildJournal journal;
    private final HttpTokenGate tokens;

    HttpCoverageReport(BuildJournal journal, HttpTokenGate tokens) {
        this.journal = journal;
        this.tokens = tokens;
    }

    /** The dashboard's link for run {@code id}: the entry page, tokenized. */
    static String href(String token, String id) {
        return PREFIX + token + "/" + id + "/";
    }

    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            HttpResponses.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String[] parts =
                exchange.getRequestURI().getPath().substring(PREFIX.length()).split("/", 3);
        if (parts.length < 2 || !tokens.tokenValid(parts[0])) {
            tokens.challenge(exchange);
            return;
        }
        BuildRecord record = journal.get(parts[1]).orElse(null);
        if (record == null || record.coverage().isEmpty()) {
            HttpResponses.sendText(exchange, 404, "no coverage report for this run\n");
            return;
        }
        String rel = parts.length < 3 ? "" : parts[2];
        if (rel.isEmpty()) {
            exchange.getResponseHeaders().set("Location", href(parts[0], parts[1]) + entryPage(record));
            exchange.sendResponseHeaders(302, -1);
            return;
        }
        Path file = servable(record, rel);
        if (file == null) {
            HttpResponses.sendText(exchange, 404, "not part of this run's coverage report\n");
            return;
        }
        exchange.getResponseHeaders().set("Content-Security-Policy", "sandbox");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        HttpResponses.sendBytes(exchange, 200, contentType(rel), Files.readAllBytes(file));
    }

    /** The entry page relative to the run's directory, forward slashes. */
    static String entryPage(BuildRecord record) {
        Path root = Path.of(record.dir()).toAbsolutePath().normalize();
        Path page = record.coverage().size() == 1
                ? Path.of(record.coverage().get(0).html())
                : CoverageRollup.pageFor(record);
        return root.relativize(page.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /**
     * {@code rel} under the run's directory when it is a file inside one of the record's report
     * directories (each module's, and the roll-up's), else {@code null}.
     */
    static @Nullable Path servable(BuildRecord record, String rel) {
        Path root = Path.of(record.dir()).toAbsolutePath().normalize();
        Path file;
        try {
            file = root.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return null;
        for (Path dir : reportDirs(record)) {
            if (file.startsWith(dir)) return file;
        }
        return null;
    }

    private static List<Path> reportDirs(BuildRecord record) {
        List<Path> dirs = new ArrayList<>();
        for (BuildRecord.Coverage c : record.coverage()) {
            Path parent = Path.of(c.html()).toAbsolutePath().normalize().getParent();
            if (parent != null) dirs.add(parent);
        }
        if (record.coverage().size() > 1) {
            Path parent =
                    CoverageRollup.pageFor(record).toAbsolutePath().normalize().getParent();
            if (parent != null) dirs.add(parent);
        }
        return dirs;
    }

    private static String contentType(String rel) {
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
