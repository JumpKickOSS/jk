// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.WhatsNew;
import cc.jumpkick.engine.api.JsonOut;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code GET /api/whats-new}: what the last update brought, for the dashboard's "What's new"
 * dialog. After an update to this engine's version it is the highlights of every release the
 * update moved across; otherwise this version's own. The highlights come from the {@code
 * whats-new.md} the install kept, which the release's signed manifest vouched for.
 */
final class HttpWhatsNewApi {

    static final String PATH = "/api/whats-new";

    private final String version;
    private final Supplier<SelfUpdateState> state;
    private final Path whatsNew;

    HttpWhatsNewApi(String version, Supplier<SelfUpdateState> state, Path whatsNew) {
        this.version = version;
        this.state = state;
        this.whatsNew = whatsNew;
    }

    void handle(HttpExchange exchange) throws IOException {
        HttpResponses.sendJson(exchange, 200, JsonOut.rawObject(body()).toString());
    }

    /**
     * {@code {version, from?, headline?, releaseUrl, entries: [{version, bullets: [markdown…]}]}}.
     * {@code from} and {@code headline} are there only when the last install was to this version.
     */
    Map<String, Object> body() {
        SelfUpdateState s = state.get();
        List<WhatsNew.Entry> all = WhatsNew.read(whatsNew);
        String from = version.equals(s.updatedTo()) ? s.updatedFrom() : null;
        List<WhatsNew.Entry> shown = from != null
                ? WhatsNew.between(all, from, version)
                : all.stream().filter(e -> e.version().equals(version)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        if (from != null) {
            out.put("from", from);
            out.put("headline", WhatsNew.headline(from, version));
        }
        out.put("releaseUrl", WhatsNew.releaseUrl(version));
        out.put(
                "entries",
                shown.stream()
                        .map(e -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("version", e.version());
                            m.put("bullets", e.bullets());
                            return m;
                        })
                        .toList());
        return out;
    }
}
