// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.repo.ReleaseVerifier;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spawn path's engine-jar self-heal ({@link EngineJarFetcher}): download from the release
 * layout ({@code releases/<version>/jk-engine-<version>.jar} + {@code SHA256SUMS}), verify,
 * ingest into the CAS, and install under {@code <home>/lib/jk-engine/}. The
 * wiring INTO {@code spawn()} is native-client-only and stays manual-verification territory,
 * like the spawn itself (see {@code EngineClientTest}).
 */
@Tag("integration")
class EngineJarFetcherTest {

    private static final String VERSION = "1.2.3";
    private static final byte[] JAR = "fake engine jar bytes".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI base;
    private volatile byte[] sumsBody;
    private volatile byte @Nullable [] signatureBody;
    private volatile byte[] jarBody;
    private volatile int sumsStatus = 200;
    private volatile int signatureStatus = 200;
    private volatile int jarStatus = 200;
    /** Send the jar chunked — no {@code Content-Length} — as a proxy or a compressing CDN edge does. */
    private volatile boolean jarChunked;

    private KeyPair signingKey;
    private ReleaseVerifier verifier;

    @BeforeEach
    void start() throws IOException {
        jarBody = JAR;
        sumsBody = (Hashing.sha256Hex(JAR) + "  jk-engine-" + VERSION + ".jar\n").getBytes(StandardCharsets.UTF_8);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            signingKey = generator.generateKeyPair();
            verifier = ReleaseVerifier.of(List.of(
                    Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded())));
            signSums();
        } catch (Exception e) {
            throw new IOException("could not create test release key", e);
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases/" + VERSION + "/SHA256SUMS", exchange -> {
            exchange.sendResponseHeaders(sumsStatus, sumsStatus == 200 ? sumsBody.length : -1);
            if (sumsStatus == 200) exchange.getResponseBody().write(sumsBody);
            exchange.close();
        });
        server.createContext("/releases/" + VERSION + "/SHA256SUMS.sig", exchange -> {
            byte[] body = Objects.requireNonNull(signatureBody, "signSums() runs before the server starts");
            exchange.sendResponseHeaders(signatureStatus, signatureStatus == 200 ? body.length : -1);
            if (signatureStatus == 200) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/releases/" + VERSION + "/jk-engine-" + VERSION + ".jar", exchange -> {
            exchange.sendResponseHeaders(jarStatus, jarStatus != 200 ? -1 : jarChunked ? 0 : jarBody.length);
            if (jarStatus == 200) exchange.getResponseBody().write(jarBody);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/releases");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Cas cas(Path root) {
        return new Cas(root.resolve("cache"));
    }

    private static EngineInstall engineInstall(Path root) {
        return new EngineInstall(root.resolve("lib"));
    }

    private void signSums() throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey.getPrivate());
        signature.update(sumsBody);
        signatureBody = (Base64.getEncoder().encodeToString(signature.sign()) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Path fetch(Path root) throws IOException {
        return EngineJarFetcher.fetch(base, VERSION, cas(root), engineInstall(root), verifier);
    }

    @Test
    void fetch_verifies_and_materializes_cas_first(@TempDir Path root) throws Exception {
        var cas = cas(root);
        var install = engineInstall(root);
        Path installed = EngineJarFetcher.fetch(base, VERSION, cas, install, verifier);

        var m = install.resolve(VERSION).orElseThrow();
        assertThat(installed).isEqualTo(m.engineJar());
        assertThat(installed).hasBinaryContent(JAR);
        // The CAS holds the blob — a pruned version re-materializes from it offline.
        assertThat(cas.pathFor(Hashing.sha256Hex(JAR))).exists();
    }

    @Test
    void checksum_mismatch_fails_and_installs_nothing(@TempDir Path root) {
        jarBody = "tampered bytes".getBytes(StandardCharsets.UTF_8);

        var install = engineInstall(root);
        assertThatThrownBy(() -> fetch(root)).isInstanceOf(IOException.class).hasMessageContaining("checksum mismatch");
        assertThat(install.resolve(VERSION)).isEmpty();
    }

    @Test
    void missing_checksums_file_refuses_to_install(@TempDir Path root) {
        sumsStatus = 404;

        var install = engineInstall(root);
        assertThatThrownBy(() -> fetch(root)).isInstanceOf(IOException.class).hasMessageContaining("HTTP 404");
        assertThat(install.resolve(VERSION)).isEmpty();
    }

    @Test
    void checksums_without_an_entry_for_the_jar_refuses_to_install(@TempDir Path root) {
        sumsBody = ("a".repeat(64) + "  jk-linux-x86_64.xz\n").getBytes(StandardCharsets.UTF_8);
        try {
            signSums();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThatThrownBy(() -> fetch(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no unique exact entry");
    }

    @Test
    void missing_signature_refuses_to_install(@TempDir Path root) {
        signatureStatus = 404;

        assertThatThrownBy(() -> fetch(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("release signature")
                .hasMessageContaining("HTTP 404");
        assertThat(engineInstall(root).resolve(VERSION)).isEmpty();
    }

    @Test
    void missing_jar_fails_with_the_url_and_status(@TempDir Path root) {
        jarStatus = 404;

        assertThatThrownBy(() -> fetch(root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("engine jar")
                .hasMessageContaining("HTTP 404");
    }

    /** The gate that keeps this out of the JVM dist, offline runs, and dev/CI snapshot builds. */
    @Test
    void applicable_only_for_online_native_release_clients() {
        assertThat(EngineJarFetcher.applicable("1.2.3", true, false)).isTrue();
        assertThat(EngineJarFetcher.applicable("1.2.3-SNAPSHOT", true, false)).isFalse();
        assertThat(EngineJarFetcher.applicable("1.2.3", false, false)).isFalse();
        assertThat(EngineJarFetcher.applicable("1.2.3", true, true)).isFalse();
    }

    /** The rendering seam: what the view is told, in order, with the byte counts the bar shows. */
    @Test
    void the_progress_sink_sees_the_start_the_bytes_and_the_settled_jar(@TempDir Path root) throws Exception {
        List<String> events = new ArrayList<>();
        EngineJarFetcher.Progress sink = new EngineJarFetcher.Progress() {
            @Override
            public void start(String jarName, long totalBytes) {
                events.add("start " + jarName + " " + totalBytes);
            }

            @Override
            public void progress(long readBytes, long totalBytes) {
                events.add("progress " + readBytes + "/" + totalBytes);
            }

            @Override
            public void done(Path engineJar) {
                events.add("done " + engineJar);
            }
        };

        Path jar = EngineJarFetcher.fetch(base, VERSION, cas(root), engineInstall(root), verifier, sink);

        assertThat(events).first().isEqualTo("start jk-engine-" + VERSION + ".jar " + JAR.length);
        assertThat(events).contains("progress " + JAR.length + "/" + JAR.length);
        assertThat(events).last().isEqualTo("done " + jar);
        assertThat(events.indexOf("progress " + JAR.length + "/" + JAR.length))
                .as("the bar is fed before it settles")
                .isLessThan(events.size() - 1);
    }

    /**
     * A body with no declared length still streams, verifies and settles; the sink is told a size
     * of 0 throughout, which the bar renders without a percent rather than as a stall.
     */
    @Test
    void a_chunked_jar_reports_no_total_and_still_verifies(@TempDir Path root) throws Exception {
        jarChunked = true;
        List<String> events = new ArrayList<>();
        EngineJarFetcher.Progress sink = new EngineJarFetcher.Progress() {
            @Override
            public void start(String jarName, long totalBytes) {
                events.add("start " + totalBytes);
            }

            @Override
            public void progress(long readBytes, long totalBytes) {
                events.add("progress " + readBytes + "/" + totalBytes);
            }

            @Override
            public void done(Path engineJar) {
                events.add("done");
            }
        };

        Path jar = EngineJarFetcher.fetch(base, VERSION, cas(root), engineInstall(root), verifier, sink);

        assertThat(jar).hasBinaryContent(JAR);
        assertThat(events).first().isEqualTo("start 0");
        assertThat(events).contains("progress " + JAR.length + "/0");
        assertThat(events).last().isEqualTo("done");
    }
}
