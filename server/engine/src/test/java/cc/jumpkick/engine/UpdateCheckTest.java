// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.http.Http;
import cc.jumpkick.prices.PriceTable;
import cc.jumpkick.prices.PriceTables;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.testing.DeadEndpoint;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.updates.UpdateManifest;
import cc.jumpkick.updates.UpdateState;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's update check against a local server: a model added to the published price table is
 * priced by the running engine after one check, signed here or by the scripts that publish it
 * ({@code hosting/jk-updates}); a catalog arrives the same way; offline, failed,
 * unsigned, mis-signed, tampered, malformed and older manifests and files change nothing; the
 * request carries nothing about the user or the project.
 */
class UpdateCheckTest {

    private static final String NEW_MODEL = "brand-new-model";

    private static KeyPair releaseKey;
    private static KeyPair otherKey;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    /** Path to the reply it gets; a path with none answers 404. */
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private final List<String> logs = new ArrayList<>();
    private final List<String> catalogs = new ArrayList<>();
    private final List<UpdateManifest> answered = new ArrayList<>();
    private final FakeClock clock = new FakeClock();

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
        server.createContext("/", exchange -> {
            Headers headers = exchange.getRequestHeaders();
            String path = exchange.getRequestURI().getPath();
            requests.add(new Seen(path, exchange.getRequestURI().getRawQuery(), headers));
            Reply reply = replies.getOrDefault(path, new Reply(404, new byte[0]));
            if (path.endsWith("updates.json") && reply.status() == 200) {
                String etag = etag(reply.body());
                if (etag.equals(headers.getFirst("If-None-Match"))) reply = new Reply(304, new byte[0]);
                exchange.getResponseHeaders().set("ETag", etag);
            }
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) exchange.getResponseBody().write(reply.body());
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String etag(byte[] body) {
        return "\"" + Hashing.sha256Hex(body).substring(0, 16) + "\"";
    }

    private String publishedEtag() {
        return etag(Objects.requireNonNull(replies.get("/updates.json")).body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private Path pricesFile() {
        return dir.resolve("store/prices.toml");
    }

    private UpdateCheck check(boolean offline, URI... manifests) {
        Map<String, UpdateCheck.Applier> appliers = Map.of(
                "prices", (entry, file) -> UpdateCheck.applyPrices(file.bytes(), pricesFile(), logs::add),
                "libraries", (entry, file) -> catalogs.add(new String(file.bytes(), StandardCharsets.UTF_8)));
        return new UpdateCheck(
                logs::add,
                () -> new UpdateCheck.Sources(Http.failFast(), List.of(manifests)),
                dir.resolve("store/updates"),
                () -> ReleaseVerifier.of(List.of(Base64.getEncoder()
                        .encodeToString(releaseKey.getPublic().getEncoded()))),
                () -> offline,
                appliers,
                answered::add,
                clock);
    }

    private UpdateCheck check() {
        return check(false, uri("/updates.json"), uri("/fallback/updates.json"));
    }

    private static Instant later(long days) {
        return PriceTable.builtIn().published().plusSeconds(days * 86_400);
    }

    private static byte[] table(Instant published, double output) {
        return ("published = " + published + "\n\n[models.\"" + NEW_MODEL + "\"]\ninput = 3.0\noutput = " + output
                        + "\nretrieved = \"2026-11-22\"\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String manifest(Instant published, String entries) {
        return "{\n  \"published\": \"" + published + "\",\n  \"data\": {" + entries + "}\n}\n";
    }

    private static String entry(String kind, String version, String file, byte[] bytes) {
        return "\"" + kind + "\": {\"version\": \"" + version + "\", \"file\": \"" + file + "\", \"sha256\": \""
                + Hashing.sha256Hex(bytes) + "\"}";
    }

    /** Publish {@code manifest} under {@code base} with a signature by {@code key}, and each file beside it. */
    private void publish(String base, String manifest, KeyPair key, Map<String, byte[]> files)
            throws GeneralSecurityException {
        byte[] json = manifest.getBytes(StandardCharsets.UTF_8);
        replies.put(base + "updates.json", new Reply(200, json));
        replies.put(base + "updates.json.sig", new Reply(200, sign(json, key)));
        files.forEach((name, bytes) -> replies.put(base + name, new Reply(200, bytes)));
    }

    /** The price table published {@code days} after the built-in one, listed in a manifest of {@code at}. */
    private void publishPrices(Instant at, long days, double output) throws GeneralSecurityException {
        byte[] prices = table(later(days), output);
        publish(
                "/",
                manifest(at, entry("prices", later(days).toString(), "prices.toml", prices)),
                releaseKey,
                Map.of("prices.toml", prices));
    }

    private static byte[] sign(byte[] content, KeyPair key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(content);
        return Base64.getEncoder().encodeToString(signer.sign()).getBytes(StandardCharsets.US_ASCII);
    }

    private boolean newModelPriced() {
        return PriceTables.current(pricesFile()).price(NEW_MODEL + "-high").isPresent();
    }

    private UpdateState state() {
        return UpdateState.read(dir.resolve("store/updates"));
    }

    private void checkAndApply() {
        UpdateCheck check = check();
        if (check.check()) check.apply();
    }

    @Test
    void a_model_added_to_the_published_price_table_is_priced_after_one_check() throws Exception {
        assertThat(newModelPriced()).as("the built-in table does not know it").isFalse();
        publishPrices(later(5), 5, 15.0);

        UpdateCheck check = check();
        assertThat(check.check()).as("the new table waits to be applied").isTrue();
        assertThat(newModelPriced()).as("a check alone downloads nothing").isFalse();
        assertThat(check.apply()).isFalse();

        assertThat(newModelPriced()).isTrue();
        assertThat(PriceTables.current(pricesFile())
                        .price(NEW_MODEL)
                        .orElseThrow()
                        .output())
                .isEqualTo(15.0);
        assertThat(logs).singleElement().asString().contains("price table updated");
        assertThat(state().applied()).containsEntry("prices", later(5).toString());
        assertThat(state().pending()).isEmpty();
        assertThat(state().checked()).isEqualTo(clock.instant());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the jk-updates scripts run on the publishing Linux runner")
    void a_model_added_to_the_table_the_jk_updates_scripts_sign_is_priced_after_one_check() throws Exception {
        assumeTrue(runs("python3", "--version") && runs("openssl", "version"), "python3 and openssl on PATH");
        Path repo = Files.createDirectories(dir.resolve("jk-updates"));
        Files.write(repo.resolve("prices.toml"), table(later(5), 15.0));
        Path key = dir.resolve("release-key.pem");
        Files.writeString(
                key,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(releaseKey.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n");
        Path assemble = RepoRoot.file(UpdateCheckTest.class, "hosting/jk-updates/scripts/assemble.sh");

        Processes.Captured assembled = Processes.capture(
                List.of("bash", assemble.toString(), repo.toString(), key.toString()), Duration.ofMinutes(1));
        assertThat(assembled.ok()).as(assembled.output()).isTrue();
        for (String name : List.of("updates.json", "updates.json.sig", "prices.toml")) {
            replies.put("/" + name, new Reply(200, Files.readAllBytes(repo.resolve(name))));
        }

        checkAndApply();

        assertThat(newModelPriced()).isTrue();
        assertThat(state().applied()).containsEntry("prices", later(5).toString());
    }

    private static boolean runs(String... command) {
        return Processes.outputIfOk(List.of(command), Duration.ofSeconds(30)) != null;
    }

    @Test
    void the_jdk_catalog_is_kept_only_in_the_feeds_own_shape() throws Exception {
        Path catalog = dir.resolve("jdks/jdks.json");
        byte[] feed = "{\n  \"jdks\": []\n}\n".getBytes(StandardCharsets.UTF_8);

        UpdateCheck.applyJdks(feed, catalog);
        assertThat(catalog).hasBinaryContent(feed);

        assertThatThrownBy(() -> UpdateCheck.applyJdks("[]".getBytes(StandardCharsets.UTF_8), catalog))
                .hasMessageContaining("no jdks array");
        assertThat(catalog).as("the copy in use stays").hasBinaryContent(feed);
    }

    @Test
    void a_catalog_arrives_the_same_way_and_a_kind_this_jk_does_not_read_is_skipped() throws Exception {
        byte[] libraries = "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);
        byte[] advisories = "{}".getBytes(StandardCharsets.UTF_8);
        publish(
                "/",
                manifest(
                        later(5),
                        entry("libraries", "r42", "catalogs/libraries.toml", libraries) + ", "
                                + entry("some-future-kind", "1", "future.bin", advisories)),
                releaseKey,
                Map.of("catalogs/libraries.toml", libraries, "future.bin", advisories));

        checkAndApply();

        assertThat(catalogs).containsExactly(new String(libraries, StandardCharsets.UTF_8));
        assertThat(state().applied()).containsOnlyKeys("libraries");
        assertThat(requests).extracting(Seen::path).doesNotContain("/future.bin");

        catalogs.clear();
        requests.clear();
        checkAndApply();
        assertThat(catalogs)
                .as("a version already applied is not fetched again")
                .isEmpty();
        assertThat(requests).extracting(Seen::path).containsExactly("/updates.json");
    }

    @Test
    void the_jumpkick_release_a_signed_manifest_names_is_handed_on_with_every_answer() throws Exception {
        String sha = "b".repeat(64);
        String json = "{\n  \"published\": \"" + later(5) + "\",\n  \"jk\": {\"stable\": {\"version\": \"9.1.0\", "
                + "\"sha256\": \"" + sha + "\"}},\n  \"data\": {}\n}\n";
        publish("/", json, otherKey, Map.of());
        check().check();
        assertThat(answered)
                .as("a manifest whose signature fails names nothing")
                .isEmpty();

        publish("/", json, releaseKey, Map.of());
        assertThat(check().check()).as("a release is not a kind of data").isFalse();
        check().check();

        assertThat(answered)
                .as("the first answer and the 304 after it")
                .hasSize(2)
                .allSatisfy(m -> assertThat(m.release()).contains(new UpdateManifest.Release("9.1.0", sha)));
        assertThat(UpdateManifest.held(dir.resolve("store/updates"))
                        .flatMap(UpdateManifest::release)
                        .map(UpdateManifest.Release::version))
                .contains("9.1.0");
    }

    @Test
    void an_unchanged_manifest_is_a_conditional_request_answered_with_304() throws Exception {
        publishPrices(later(5), 5, 15.0);
        checkAndApply();
        requests.clear();

        assertThat(check().check()).isFalse();

        assertThat(requests).extracting(Seen::path).containsExactly("/updates.json");
        assertThat(requests.getFirst().headers().getFirst("If-None-Match")).isEqualTo(publishedEtag());
        assertThat(state().outcome()).isEqualTo("up to date");
        assertThat(newModelPriced()).isTrue();
    }

    @Test
    void offline_asks_nothing_and_changes_nothing() throws Exception {
        publishPrices(later(5), 5, 15.0);

        UpdateCheck offline = check(true, uri("/updates.json"));
        assertThat(offline.check()).isFalse();
        offline.apply();

        assertThat(requests).isEmpty();
        assertThat(pricesFile()).doesNotExist();
        assertThat(dir.resolve("store/updates")).doesNotExist();
    }

    @Test
    void a_failed_fetch_keeps_the_data_on_disk_and_says_nothing() throws Exception {
        publishPrices(later(5), 5, 15.0);
        checkAndApply();
        byte[] kept = Files.readAllBytes(pricesFile());
        logs.clear();

        replies.clear();
        replies.put("/updates.json", new Reply(500, new byte[0]));
        checkAndApply();
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            UpdateCheck check = check(false, dead.uri("/updates.json"));
            check.check();
            check.apply();
        }

        assertThat(Files.readAllBytes(pricesFile())).isEqualTo(kept);
        assertThat(newModelPriced()).isTrue();
        assertThat(logs).isEmpty();
        assertThat(state().outcome()).startsWith("failed: ");
    }

    @Test
    void the_fallback_source_is_asked_only_when_the_first_fails_and_serves_the_files_too() throws Exception {
        byte[] prices = table(later(5), 15.0);
        publish(
                "/fallback/",
                manifest(later(5), entry("prices", "v5", "prices.toml", prices)),
                releaseKey,
                Map.of("prices.toml", prices));

        checkAndApply();

        assertThat(requests)
                .extracting(Seen::path)
                .containsExactly(
                        "/updates.json",
                        "/fallback/updates.json",
                        "/fallback/updates.json.sig",
                        "/fallback/prices.toml");
        assertThat(newModelPriced()).isTrue();
    }

    @Test
    void a_manifest_that_does_not_verify_or_parse_is_ignored() throws Exception {
        byte[] prices = table(later(5), 15.0);
        String good = manifest(later(5), entry("prices", "v5", "prices.toml", prices));
        List<Runnable> bad = new ArrayList<>();
        bad.add(() -> replies.remove("/updates.json.sig"));
        bad.add(() -> replies.put(
                "/updates.json.sig",
                new Reply(200, unchecked(() -> sign(good.getBytes(StandardCharsets.UTF_8), otherKey)))));
        bad.add(() -> replies.put(
                "/updates.json", new Reply(200, good.replace("v5", "v6").getBytes(StandardCharsets.UTF_8))));
        bad.add(() -> replies.put("/updates.json.sig", new Reply(200, "not base64!".getBytes(StandardCharsets.UTF_8))));

        for (Runnable spoil : bad) {
            publish("/", good, releaseKey, Map.of("prices.toml", prices));
            spoil.run();
            checkAndApply();
            assertThat(pricesFile()).doesNotExist();
            assertThat(newModelPriced()).isFalse();
            assertThat(state().pending()).isEmpty();
        }
        publish("/", "{\"published\": \"yesterday\"}", releaseKey, Map.of());
        checkAndApply();
        publish(
                "/",
                manifest(
                        later(5),
                        "\"prices\": {\"version\": \"v5\", \"file\": \"../prices.toml\", \"sha256\": \""
                                + Hashing.sha256Hex(prices) + "\"}"),
                releaseKey,
                Map.of());
        checkAndApply();

        assertThat(pricesFile()).doesNotExist();
        assertThat(logs).isEmpty();
    }

    @Test
    void a_file_that_does_not_match_its_sha256_or_parse_is_not_applied_and_waits_for_the_next_check() throws Exception {
        byte[] prices = table(later(5), 15.0);
        publish(
                "/",
                manifest(later(5), entry("prices", "v5", "prices.toml", prices)),
                releaseKey,
                Map.of("prices.toml", table(later(5), 0.0)));

        checkAndApply();

        assertThat(pricesFile()).doesNotExist();
        assertThat(state().pending()).containsEntry("prices", "v5");

        byte[] broken = "published = 2099-01-01T00:00:00Z\nmodels = 3\n".getBytes(StandardCharsets.UTF_8);
        publish(
                "/",
                manifest(later(6), entry("prices", "v6", "prices.toml", broken)),
                releaseKey,
                Map.of("prices.toml", broken));
        checkAndApply();

        assertThat(pricesFile()).doesNotExist();
        assertThat(state().pending()).containsEntry("prices", "v6");
        assertThat(logs).isEmpty();
    }

    @Test
    void a_manifest_published_before_the_one_held_and_a_table_older_than_the_one_in_use_change_nothing()
            throws Exception {
        publishPrices(later(5), 5, 15.0);
        checkAndApply();
        byte[] kept = Files.readAllBytes(pricesFile());

        replies.clear();
        publishPrices(later(4), 9, 1.0);
        checkAndApply();
        assertThat(Files.readAllBytes(pricesFile())).as("an older manifest").isEqualTo(kept);

        replies.clear();
        publishPrices(later(7), 2, 1.0);
        checkAndApply();
        assertThat(Files.readAllBytes(pricesFile())).as("an older table").isEqualTo(kept);
        assertThat(PriceTables.current(pricesFile())
                        .price(NEW_MODEL)
                        .orElseThrow()
                        .output())
                .isEqualTo(15.0);
    }

    @Test
    void the_request_carries_nothing_about_the_user_or_the_project() throws Exception {
        publishPrices(later(5), 5, 15.0);
        checkAndApply();
        check().check();

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
        assertThat(requests)
                .extracting(Seen::path)
                .containsExactly("/updates.json", "/updates.json.sig", "/prices.toml", "/updates.json");
        assertThat(requests).allSatisfy(seen -> {
            assertThat(seen.query()).isNull();
            assertThat(seen.headers().keySet())
                    .extracting(name -> name.toLowerCase(Locale.ROOT))
                    .isSubsetOf(allowed);
            assertThat(seen.headers().getFirst("User-Agent"))
                    .as("the JDK's own client name and version, nothing of jk's or the host's")
                    .startsWith("Java-http-client/");
        });
        assertThat(requests.getLast().headers().getFirst("If-None-Match")).isEqualTo(publishedEtag());
    }

    private interface Signing {
        byte[] sign() throws GeneralSecurityException;
    }

    private static byte[] unchecked(Signing signing) {
        try {
            return signing.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
