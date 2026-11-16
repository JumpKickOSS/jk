// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionAlerts;
import cc.jumpkick.engine.decisions.Decisions;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.EventCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The front door's notifier for every paired engine: one event stream held open to each, with that
 * engine's own token, as the proxy reaches it. A decision the engine would notify about while it is
 * relayed (its {@code alert} event) becomes this engine's desktop notification, named for the engine
 * and linking to the decision under {@code /engines/<id>/}; once the decision settles there (its
 * {@code decision} event) the notification is withdrawn. Each change-log notice on the engine's bell
 * goes on this engine's bell too, named for the engine and linking under its prefix, the way this
 * engine treats its own: on the bell, not as a desktop notification. Its decision notices are not
 * copied: the decision's own notification stands for them.
 *
 * <p>The paired engines are read at each {@link #sync}, on the front door's heartbeat: a new pairing
 * gets a stream, one that went away or whose stream ended is dropped and asked again next beat. One
 * platform thread reads each stream; none is started for an engine already streamed.
 */
public final class FrontDoorRelay implements AutoCloseable {

    /** Where an engine is reached, its label, and the alert sink; the seams tests replace. */
    record Target(String id, int port, String token, String label) {}

    private final Supplier<@Nullable FrontDoor> door;
    private final Supplier<@Nullable Decisions> decisions;
    private final Supplier<@Nullable Notices> notices;
    private final Supplier<@Nullable String> baseUrl;
    private final Supplier<@Nullable String> token;
    private final Consumer<String> log;
    private final HttpClient client;
    private final Map<String, Thread> streams = new ConcurrentHashMap<>();
    private final Set<String> raised = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * @param baseUrl this front door's dashboard URL, which the notification's link opens
     * @param token the token that URL takes, which the dashboard reads from the link
     */
    public FrontDoorRelay(
            Supplier<@Nullable FrontDoor> door,
            Supplier<@Nullable Decisions> decisions,
            Supplier<@Nullable Notices> notices,
            Supplier<@Nullable String> baseUrl,
            Supplier<@Nullable String> token,
            Consumer<String> log) {
        this.door = door;
        this.decisions = decisions;
        this.notices = notices;
        this.baseUrl = baseUrl;
        this.token = token;
        this.log = log;
        this.client = Http.loopbackClientBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * The relay of the front door {@code front} serves, raising notifications through {@code home}'s
     * decisions and bell: the same server on the host engine; for a WSL guest holding 8910, its
     * front-door listener and its own band-port server.
     */
    public static FrontDoorRelay of(
            Supplier<@Nullable HttpEngineServer> front,
            Supplier<@Nullable HttpEngineServer> home,
            Consumer<String> log) {
        return new FrontDoorRelay(
                () -> {
                    HttpEngineServer f = front.get();
                    return f == null ? null : f.frontDoor();
                },
                () -> {
                    HttpEngineServer h = home.get();
                    return h == null ? null : h.decisions();
                },
                () -> {
                    HttpEngineServer h = home.get();
                    return h == null ? null : h.notices();
                },
                () -> {
                    HttpEngineServer f = front.get();
                    return f == null ? null : f.url();
                },
                () -> {
                    HttpEngineServer f = front.get();
                    return f == null ? null : f.token();
                },
                log);
    }

    /** A stream to every paired engine that has none; says in the heartbeat that this door relays. */
    public void sync() {
        FrontDoor d = door.get();
        if (d == null || closed) return;
        d.relays(true);
        for (GuestPairing.Pair pair : d.pairs().values()) {
            Thread running = streams.get(pair.id());
            if (running != null && running.isAlive()) continue;
            Target t = new Target(pair.id(), pair.port(), pair.token(), label(d, pair.id()));
            streams.put(pair.id(), SessionContext.startPlatform("jk-front-door-relay-" + pair.id(), () -> follow(t)));
        }
    }

    /** The engine's label as the front door lists it; its id when it does not answer. */
    private static String label(FrontDoor d, String id) {
        Map<String, Object> me = d.identityOfPaired(id);
        Object label = me == null ? null : me.get("label");
        return label instanceof String s && !s.isBlank() ? s : "engine " + id;
    }

    /** Reads {@code t}'s event stream until it ends or this relay closes. */
    void follow(Target t) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + t.port() + "/api/events"))
                .header("Authorization", "Bearer " + t.token())
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        try {
            HttpResponse<Stream<String>> r = client.send(req, HttpResponse.BodyHandlers.ofLines());
            if (r.statusCode() != 200) {
                r.body().close();
                return;
            }
            try (Stream<String> lines = r.body()) {
                String[] event = {""};
                StringBuilder data = new StringBuilder();
                lines.takeWhile(l -> !closed).forEach(line -> {
                    if (line.startsWith("event:")) {
                        event[0] = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).strip());
                    } else if (line.isEmpty()) {
                        if (!event[0].isEmpty() && !data.isEmpty()) frame(t, event[0], data.toString());
                        event[0] = "";
                        data.setLength(0);
                    }
                });
            }
        } catch (IOException e) {
            // The engine went away; the next beat asks again while it stays paired.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.accept("jk engine: front door relay from " + t.label() + ": " + e);
        }
    }

    /** One frame of {@code t}'s stream. */
    void frame(Target t, String event, String json) {
        Object parsed;
        try {
            parsed = MiniJson.parse(json);
        } catch (IllegalArgumentException notJson) {
            return;
        }
        if (!(parsed instanceof Map<?, ?> m)) return;
        switch (event) {
            case EventCatalog.SSE_ALERT -> alert(t, m);
            case EventCatalog.SSE_DECISION -> {
                String id = MiniJson.str(m, "id");
                if (id != null && !"open".equals(MiniJson.str(m, "state"))) withdraw(t, id);
            }
            case EventCatalog.SSE_NOTICE -> notice(t, m);
            default -> {}
        }
    }

    /** The relayed decision's own id at this front door: the engine's id and the decision's. */
    static String relayId(String engine, String decision) {
        return engine + "-" + decision;
    }

    private void alert(Target t, Map<?, ?> m) {
        Decisions d = decisions.get();
        String base = baseUrl.get();
        String id = MiniJson.str(m, "id");
        String question = MiniJson.str(m, "question");
        if (d == null || base == null || id == null || question == null || !"open".equals(MiniJson.str(m, "state"))) {
            return;
        }
        Map<String, Object> named = new LinkedHashMap<>();
        m.forEach((k, v) -> named.put(String.valueOf(k), v));
        named.put("id", relayId(t.id(), id));
        named.put("question", t.label() + ": " + question);
        Decision decision = Decision.fromMap(named).orElse(null);
        if (decision == null) return;
        String engineBase = (base.endsWith("/") ? base : base + "/") + FrontDoor.PREFIX.substring(1) + t.id() + "/";
        // Raised on this machine's desktop, which no transcript captures: signed with the front door's token.
        String link = DashboardLinks.signed(DashboardLinks.decision(engineBase, id), token.get());
        if (link == null || !raised.add(decision.id())) return;
        String what = d.relayAlert(decision, link);
        log.accept("jk engine: front door relayed " + t.label() + "'s decision " + id + ": " + what);
    }

    private void withdraw(Target t, String decision) {
        String id = relayId(t.id(), decision);
        if (!raised.remove(id)) return;
        Decisions d = decisions.get();
        DecisionAlerts alerts = d == null ? null : d.alerts();
        if (alerts != null) alerts.withdraw(id);
    }

    private void notice(Target t, Map<?, ?> change) {
        Notices bell = notices.get();
        if (bell == null || !"added".equals(MiniJson.str(change, "change"))) return;
        if (!(change.get("notice") instanceof Map<?, ?> n)) return;
        String kind = MiniJson.str(n, "kind");
        String text = MiniJson.str(n, "text");
        if (kind == null || text == null || n.get("decision") != null || kind.equals("decision")) return;
        String href = MiniJson.str(n, "href");
        String under = FrontDoor.PREFIX + t.id() + "/" + (href == null ? "" : href.replaceFirst("^/", ""));
        bell.add(kind, t.label() + ": " + text, under, null);
    }

    /** Stops relaying for now (a guest gave 8910 up); a later {@link #sync} starts again. */
    public void stop() {
        for (Thread t : streams.values()) t.interrupt();
        streams.clear();
        FrontDoor d = door.get();
        if (d != null) d.relays(false);
    }

    @Override
    public void close() {
        closed = true;
        stop();
    }
}
