// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.engine.api.JsonOut;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * The dashboard's Update pill: {@code GET /api/self-update} says whether a downloaded release is
 * on offer, {@code POST /api/self-update/install} installs it now (the new engine displaces this
 * one, builds in flight finish here, and the page reloads onto the new engine), and {@code POST
 * /api/self-update/snooze} is "not now" on every surface until the next check. Both POSTs sit
 * behind the bearer token like every {@code /api} call, so no other page can trigger them.
 */
final class HttpSelfUpdateApi {

    static final String PATH = "/api/self-update";
    static final String INSTALL = PATH + "/install";
    static final String SNOOZE = PATH + "/snooze";

    private final String version;
    private final Supplier<SelfUpdateControl> control;

    HttpSelfUpdateApi(String version, Supplier<SelfUpdateControl> control) {
        this.version = version;
        this.control = control;
    }

    /** {@code {running, offer, installing}}; {@code offer} is null when nothing is on offer. */
    void handleState(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, state().toString());
    }

    /** 202 with the state once the install has started; 409 when no release is downloaded. */
    void handleInstall(HttpExchange exchange) throws IOException {
        if (!control.get().installNow()) {
            HttpResponses.sendJson(
                    exchange,
                    409,
                    JsonOut.object()
                            .put("error", "no update is ready to install")
                            .toString());
            return;
        }
        HttpResponses.sendJson(exchange, 202, state().toString());
    }

    /** 200 with the state after "not now". */
    void handleSnooze(HttpExchange exchange) throws IOException {
        control.get().snooze();
        HttpResponses.sendJson(exchange, 200, state().toString());
    }

    JsonOut state() {
        SelfUpdateControl c = control.get();
        return JsonOut.object()
                .put("running", version)
                .put("offer", c.offer().orElse(null))
                .put("installing", c.installing());
    }
}
