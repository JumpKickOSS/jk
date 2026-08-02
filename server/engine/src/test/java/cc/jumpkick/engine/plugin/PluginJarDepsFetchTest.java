// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.WorkerClasspath;
import cc.jumpkick.model.JkVersion;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1351: a thin worker fetched from the official repo provisions its runtime classpath from the
 * published {@code <jar>.deps} coordinate closure — no install-local required on a cold store.
 * Legacy artifacts without {@code .deps} fetch exactly as before.
 */
class PluginJarDepsFetchTest {

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> served = new HashMap<>();
    /** Restored after each test — self-host / Gradle may pin a real publisher jar. */
    private String savedPublisherJarProp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        System.setProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY, base);
        // Self-host engine tests set -Djk.publisher.plugin.jar to the monorepo assembly
        // (server/engine/jk.toml test-plugin-jars). locate() would return that binary jar
        // before the cold-store official fetch — Files.readString then throws MalformedInput.
        savedPublisherJarProp = System.getProperty(PluginJar.PUBLISHER.jarProperty());
        System.clearProperty(PluginJar.PUBLISHER.jarProperty());
    }

    @AfterEach
    void stop() {
        System.clearProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY);
        if (savedPublisherJarProp != null) {
            System.setProperty(PluginJar.PUBLISHER.jarProperty(), savedPublisherJarProp);
        } else {
            System.clearProperty(PluginJar.PUBLISHER.jarProperty());
        }
        server.stop(0);
    }

    @Test
    void official_fetch_resolves_deps_and_writes_the_launch_sidecar(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "thin-worker-jar");
        serve("/" + rel + ".deps", "# closure\ncom.foo:lib:1.0\n");
        serve("/com/foo/lib/1.0/lib-1.0.jar", "dep-bytes");

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));

        assertThat(Files.readString(jar)).isEqualTo("thin-worker-jar");
        Path sidecar = WorkerClasspath.sidecarPath(jar);
        assertThat(sidecar).exists();
        java.util.List<String> entries = Files.readAllLines(sidecar).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        assertThat(entries).hasSize(1);
        Path dep = Path.of(entries.getFirst());
        assertThat(dep).exists();
        assertThat(Files.readString(dep)).isEqualTo("dep-bytes");
        // The launch classpath picks the dep up (worker jar has no vendored PluginMain).
        assertThat(WorkerClasspath.paths(jar)).contains(dep.toAbsolutePath().normalize());
    }

    @Test
    void legacy_artifact_without_deps_fetches_like_before(@TempDir Path tmp) throws Exception {
        String rel = PluginJar.PUBLISHER.relativePath();
        serve("/" + rel, "fat-worker-jar");

        Path jar = PluginJar.PUBLISHER.locate(new Cas(tmp.resolve("cache")));

        assertThat(Files.readString(jar)).isEqualTo("fat-worker-jar");
        assertThat(Files.exists(WorkerClasspath.sidecarPath(jar))).isFalse();
    }

    private void serve(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
