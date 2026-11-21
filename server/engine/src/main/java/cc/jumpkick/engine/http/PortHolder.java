// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Who holds a loopback port this engine could not bind, in words {@code jk engine status} shows: the
 * home of the jk engine that answers {@code GET /api/identity} there, or another program. The
 * default home's engine asks when 8910 stays taken. When an engine of another home holds it,
 * stopping that engine is the cure, so the line names the home to stop it in; when no engine
 * answers, the default home's engine serves on a band port instead.
 */
public final class PortHolder {

    private static final Duration PROBE = Duration.ofSeconds(1);

    private PortHolder() {}

    /**
     * Why {@code port} could not be bound, naming its holder: {@code home} is the {@link #homeAt} of
     * the engine there, null for another program; {@code ownHome} is this engine's {@code JK_HOME}.
     */
    public static String describe(int port, @Nullable String home, String ownHome) {
        if (home == null) return "port " + port + " is held by another program";
        if (home.equals(ownHome)) return "port " + port + " is held by another engine of this home";
        return "port " + port + " is held by the jk engine of home " + home
                + "; run jk engine stop with JK_HOME set to that home to free it";
    }

    /** The {@code jkHome} the jk engine on {@code port} names; null when none answers there. */
    public static @Nullable String homeAt(int port) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + HttpEngineServer.IDENTITY))
                .timeout(PROBE)
                .GET()
                .build();
        try (HttpClient client = Http.loopbackClientBuilder()
                .connectTimeout(PROBE)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200 || !(MiniJson.parse(r.body()) instanceof Map<?, ?> m)) return null;
            return m.get("id") instanceof String && m.get("jkHome") instanceof String home && !home.isBlank()
                    ? home
                    : null;
        } catch (IOException | RuntimeException notAnEngine) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
