// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

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
 * A loopback GitHub REST API for one repository, {@code acme/app}: releases listed with their
 * drafts, created, read and patched, and assets listed, uploaded, downloaded and deleted. Every
 * request must carry {@link #TOKEN}. {@link #refuse} answers every request with one status and
 * headers instead, as a token without the scope or a rate limit does.
 */
public final class FakeGitHub implements AutoCloseable {

    public static final String TOKEN = "fake-github-token-for-tests";

    /** One release; {@code assets} by name. */
    public static final class Release {
        public final long id;
        public final String tag;
        public volatile boolean draft;
        public final @Nullable String commitish;
        public final Map<String, Asset> assets = new LinkedHashMap<>();

        Release(long id, String tag, boolean draft, @Nullable String commitish) {
            this.id = id;
            this.tag = tag;
            this.draft = draft;
            this.commitish = commitish;
        }
    }

    /** One asset: its bytes and upload state. */
    public record Asset(long id, String name, byte[] body, String state) {}

    public final List<Release> releases = new CopyOnWriteArrayList<>();

    /** Requests that change something: create, upload, patch, delete. */
    public final AtomicInteger writes = new AtomicInteger();

    /** Assets the API serves as bytes because a listing gave no digest. */
    public final AtomicInteger downloads = new AtomicInteger();

    /** False to list assets without {@code digest}, as a server older than the field does. */
    public volatile boolean digests = true;

    /** When set, every request is answered with this status and {@link #refusalHeaders}. */
    public volatile int refuse;

    public volatile Map<String, String> refusalHeaders = Map.of();

    private final AtomicLong ids = new AtomicLong(100);
    private final HttpServer server;

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>}: what {@code GITHUB_API_URL} names. */
    public URI api() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The store of {@code tag}'s release in {@code acme/app}, written with {@code token}. */
    public ReleaseGitHub store(String tag, String token) {
        URI repos = URI.create(api() + "/repos/acme/app");
        return new ReleaseGitHub(
                new Http(), repos, URI.create(api() + "/uploads/repos/acme/app"), tag, token, "c0ffee");
    }

    /** A release already there. */
    public Release release(String tag, boolean draft) {
        Release r = new Release(ids.incrementAndGet(), tag, draft, null);
        releases.add(r);
        return r;
    }

    /** An uploaded asset already in {@code release}. */
    public void asset(Release release, String name, String text) {
        add(release, name, text.getBytes(StandardCharsets.UTF_8), "uploaded");
    }

    /** An asset a broken upload left empty. */
    public void starter(Release release, String name) {
        add(release, name, new byte[0], "starter");
    }

    /** The text of {@code name} in {@code tag}'s release, or {@code null}. */
    public @Nullable String text(String tag, String name) {
        for (Release r : releases) {
            if (r.tag.equals(tag) && r.assets.get(name) instanceof Asset a && "uploaded".equals(a.state())) {
                return new String(a.body(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void add(Release release, String name, byte[] body, String state) {
        synchronized (release.assets) {
            release.assets.put(name, new Asset(ids.incrementAndGet(), name, body, state));
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (refuse != 0) {
                refusalHeaders.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
                send(exchange, refuse, "{\"message\":\"refused\"}");
                return;
            }
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{\"message\":\"Bad credentials\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] p = path.split("/");
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.equals("/repos/acme/app/releases") && method.equals("GET")) {
                List<Object> out = new ArrayList<>();
                for (Release r : releases) out.add(json(r));
                send(exchange, 200, MiniJson.write(out));
            } else if (path.equals("/repos/acme/app/releases") && method.equals("POST")) {
                writes.incrementAndGet();
                Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                Release r = new Release(
                        ids.incrementAndGet(),
                        String.valueOf(MiniJson.str(req, "tag_name")),
                        Boolean.TRUE.equals(MiniJson.get(req, "draft")),
                        MiniJson.str(req, "target_commitish"));
                releases.add(r);
                send(exchange, 201, MiniJson.write(json(r)));
            } else if (path.startsWith("/repos/acme/app/releases/assets/")) {
                Asset asset = assetById(Long.parseLong(p[6]));
                if (asset == null) {
                    send(exchange, 404, "{}");
                } else if (method.equals("DELETE")) {
                    writes.incrementAndGet();
                    for (Release r : releases) {
                        synchronized (r.assets) {
                            r.assets.values().removeIf(a -> a.id() == asset.id());
                        }
                    }
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    downloads.incrementAndGet();
                    exchange.sendResponseHeaders(200, asset.body().length);
                    exchange.getResponseBody().write(asset.body());
                }
            } else if (path.matches("/repos/acme/app/releases/\\d+/assets")) {
                Release r = byId(Long.parseLong(p[5]));
                if (r == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                List<Object> out = new ArrayList<>();
                synchronized (r.assets) {
                    for (Asset a : r.assets.values()) out.add(json(a));
                }
                send(exchange, 200, MiniJson.write(out));
            } else if (path.matches("/repos/acme/app/releases/\\d+")) {
                Release r = byId(Long.parseLong(p[5]));
                if (r == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                if (method.equals("PATCH")) {
                    writes.incrementAndGet();
                    Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                    if (MiniJson.get(req, "draft") instanceof Boolean draft) r.draft = draft;
                }
                send(exchange, 200, MiniJson.write(json(r)));
            } else if (path.matches("/uploads/repos/acme/app/releases/\\d+/assets") && method.equals("POST")) {
                Release r = byId(Long.parseLong(p[6]));
                String name = URLDecoder.decode(
                        exchange.getRequestURI().getRawQuery().replaceFirst("^name=", ""), StandardCharsets.UTF_8);
                if (r == null || !r.draft) {
                    send(exchange, 422, "{\"message\":\"release is immutable\"}");
                    return;
                }
                synchronized (r.assets) {
                    if (r.assets.containsKey(name)) {
                        send(exchange, 422, "{\"message\":\"already_exists\"}");
                        return;
                    }
                    writes.incrementAndGet();
                    add(r, name, body, "uploaded");
                    send(exchange, 201, MiniJson.write(json(r.assets.get(name))));
                }
            } else {
                send(exchange, 404, "{\"message\":\"Not Found\"}");
            }
        }
    }

    private Map<String, Object> json(Release r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("tag_name", r.tag);
        m.put("name", r.tag);
        m.put("draft", r.draft);
        m.put("upload_url", api() + "/uploads/repos/acme/app/releases/" + r.id + "/assets{?name,label}");
        return m;
    }

    private Map<String, Object> json(@Nullable Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null) return m;
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("size", (long) a.body().length);
        m.put("state", a.state());
        m.put("digest", digests ? "sha256:" + Hashing.sha256Hex(a.body()) : null);
        return m;
    }

    private @Nullable Release byId(long id) {
        return releases.stream().filter(r -> r.id == id).findFirst().orElse(null);
    }

    private @Nullable Asset assetById(long id) {
        for (Release r : releases) {
            synchronized (r.assets) {
                for (Asset a : r.assets.values()) if (a.id() == id) return a;
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
