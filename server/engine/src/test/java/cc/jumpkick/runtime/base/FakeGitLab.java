// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.forge.GitLabProject;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.JkThreads;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A loopback GitLab REST API for one project, {@code acme/tools/app}: generic packages listed (by
 * a fuzzy name, as GitLab does), their files listed, uploaded and downloaded, and releases read
 * and created with asset links. Every request must carry {@link #TOKEN} as a bearer token or
 * {@link #JOB_TOKEN} as {@code JOB-TOKEN}. {@link #refuse} answers every request with one status.
 */
public final class FakeGitLab implements AutoCloseable {

    public static final String TOKEN = "fake-gitlab-token-for-tests";
    public static final String JOB_TOKEN = "fake-gitlab-job-token-for-tests";
    public static final String PROJECT = "/api/v4/projects/acme%2Ftools%2Fapp";

    /** One generic package and its files, oldest first. */
    public record Package(long id, String name, String version, List<PackageFile> files) {}

    public record PackageFile(long id, String name, byte[] body) {}

    /** A release: its tag and the links it was created with, by name to URL. */
    public record Release(String tag, @Nullable String ref, Map<String, String> links) {}

    public final List<Package> packages = new CopyOnWriteArrayList<>();
    public final List<Release> releases = new CopyOnWriteArrayList<>();

    /** Requests that change something: uploads and release creation. */
    public final AtomicInteger writes = new AtomicInteger();

    /** Package files served as bytes because a listing gave no checksum. */
    public final AtomicInteger downloads = new AtomicInteger();

    /** False to list package files without {@code file_sha256}. */
    public volatile boolean checksums = true;

    /** False to refuse a second file of a name, as a group that disallows duplicates does. */
    public volatile boolean duplicates = true;

    /** When set, every request is answered with this status. */
    public volatile int refuse;

    /** The authentication header each request carried: {@code bearer} or {@code job}. */
    public final List<String> auth = new CopyOnWriteArrayList<>();

    private final AtomicLong ids = new AtomicLong(100);
    private final HttpServer server;

    public FakeGitLab() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>}: the instance's web root. */
    public URI web() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The project as origin names it. */
    public GitLabProject project() {
        return new GitLabProject(web(), "acme/tools/app");
    }

    /** The store of {@code tag}'s release, its package {@code app}/{@code version}. */
    public ReleaseGitLab store(String version, String tag, String token, boolean job) {
        GitLabProject project = project();
        return new ReleaseGitLab(new Http(), project, project.api(), "app", version, tag, token, job, "c0ffee");
    }

    /** A file already in package {@code name}/{@code version}. */
    public void file(String name, String version, String file, String text) {
        add(name, version, file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A release already there, linking {@code links}. */
    public void release(String tag, String... links) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : links) m.put(l, web() + PROJECT + "/packages/generic/app/x/" + l);
        releases.add(new Release(tag, null, m));
    }

    /** The text of the latest {@code file} in package {@code app}/{@code version}, or {@code null}. */
    public @Nullable String text(String version, String file) {
        PackageFile f = latest("app", version, file);
        return f == null ? null : new String(f.body(), StandardCharsets.UTF_8);
    }

    public @Nullable Release release(String tag) {
        return releases.stream().filter(r -> r.tag().equals(tag)).findFirst().orElse(null);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void add(String name, String version, String file, byte[] body) {
        Package p = packages.stream()
                .filter(x -> x.name().equals(name) && x.version().equals(version))
                .findFirst()
                .orElse(null);
        if (p == null) {
            p = new Package(ids.incrementAndGet(), name, version, new CopyOnWriteArrayList<>());
            packages.add(p);
        }
        p.files().add(new PackageFile(ids.incrementAndGet(), file, body));
    }

    private @Nullable PackageFile latest(String name, String version, String file) {
        PackageFile out = null;
        for (Package p : packages) {
            if (!p.name().equals(name) || !p.version().equals(version)) continue;
            for (PackageFile f : p.files()) if (f.name().equals(file)) out = f;
        }
        return out;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (refuse != 0) {
                send(exchange, refuse, "{\"message\":\"refused\"}");
                return;
            }
            String bearer = exchange.getRequestHeaders().getFirst("Authorization");
            String job = exchange.getRequestHeaders().getFirst("JOB-TOKEN");
            if (("Bearer " + TOKEN).equals(bearer)) {
                auth.add("bearer");
            } else if (JOB_TOKEN.equals(job)) {
                auth.add("job");
            } else {
                send(exchange, 401, "{\"message\":\"401 Unauthorized\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String raw = exchange.getRequestURI().getRawPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (!raw.startsWith(PROJECT + "/")) {
                send(exchange, 404, "{\"message\":\"404 Project Not Found\"}");
                return;
            }
            String path = raw.substring(PROJECT.length());
            String[] p = path.split("/");
            if (path.equals("/packages") && method.equals("GET")) {
                String fuzzy = query.getOrDefault("package_name", "");
                List<Object> out = new ArrayList<>();
                for (Package pkg : packages) {
                    if (!pkg.name().contains(fuzzy)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", pkg.id());
                    m.put("name", pkg.name());
                    m.put("version", pkg.version());
                    m.put("package_type", "generic");
                    out.add(m);
                }
                send(exchange, 200, MiniJson.write(out));
            } else if (path.matches("/packages/\\d+/package_files") && method.equals("GET")) {
                long id = Long.parseLong(p[2]);
                List<Object> out = new ArrayList<>();
                for (Package pkg : packages) {
                    if (pkg.id() != id) continue;
                    for (PackageFile f : pkg.files()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", f.id());
                        m.put("package_id", id);
                        m.put("file_name", f.name());
                        m.put("size", (long) f.body().length);
                        if (checksums) m.put("file_sha256", Hashing.sha256Hex(f.body()));
                        out.add(m);
                    }
                }
                send(exchange, 200, MiniJson.write(out));
            } else if (path.startsWith("/packages/generic/") && p.length == 6) {
                String name = decode(p[3]);
                String version = decode(p[4]);
                String file = decode(p[5]);
                if (method.equals("PUT")) {
                    synchronized (this) {
                        if (!duplicates && latest(name, version, file) != null) {
                            send(exchange, 400, "{\"message\":\"Duplicate package is not allowed\"}");
                            return;
                        }
                        writes.incrementAndGet();
                        add(name, version, file, body);
                    }
                    send(exchange, 201, "{\"message\":\"201 Created\"}");
                } else {
                    PackageFile f = latest(name, version, file);
                    if (f == null) {
                        send(exchange, 404, "{\"message\":\"404 Not Found\"}");
                        return;
                    }
                    downloads.incrementAndGet();
                    exchange.sendResponseHeaders(200, f.body().length);
                    exchange.getResponseBody().write(f.body());
                }
            } else if (path.equals("/releases") && method.equals("POST")) {
                Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                String tag = String.valueOf(MiniJson.str(req, "tag_name"));
                synchronized (this) {
                    if (release(tag) != null) {
                        send(exchange, 409, "{\"message\":\"Release already exists\"}");
                        return;
                    }
                    writes.incrementAndGet();
                    Map<String, String> links = new LinkedHashMap<>();
                    for (Object l : MiniJson.list(MiniJson.get(req, "assets"), "links")) {
                        links.put(String.valueOf(MiniJson.str(l, "name")), String.valueOf(MiniJson.str(l, "url")));
                    }
                    Release r = new Release(tag, MiniJson.str(req, "ref"), links);
                    releases.add(r);
                    send(exchange, 201, MiniJson.write(json(r)));
                }
            } else if (path.startsWith("/releases/") && method.equals("GET")) {
                Release r = release(decode(path.substring("/releases/".length())));
                if (r == null) {
                    send(exchange, 404, "{\"message\":\"404 Not Found\"}");
                } else {
                    send(exchange, 200, MiniJson.write(json(r)));
                }
            } else {
                send(exchange, 404, "{\"message\":\"404 Not Found\"}");
            }
        }
    }

    private static Map<String, Object> json(Release r) {
        List<Object> links = new ArrayList<>();
        r.links().forEach((name, url) -> links.add(Map.of("name", name, "url", url)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag_name", r.tag());
        m.put("assets", Map.of("links", links));
        return m;
    }

    private static Map<String, String> query(@Nullable String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
