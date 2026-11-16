// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.machine.MachineEnvironments;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The machine's environments on the Windows engine: {@code GET /api/machine/environments} lists the
 * host and each WSL distro with its JumpKick, its engine and its operation's progress; {@code POST
 * …/install} and {@code POST …/remove} with {@code {"distros": [<name>…]}} queue the one operation
 * ({@link MachineEnvironments}) and answer what each name was taken as or refused for.
 *
 * <p>Installing or removing is control of the machine, so beyond the bearer token every {@code /api}
 * call needs (on the Windows engine, the front door's token), a POST that carries an {@code Origin}
 * other than this listener's own is refused: no other page reaches it, even one that came by a token.
 */
final class HttpMachineApi {

    static final String PATH = "/api/machine/environments";
    static final String INSTALL = PATH + "/install";
    static final String REMOVE = PATH + "/remove";

    /** A request names a few distros; anything bigger is not one. */
    private static final int MAX_BODY = 8 * 1024;

    private static final int MAX_NAMES = 32;

    private final MachineEnvironments machine;
    private final IntSupplier port;

    HttpMachineApi(MachineEnvironments machine, IntSupplier port) {
        this.machine = machine;
        this.port = port;
    }

    void handleList(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(machine.list()).toString());
    }

    void handleInstall(HttpExchange exchange) throws IOException {
        handle(exchange, MachineEnvironments.Action.INSTALL);
    }

    void handleRemove(HttpExchange exchange) throws IOException {
        handle(exchange, MachineEnvironments.Action.REMOVE);
    }

    private void handle(HttpExchange exchange, MachineEnvironments.Action action) throws IOException {
        if (!sameOrigin(exchange.getRequestHeaders().getFirst("Origin"), port.getAsInt())) {
            error(exchange, 403, "refused: this request came from another page");
            return;
        }
        List<String> names = names(HttpRequests.body(exchange, MAX_BODY));
        if (names == null) {
            error(exchange, 400, "expected {\"distros\": [<name>, …]} naming 1 to " + MAX_NAMES + " distros");
            return;
        }
        MachineEnvironments.Started started = machine.start(action, names);
        HttpResponses.sendJson(
                exchange,
                started.accepted().isEmpty() ? 409 : 202,
                JsonOut.rawObject(started.toMap()).toString());
    }

    /** The names {@code body} lists under {@code distros}; null when it is not that shape. */
    static @Nullable List<String> names(String body) {
        Object parsed;
        try {
            parsed = MiniJson.parse(body);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> m) || !(m.get("distros") instanceof List<?> list)) return null;
        if (list.isEmpty() || list.size() > MAX_NAMES) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) return null;
            out.add(s.strip());
        }
        return out;
    }

    /**
     * True when {@code origin} is absent (a client that is not a page: the CLI, an agent's MCP
     * host) or names this listener: {@code http}, a loopback or this machine's host, this port.
     */
    static boolean sameOrigin(@Nullable String origin, int port) {
        if (origin == null) return true;
        try {
            URI u = URI.create(origin.strip());
            String authority = u.getRawAuthority();
            String path = u.getRawPath();
            if (!"http".equals(u.getScheme()) || authority == null || (path != null && !path.isEmpty())) return false;
            return u.getPort() == port && HostCheck.allowed(authority, port);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        HttpResponses.sendJson(
                exchange, status, JsonOut.object().put("error", message).toString());
    }
}
