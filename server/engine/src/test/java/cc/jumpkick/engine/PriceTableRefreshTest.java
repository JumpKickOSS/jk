// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.Http;
import cc.jumpkick.prices.PriceTable;
import cc.jumpkick.prices.PriceTables;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.testing.DeadEndpoint;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's price-table check against a local server: a newer signed table is priced by the
 * running engine after one pass; offline, failed, unsigned, mis-signed, malformed and older tables
 * change nothing; the request carries nothing about the user or the project.
 */
class PriceTableRefreshTest {

    private static final String ETAG = "\"prices-v2\"";
    private static final String NEW_MODEL = "brand-new-model";

    private static KeyPair releaseKey;
    private static KeyPair otherKey;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Headers, Reply> primary = h -> new Reply(500, new byte[0]);
    private volatile Function<Headers, Reply> fallback = h -> new Reply(500, new byte[0]);
    private final List<String> logs = new ArrayList<>();

    private record Seen(String path, String query, Headers headers) {}

    private record Reply(int status, byte[] body) {}

    @BeforeAll
    static void keys() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        releaseKey = gen.generateKeyPair();
        otherKey = gen.generateKeyPair();
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/prices.toml", () -> primary);
        serve("/fallback/prices.toml", () -> fallback);
        server.start();
    }

    private void serve(String path, Supplier<Function<Headers, Reply>> handler) {
        server.createContext(path, exchange -> {
            Headers headers = exchange.getRequestHeaders();
            requests.add(new Seen(
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(), headers));
            Reply reply = handler.get().apply(headers);
            if (reply.status() == 200) exchange.getResponseHeaders().set("ETag", ETAG);
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) exchange.getResponseBody().write(reply.body());
            exchange.close();
        });
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private Path file() {
        return dir.resolve("store/prices.toml");
    }

    private PriceTableRefresh refresh(boolean offline, URI... sources) {
        return new PriceTableRefresh(
                logs::add,
                () -> new PriceTableRefresh.Sources(Http.failFast(), List.of(sources)),
                file(),
                () -> ReleaseVerifier.of(List.of(Base64.getEncoder()
                        .encodeToString(releaseKey.getPublic().getEncoded()))),
                () -> offline);
    }

    private PriceTableRefresh refresh() {
        return refresh(false, uri("/prices.toml"), uri("/fallback/prices.toml"));
    }

    private static Instant later(long days) {
        return PriceTable.builtIn().published().plusSeconds(days * 86_400);
    }

    private static String table(Instant published) {
        return "published = " + published + "\n\n[models.\"" + NEW_MODEL + "\"]\ninput = 3.0\noutput = 15.0\n"
                + "retrieved = \"2026-11-22\"\n";
    }

    private static byte[] signed(String content, KeyPair key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(content.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(signer.sign());
        return (content + PriceTable.SIGNATURE_PREFIX + sig + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Function<Headers, Reply> serving(byte[] body) {
        return h -> new Reply(200, body);
    }

    private boolean newModelPriced() {
        return PriceTables.current(file()).price(NEW_MODEL + "-high").isPresent();
    }

    @Test
    void a_model_added_to_the_published_table_is_priced_after_one_pass() throws Exception {
        assertThat(newModelPriced()).as("the built-in table does not know it").isFalse();
        primary = serving(signed(table(later(5)), releaseKey));

        refresh().refreshQuietly();

        assertThat(newModelPriced()).isTrue();
        assertThat(PriceTables.current(file()).price(NEW_MODEL).orElseThrow().output())
                .isEqualTo(15.0);
        assertThat(logs).singleElement().asString().contains("price table updated");
    }

    @Test
    void an_unchanged_table_is_a_conditional_request_answered_with_304() throws Exception {
        byte[] body = signed(table(later(5)), releaseKey);
        primary = h -> ETAG.equals(h.getFirst("If-None-Match")) ? new Reply(304, new byte[0]) : new Reply(200, body);
        refresh().refreshQuietly();
        long modified = Files.getLastModifiedTime(file()).toMillis();

        refresh().refreshQuietly();

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).headers().getFirst("If-None-Match")).isEqualTo(ETAG);
        assertThat(Files.getLastModifiedTime(file()).toMillis()).isEqualTo(modified);
        assertThat(newModelPriced()).isTrue();
    }

    @Test
    void offline_asks_nothing_and_changes_nothing() throws Exception {
        primary = serving(signed(table(later(5)), releaseKey));

        refresh(true, uri("/prices.toml")).refreshQuietly();

        assertThat(requests).isEmpty();
        assertThat(file()).doesNotExist();
        assertThat(newModelPriced()).isFalse();
    }

    @Test
    void a_failed_fetch_keeps_the_table_on_disk_and_says_nothing() throws Exception {
        primary = serving(signed(table(later(5)), releaseKey));
        refresh().refreshQuietly();
        byte[] kept = Files.readAllBytes(file());
        logs.clear();

        primary = h -> new Reply(500, new byte[0]);
        refresh().refreshQuietly();
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            refresh(false, dead.uri("/prices.toml")).refreshQuietly();
        }

        assertThat(Files.readAllBytes(file())).isEqualTo(kept);
        assertThat(newModelPriced()).isTrue();
        assertThat(logs).isEmpty();
    }

    @Test
    void the_fallback_source_is_asked_only_when_the_first_fails() throws Exception {
        fallback = serving(signed(table(later(5)), releaseKey));

        refresh().refreshQuietly();

        assertThat(requests).extracting(Seen::path).containsExactly("/prices.toml", "/fallback/prices.toml");
        assertThat(newModelPriced()).isTrue();

        requests.clear();
        primary = serving(signed(table(later(6)), releaseKey));
        refresh().refreshQuietly();
        assertThat(requests).extracting(Seen::path).containsExactly("/prices.toml");
    }

    @Test
    void a_table_that_does_not_verify_or_parse_is_ignored() throws Exception {
        String content = table(later(5));
        byte[] good = signed(content, releaseKey);
        byte[] tampered = new String(good, StandardCharsets.UTF_8)
                .replace("output = 15.0", "output = 0.0")
                .getBytes(StandardCharsets.UTF_8);
        List<byte[]> bad = List.of(
                content.getBytes(StandardCharsets.UTF_8),
                signed(content, otherKey),
                tampered,
                signed("published = 2099-01-01T00:00:00Z\nmodels = 3\n", releaseKey),
                (content + "# signature not-base64!\n").getBytes(StandardCharsets.UTF_8));

        for (byte[] body : bad) {
            primary = serving(body);
            refresh().refreshQuietly();
            assertThat(file()).doesNotExist();
            assertThat(newModelPriced()).isFalse();
        }
        assertThat(logs).isEmpty();
    }

    @Test
    void a_table_published_before_the_one_in_effect_is_not_kept() throws Exception {
        primary = serving(signed(table(later(5)), releaseKey));
        refresh().refreshQuietly();
        byte[] kept = Files.readAllBytes(file());

        primary = serving(signed(table(later(2)).replace("15.0", "1.0"), releaseKey));
        refresh().refreshQuietly();

        assertThat(Files.readAllBytes(file())).isEqualTo(kept);
        assertThat(PriceTables.current(file()).price(NEW_MODEL).orElseThrow().output())
                .isEqualTo(15.0);
    }

    @Test
    void the_request_carries_nothing_about_the_user_or_the_project() throws Exception {
        primary = serving(signed(table(later(5)), releaseKey));
        refresh().refreshQuietly();
        primary = h -> new Reply(304, new byte[0]);
        refresh().refreshQuietly();

        Set<String> allowed = Set.of(
                "host",
                "user-agent",
                "accept-encoding",
                "if-none-match",
                "if-modified-since",
                "connection",
                "content-length",
                "upgrade",
                "http2-settings");
        assertThat(requests).hasSize(2).allSatisfy(seen -> {
            assertThat(seen.path()).isEqualTo("/prices.toml");
            assertThat(seen.query()).isNull();
            assertThat(seen.headers().keySet())
                    .extracting(name -> name.toLowerCase(Locale.ROOT))
                    .isSubsetOf(allowed);
            assertThat(seen.headers().getFirst("User-Agent"))
                    .as("the JDK's own client name and version, nothing of jk's or the host's")
                    .startsWith("Java-http-client/");
        });
        assertThat(requests.get(1).headers().getFirst("If-None-Match")).isEqualTo(ETAG);
    }
}
