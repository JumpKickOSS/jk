// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgeIdentityTest {

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ForgeIdentity.HttpForgeIdentity identity() {
        return new ForgeIdentity.HttpForgeIdentity(new Http());
    }

    @Test
    void reads_login_and_sends_bearer() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/user", ex -> {
            calls.incrementAndGet();
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"login\":\"octocat\",\"id\":1}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        URI endpoint = base.resolve("/user");
        var id = identity();
        assertThat(id.login(endpoint, "login", "tok-123")).contains("octocat");
        assertThat(seenAuth.get()).isEqualTo("Bearer tok-123");

        // Second lookup is cached — no extra HTTP call.
        assertThat(id.login(endpoint, "login", "tok-123")).contains("octocat");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void reads_gitlab_username_field() throws Exception {
        server.createContext("/user", ex -> {
            byte[] body = "{\"username\":\"alice\",\"id\":7}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        assertThat(identity().login(base.resolve("/user"), "username", "tok")).contains("alice");
    }

    @Test
    void non_2xx_yields_empty() {
        server.createContext("/user", ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        assertThat(identity().login(base.resolve("/user"), "login", "bad-token"))
                .isEmpty();
    }

    /**
     * The cache distinguishes tokens; it must not <em>hold</em> one. A live bearer token in a map
     * key is readable in a heap dump and printed by anything that dumps the map — so this reads the
     * real map after a real lookup and asserts the token is not in it, while two different tokens
     * still get two entries.
     */
    @Test
    void the_bearer_token_is_never_a_cache_key() throws Exception {
        server.createContext("/user", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            byte[] body = ("{\"login\":\"" + (auth.endsWith("token-A") ? "alice" : "bob") + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });

        URI endpoint = base.resolve("/user");
        var id = identity();
        assertThat(id.login(endpoint, "login", "ghp_live-token-A")).contains("alice");
        assertThat(id.login(endpoint, "login", "ghp_live-token-B")).contains("bob");

        Map<String, String> dump = cacheOf(id);
        assertThat(dump.keySet())
                .as("the map as a heap dump or a debug print would show it: %s", dump)
                .containsExactlyInAnyOrder(
                        endpoint + " " + SecretRedactor.KEY_PREFIX + Hashing.sha256Hex("ghp_live-token-A"),
                        endpoint + " " + SecretRedactor.KEY_PREFIX + Hashing.sha256Hex("ghp_live-token-B"));
        assertThat(String.join("\n", dump.keySet()))
                .doesNotContain("ghp_live-token-A")
                .doesNotContain("ghp_live-token-B");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cacheOf(ForgeIdentity.HttpForgeIdentity id) throws Exception {
        Field field = ForgeIdentity.HttpForgeIdentity.class.getDeclaredField("cache");
        field.setAccessible(true);
        return (Map<String, String>) field.get(id);
    }

    @Test
    void blank_token_yields_empty_without_calling() {
        // No context registered; a call would 404. Blank token must short-circuit.
        assertThat(identity().login(base.resolve("/user"), "login", "  ")).isEmpty();
    }
}
