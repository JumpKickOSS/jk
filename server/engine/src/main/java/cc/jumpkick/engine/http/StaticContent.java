// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * Static files: disk {@code web-root} first (no-cache, size-snapshotted GET so concurrent appends
 * cannot corrupt framing), then classpath {@code /web} (version ETag). No directory listings.
 */
final class StaticContent {

    private static final String CLASSPATH_PREFIX = "/web/";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneOffset.UTC);

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"),
            Map.entry("wasm", "application/wasm"));

    private final Path root;
    private final String classpathEtag;

    /**
     * Snapshot builds revalidate classpath assets on every load: the version-derived ETag never
     * moves between {@code -SNAPSHOT} jars, so an hour of {@code max-age} would keep serving the
     * previous jar's dashboard from the browser cache after an upgrade. Releases bump the
     * version, so they keep real caching. {@code installLocal} of the same version also changes
     * the jar's {@code /web/index.html} mtime, which is folded into the ETag so a same-version
     * bounce is not a perpetual 304 of the previous shell.
     */
    private final boolean snapshotVersion;

    /** @param root the resolved {@code web-root} — need not exist (classpath still serves) */
    StaticContent(Path root, String version) {
        this.root = root.normalize();
        this.classpathEtag = "\"jk-" + version + "-" + classpathStamp() + "\"";
        this.snapshotVersion = version.endsWith("-SNAPSHOT");
    }

    private static String classpathStamp() {
        long modified = 0;
        try {
            var src = StaticContent.class.getProtectionDomain().getCodeSource();
            URL loc = src == null ? null : src.getLocation();
            if (loc != null && "file".equals(loc.getProtocol())) {
                modified = Files.getLastModifiedTime(Path.of(loc.toURI())).toMillis();
            }
        } catch (Exception ignored) {
            // fall through to the shell resource
        }
        if (modified == 0) {
            URL resource = StaticContent.class.getResource(CLASSPATH_PREFIX + "index.html");
            if (resource != null) {
                try {
                    modified = resource.openConnection().getLastModified();
                } catch (IOException ignored) {
                    // keep 0
                }
            }
        }
        return Long.toHexString(modified);
    }

    void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        boolean head = method.equals("HEAD");
        if (!head && !method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            HttpEngineServer.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String rel = relativize(exchange.getRequestURI().getPath());
        if (rel == null) {
            HttpEngineServer.sendText(exchange, 404, "not found\n");
            return;
        }
        if (serveFromDisk(exchange, rel, head)) return;
        if (serveFromClasspath(exchange, rel, head)) return;
        HttpEngineServer.sendText(exchange, 404, "not found\n");
    }

    /**
     * Map a request path (already percent-decoded by {@link java.net.URI#getPath}) to a safe
     * root-relative file path, or {@code null} for anything that smells like traversal. Percent
     * decoding happens before this sees the path, so {@code %2e%2e} arrives as literal {@code ..}
     * and is caught by the segment check like any other spelling.
     */
    private static String relativize(String requestPath) {
        if (requestPath == null || requestPath.indexOf('\0') >= 0 || requestPath.indexOf('\\') >= 0) return null;
        String path = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (path.isEmpty() || path.endsWith("/")) path = path + "index.html";
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return path;
    }

    private boolean serveFromDisk(HttpExchange exchange, String rel, boolean head) throws IOException {
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return false;
        // Lexical containment is not enough: builds may write into web-root, so a symlink planted
        // there would resolve outside and be served *unauthenticated* (static content is never
        // token-gated). Compare real paths (JK-1487).
        try {
            if (!file.toRealPath().startsWith(root.toRealPath())) return false;
        } catch (IOException e) {
            return false; // unresolvable — treat as absent
        }

        Instant lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException e) {
            return false; // deleted between the check and now — fall through to classpath/404
        }
        // Disk web-root is never token-gated, and builds may write arbitrary user content (HTML
        // reports) into it. Without a policy, such HTML is same-origin with the SPA and can read
        // localStorage['jk-http-token'] — the sole credential (JK-1776). A bare CSP `sandbox`
        // renders it in a unique opaque origin with scripts, forms, and plugins disabled: styles
        // and images still work (reports stay readable), but nothing under web-root can script
        // the dashboard origin or exfiltrate the token.
        exchange.getResponseHeaders().set("Content-Security-Policy", "sandbox");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Last-Modified", HTTP_DATE.format(lastModified));
        if (notModifiedSince(exchange.getRequestHeaders().getFirst("If-Modified-Since"), lastModified)) {
            exchange.sendResponseHeaders(304, -1);
            return true;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(rel));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size(); // the snapshot: a concurrent append only ever grows the file
            if (head) {
                // The JDK's own FileServerHandler convention: an explicit Content-Length plus a
                // -1 response length is the sanctioned "headers only, no body" shape for HEAD.
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
            } else if (size == 0) {
                exchange.sendResponseHeaders(200, -1); // a 0 length arg would mean chunked, not empty
            } else {
                exchange.sendResponseHeaders(200, size);
                copyExactly(channel, exchange.getResponseBody(), size);
            }
        }
        return true;
    }

    private boolean serveFromClasspath(HttpExchange exchange, String rel, boolean head) throws IOException {
        URL resource = StaticContent.class.getResource(CLASSPATH_PREFIX + rel);
        if (resource == null) return false;
        // On an exploded classpath (dev/test) a directory resolves to a file: URL — never serve
        // those; in a jar, directory entries don't resolve to streams of content the same way, and
        // the regular-file check below covers the exploded case.
        if ("file".equals(resource.getProtocol())) {
            try {
                if (!Files.isRegularFile(Path.of(resource.toURI()))) return false;
            } catch (URISyntaxException e) {
                return false;
            }
        }
        // The shipped SPA's only external resources: Vue + ECharts + Monaco (lazy,
        // #project/…/files) + Preview CDNs (marked/mermaid/…, also lazy) from unpkg
        // (version-pinned; SRI on every static tag where possible) and the JetBrains
        // Mono webfonts from Google Fonts (CSS on fonts.googleapis.com, font files on
        // fonts.gstatic.com). style-src includes unpkg for Monaco's editor.main.css, which its
        // loader injects itself.
        // 'unsafe-eval' is Vue's runtime template compiler. The rest is Monaco, and each piece was
        // verified against a real browser (a missing one degrades to a blank/broken pane):
        //   blob: in script-src + worker-src — it spawns language workers from a generated Blob
        //     that importScripts the unpkg bundle;
        //   'unsafe-inline' in style-src — the editor positions every view line, cursor and widget
        //     with inline style attributes (~100 per paint), which no hash or nonce can cover;
        //   data: in font-src — editor.main.css inlines the codicon font (gutter/find-widget icons).
        // Disk web-root content gets a stricter sandboxing CSP in serveFromDisk (JK-1776) — only
        // the shipped shell earns this policy.
        exchange.getResponseHeaders()
                .set(
                        "Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-eval' blob: https://unpkg.com; "
                                + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://unpkg.com; "
                                + "font-src https://fonts.gstatic.com data:; worker-src blob:; "
                                // Preview pane: auth-fetch → blob: for in-repo images; https: for badges /
                                // remote README images (shields.io, etc.); data: for rare inlines.
                                + "img-src 'self' blob: data: https: http:;");
        if (snapshotVersion) {
            exchange.getResponseHeaders().set("Cache-Control", "no-cache"); // see snapshotVersion javadoc
        } else {
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.getResponseHeaders().set("ETag", classpathEtag);
            if (classpathEtag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return true;
            }
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(rel));
        if (head) {
            exchange.sendResponseHeaders(200, -1); // length unknowable without buffering the asset
            return true;
        }
        try (InputStream in = resource.openStream()) {
            exchange.sendResponseHeaders(200, 0); // chunked: length unknowable without buffering the asset
            in.transferTo(exchange.getResponseBody());
        }
        return true;
    }

    private static boolean notModifiedSince(String ifModifiedSince, Instant lastModified) {
        if (ifModifiedSince == null) return false;
        try {
            Instant since = Instant.from(HTTP_DATE.parse(ifModifiedSince.trim()));
            return !lastModified.isAfter(since);
        } catch (RuntimeException e) {
            return false; // unparseable header → serve the full response
        }
    }

    private static void copyExactly(FileChannel channel, OutputStream out, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = size;
        var target = Channels.newChannel(out);
        while (remaining > 0) {
            buffer.clear();
            if (remaining < buffer.capacity()) buffer.limit((int) remaining);
            int read = channel.read(buffer);
            // A shrunk file (this dir only ever expects appends) leaves the response short of its
            // Content-Length; the server then closes the connection rather than hanging the client.
            if (read < 0) break;
            buffer.flip();
            while (buffer.hasRemaining()) target.write(buffer);
            remaining -= read;
        }
    }

    private static String contentType(String rel) {
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
