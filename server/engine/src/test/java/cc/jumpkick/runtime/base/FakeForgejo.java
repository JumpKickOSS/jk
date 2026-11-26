// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.forge.ForgejoRepository;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A loopback Forgejo API for one repository, {@code acme/app}: releases listed with their drafts,
 * created and patched, and attachments listed, uploaded as a raw body and downloaded by UUID under
 * {@code /attachments/}. Every request must carry {@code Authorization: token} {@link #TOKEN}.
 * {@link #refuse} answers every request with one status instead.
 */
public final class FakeForgejo implements AutoCloseable {

    public static final String TOKEN = "fake-forgejo-token-for-tests";
    private static final String REPOS = "/api/v1/repos/acme/app";

    /** One release; {@code assets} by name. */
    public static final class Release {
        public final long id;
        public final String tag;
        public volatile boolean draft;
        public final @Nullable String commitish;
        public final Map<String, Attachment> assets = new LinkedHashMap<>();

        Release(long id, String tag, boolean draft, @Nullable String commitish) {
            this.id = id;
            this.tag = tag;
            this.draft = draft;
            this.commitish = commitish;
        }
    }

    public record Attachment(long id, String name, String uuid, byte[] body) {}

    public final List<Release> releases = new CopyOnWriteArrayList<>();

    /** Requests that change something: create, upload, patch, delete. */
    public final AtomicInteger writes = new AtomicInteger();

    /** Attachments served as bytes. */
    public final AtomicInteger downloads = new AtomicInteger();

    /** When set, every request is answered with this status. */
    public volatile int refuse;

    private final AtomicLong ids = new AtomicLong(100);
    private final HttpServer server;

    public FakeForgejo() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(JkThreads.platform());
        server.createContext("/", this::handle);
        server.start();
    }

    /** {@code http://127.0.0.1:<port>}: the instance's web root. */
    public URI web() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The repository as origin names it. */
    public ForgejoRepository repository() {
        return new ForgejoRepository(web(), "acme", "app");
    }

    /** The store of {@code tag}'s release in {@code acme/app}, written with {@code token}. */
    public ReleaseForgejo store(String tag, String token) {
        ForgejoRepository repo = repository();
        return new ReleaseForgejo(new Http(), repo, repo.repos(repo.api()), tag, token, "c0ffee");
    }

    /** A release already there. */
    public Release release(String tag, boolean draft) {
        Release r = new Release(ids.incrementAndGet(), tag, draft, null);
        releases.add(r);
        return r;
    }

    /** An attachment already in {@code release}. */
    public void asset(Release release, String name, String text) {
        add(release, name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** The text of {@code name} in {@code tag}'s release, or {@code null}. */
    public @Nullable String text(String tag, String name) {
        for (Release r : releases) {
            if (r.tag.equals(tag) && r.assets.get(name) instanceof Attachment a) {
                return new String(a.body(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void add(Release release, String name, byte[] body) {
        synchronized (release.assets) {
            release.assets.put(
                    name,
                    new Attachment(
                            ids.incrementAndGet(), name, UUID.randomUUID().toString(), body));
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (refuse != 0) {
                send(exchange, refuse, "{\"message\":\"refused\"}");
                return;
            }
            if (!("token " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{\"message\":\"token is required\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] p = path.split("/");
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.startsWith("/attachments/")) {
                Attachment a = byUuid(p[2]);
                if (a == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                downloads.incrementAndGet();
                exchange.sendResponseHeaders(200, a.body().length);
                exchange.getResponseBody().write(a.body());
            } else if (path.equals(REPOS + "/releases") && method.equals("GET")) {
                List<Object> out = new ArrayList<>();
                for (Release r : releases) out.add(json(r));
                send(exchange, 200, MiniJson.write(out));
            } else if (path.equals(REPOS + "/releases") && method.equals("POST")) {
                writes.incrementAndGet();
                Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                Release r = new Release(
                        ids.incrementAndGet(),
                        String.valueOf(MiniJson.str(req, "tag_name")),
                        Boolean.TRUE.equals(MiniJson.get(req, "draft")),
                        MiniJson.str(req, "target_commitish"));
                releases.add(r);
                send(exchange, 201, MiniJson.write(json(r)));
            } else if (path.matches(REPOS + "/releases/\\d+/assets") && method.equals("GET")) {
                Release r = byId(Long.parseLong(p[7]));
                if (r == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                List<Object> out = new ArrayList<>();
                synchronized (r.assets) {
                    for (Attachment a : r.assets.values()) out.add(json(r, a));
                }
                send(exchange, 200, MiniJson.write(out));
            } else if (path.matches(REPOS + "/releases/\\d+/assets") && method.equals("POST")) {
                Release r = byId(Long.parseLong(p[7]));
                String name = URLDecoder.decode(
                        exchange.getRequestURI().getRawQuery().replaceFirst("^name=", ""), StandardCharsets.UTF_8);
                if (r == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                writes.incrementAndGet();
                add(r, name, body);
                send(exchange, 201, MiniJson.write(json(r, r.assets.get(name))));
            } else if (path.matches(REPOS + "/releases/\\d+/assets/\\d+") && method.equals("DELETE")) {
                Release r = byId(Long.parseLong(p[7]));
                long id = Long.parseLong(p[9]);
                if (r != null) {
                    writes.incrementAndGet();
                    synchronized (r.assets) {
                        r.assets.values().removeIf(a -> a.id() == id);
                    }
                }
                exchange.sendResponseHeaders(204, -1);
            } else if (path.matches(REPOS + "/releases/\\d+") && method.equals("PATCH")) {
                Release r = byId(Long.parseLong(p[7]));
                if (r == null) {
                    send(exchange, 404, "{}");
                    return;
                }
                writes.incrementAndGet();
                Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                if (MiniJson.get(req, "draft") instanceof Boolean draft) r.draft = draft;
                send(exchange, 200, MiniJson.write(json(r)));
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
        return m;
    }

    private Map<String, Object> json(Release r, @Nullable Attachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a == null) return m;
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("size", (long) a.body().length);
        m.put("uuid", a.uuid());
        m.put("browser_download_url", web() + "/acme/app/releases/download/" + r.tag + "/" + a.name());
        return m;
    }

    private @Nullable Release byId(long id) {
        return releases.stream().filter(r -> r.id == id).findFirst().orElse(null);
    }

    private @Nullable Attachment byUuid(String uuid) {
        for (Release r : releases) {
            synchronized (r.assets) {
                for (Attachment a : r.assets.values()) if (a.uuid().equals(uuid)) return a;
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
